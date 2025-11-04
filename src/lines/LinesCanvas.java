package lines;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.GameCanvas;

public final class LinesCanvas extends GameCanvas implements Runnable {

    public interface Host { void onExitRequested(); }
    private final Host host;

    private static final int S_TITLE = 0;
    private static final int S_PLAY  = 1;
    private static final int S_ABOUT = 2;
    private static final int S_GAMEOVER = 3;
    private int state = S_TITLE;
    private Thread loop;
    private boolean running = false, paused = false;
    private int keysPrev = 0;
    private final int tickDelay = 40;
    private static final int N = 9;
    private final int[][] board = new int[N][N];
    private int cell, originX, originY, gridSizePx;
    private int softBarH = 0, softBarY = 0;
    private int previewCell = 0;
    private int previewY = 0;
    private int previewGap = 2;
    private Image titleLogo = null;
    private int menuSel = 0; // 0=START, 1=ABOUT
    private int titleBlink = 0;
    private int curR = 4, curC = 4;
    private int selR = -1, selC = -1;
    private int score = 0;
    private static final int[] COLORS = {
        0,
        0xD13B3B, 0x3BA64B, 0x3E7BD6, 0xE0B13A,
        0xA73FD8, 0x2DB7B7, 0xE06B2E
    };
    private int seed = 123456789;
    private int rnd() { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed; }
    private int rndRange(int n) { if (n <= 0) return 0; int r = rnd() % n; return (r < 0) ? -r : r; }
    private boolean moving = false;
    private int moveColor = 0;
    private int moveR, moveC;
    private final int[] pathR = new int[N*N];
    private final int[] pathC = new int[N*N];
    private int pathLen = 0, pathIdx = 0;
    private int stepDelay = 3, stepTick = 0;
    private static final int PREVIEW_K = 3;
    private final int[] nextR = new int[PREVIEW_K];
    private final int[] nextC = new int[PREVIEW_K];
    private final int[] nextCol = new int[PREVIEW_K];
    private int nextCount = 0;
    private final int[] queuedColors = new int[PREVIEW_K];
    private boolean clearing = false;
    private boolean[][] clearMark = new boolean[N][N];
    private int clearTicks = 0;
    private int clearTicksMax = 6;
    private int clearCountCached = 0;

    private static final class State {
        byte[] cells;
        byte nextCount;
        byte[] nR, nC, nCol;
        byte[] qCol;
        int score, seed;
        int curR, curC, selR, selC, state;
    }
    private State[] hist = new State[128];
    private int histSize = 0;

    public LinesCanvas(Host host) {
        super(true);
        this.host = host;
        setFullScreenMode(true);
        computeLayout();
        try {
            titleLogo = Image.createImage("/img/lines2.png");
        } catch (Throwable ignored) { titleLogo = null; }
    }

    protected void sizeChanged(int w, int h) { computeLayout(); render(); }

    private void computeLayout() {
        int w = getWidth(), h = getHeight();
        Graphics g = getGraphics();

        int fh = g.getFont().getHeight();
        softBarH = fh + 6;
        int pad = Math.min(w, h) / 14;
        softBarY = h - softBarH;
        int availW = w - pad * 2;
        int availH = softBarY - pad - 2;
        gridSizePx = Math.min(availW, availH);
        cell = gridSizePx / N;
        gridSizePx = cell * N;
        previewCell = Math.max(10, cell - 4);
        previewGap = Math.max(2, cell / 6);
        previewY = pad;
        originY = previewY + previewCell + 4;
        originX = (w - gridSizePx) / 2;
        int bottom = originY + gridSizePx;
        int freeBelow = softBarY - 2 - bottom;
        if (freeBelow < 0) {
            int delta = -freeBelow;
            int rows = N;
            int shrink = (delta + rows - 1) / rows;
            cell = Math.max(8, cell - shrink);
            gridSizePx = cell * N;
            previewCell = Math.max(10, cell - 4);
            previewGap = Math.max(2, cell / 6);
            originX = (w - gridSizePx) / 2;
        }
    }

    public void start() { if (loop == null) { running = true; loop = new Thread(this); loop.start(); } else paused = false; }
    public void pause() { paused = true; }
    public void stop()  { running = false; loop = null; }

    public void run() {
        while (running) {
            long t0 = System.currentTimeMillis();
            if (!paused) {
                input();
                updateAnimation();
                render();
            }
            int sleep = tickDelay - (int)(System.currentTimeMillis() - t0);
            if (sleep < 5) sleep = 5;
            try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
        }
    }

    protected void keyPressed(int keyCode) {
        if (state == S_TITLE) {
            // Левая софтка (-6) = Select, Правая (-7) = Exit
            if (keyCode == -6) { titleSelect(); return; }
            if (keyCode == -7 || keyCode == 'q' || keyCode == 'Q') { if (host != null) host.onExitRequested(); return; }
        } else if (state == S_ABOUT) {
            if (keyCode == -6) { state = S_TITLE; return; } // Back
            if (keyCode == -7 || keyCode == 'q' || keyCode == 'Q') { if (host != null) host.onExitRequested(); return; }
        } else {
            // ИГРА: -6=Undo, -7=Exit
            if (moving || clearing) return;
            if (keyCode == -6 || keyCode == KEY_NUM1 || keyCode == 'u' || keyCode == 'U') { undoOne(); return; }
            if (keyCode == -7 || keyCode == 'q' || keyCode == 'Q') { if (host != null) host.onExitRequested(); return; }
        }
    }

    private void input() {
        int ks = getKeyStates();
        int press = ks & ~keysPrev;
        keysPrev = ks;

        if (state == S_TITLE) {
            if ((press & (LEFT_PRESSED | UP_PRESSED)) != 0)  menuSel = (menuSel + 1) & 1;
            if ((press & (RIGHT_PRESSED | DOWN_PRESSED)) != 0) menuSel = (menuSel + 1) & 1;
            if ((press & FIRE_PRESSED) != 0) titleSelect();
            return;
        }

        if (state == S_ABOUT) {
            if ((press & (FIRE_PRESSED | LEFT_PRESSED)) != 0) state = S_TITLE;
            return;
        }

        if (state == S_GAMEOVER) {
            if ((press & FIRE_PRESSED) != 0) { startNewGame(); state = S_PLAY; }
            return;
        }

        if (moving || clearing) return;

        if ((press & LEFT_PRESSED)  != 0 && curC > 0)     curC--;
        if ((press & RIGHT_PRESSED) != 0 && curC < N - 1) curC++;
        if ((press & UP_PRESSED)    != 0 && curR > 0)     curR--;
        if ((press & DOWN_PRESSED)  != 0 && curR < N - 1) curR++;

        if ((press & FIRE_PRESSED) != 0) handleSelectOrMoveAt(curR, curC);
    }

    protected void pointerPressed(int px, int py) {
        if (py >= softBarY && py < softBarY + softBarH) {
            int w = getWidth();
            if (px < w / 3) {
                if (state == S_TITLE)       titleSelect();
                else if (state == S_ABOUT)  state = S_TITLE;
                else                        undoOne();
            } else if (px > (w * 2) / 3) {
                if (host != null) host.onExitRequested();
            }
            return;
        }

        if (state == S_TITLE || state == S_ABOUT) {
            return;
        }

        if (moving || clearing || state == S_GAMEOVER) return;
        if (py >= previewY && py < previewY + previewCell) {
            return;
        }

        if (px < originX || px >= originX + gridSizePx) return;
        if (py < originY || py >= originY + gridSizePx) return;
        curC = (px - originX) / cell;
        curR = (py - originY) / cell;
        handleSelectOrMoveAt(curR, curC);
        render();
    }

    private void titleSelect() {
        if (menuSel == 0) {
            // START
            startNewGame();
            state = S_PLAY;
        } else {
            // ABOUT
            state = S_ABOUT;
        }
    }

    private void handleSelectOrMoveAt(int r, int c) {
        if (selR < 0) {
            if (board[r][c] != 0) { selR = r; selC = c; }
            return;
        }
        if (selR == r && selC == c) { selR = selC = -1; return; }

        int len = findPath(selR, selC, r, c, pathR, pathC);
        if (board[r][c] == 0 && len > 0) {
            startMove(selR, selC, len);
            selR = selC = -1;
        } else {
            selR = selC = -1;
        }
    }

    private void startMove(int sr, int sc, int len) {
        moving = true;
        moveColor = board[sr][sc];
        board[sr][sc] = 0;
        moveR = sr; moveC = sc;
        pathLen = len;
        pathIdx = 0;
        stepTick = 0;
    }

    private void updateAnimation() {
        if (moving) {
            if (++stepTick >= stepDelay) {
                stepTick = 0;
                moveR = pathR[pathIdx];
                moveC = pathC[pathIdx];
                pathIdx++;
                if (pathIdx >= pathLen) finishMove();
            }
        }

        if (clearing) {
            clearTicks++;
            if (clearTicks >= clearTicksMax) {
                for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
                    if (clearMark[r][c]) board[r][c] = 0;
                }
                score += clearCountCached;

                clearing = false;
                clearTicks = 0;
                clearCountCached = 0;
                clearMark = new boolean[N][N];

                pushSnapshot();

                if (emptyCount() == 0) state = S_GAMEOVER;
            }
        }
    }

    private void finishMove() {
        moving = false;
        board[moveR][moveC] = moveColor;

        boolean started = startClearIfAny();
        if (!started) {
            applyPreviewBalls();
            started = startClearIfAny();
            if (!started) {
                prepareNextTurnPreviews();
                pushSnapshot();
                if (emptyCount() == 0) state = S_GAMEOVER;
            }
        }
    }

    // BFS + восстановление пути
    private int findPath(int sr, int sc, int tr, int tc, int[] outR, int[] outC) {
        if (sr == tr && sc == tc) return 0;
        if (board[sr][sc] == 0 || board[tr][tc] != 0) return 0;

        boolean[][] vis = new boolean[N][N];
        int[][] pr = new int[N][N];
        int[][] pc = new int[N][N];
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) { pr[r][c] = -1; pc[r][c] = -1; }

        int[] qr = new int[N*N], qc = new int[N*N];
        int head = 0, tail = 0;

        vis[sr][sc] = true;
        qr[tail] = sr; qc[tail] = sc; tail++;

        final int[] dr = {-1, 1, 0, 0};
        final int[] dc = { 0, 0,-1, 1};
        boolean found = false;

        while (head < tail) {
            int r = qr[head], c = qc[head]; head++;
            for (int i = 0; i < 4; i++) {
                int nr = r + dr[i], nc = c + dc[i];
                if (nr < 0 || nr >= N || nc < 0 || nc >= N) continue;
                if (vis[nr][nc]) continue;
                if (nr == tr && nc == tc) { pr[nr][nc] = r; pc[nr][nc] = c; found = true; head = tail; break; }
                if (board[nr][nc] != 0) continue;
                vis[nr][nc] = true;
                pr[nr][nc] = r; pc[nr][nc] = c;
                qr[tail] = nr; qc[tail] = nc; tail++;
            }
        }
        if (!found) return 0;

        int len = 0, r = tr, c = tc;
        while (!(r == sr && c == sc)) { len++; int prr = pr[r][c], pcc = pc[r][c]; r = prr; c = pcc; }

        int idx = len - 1; r = tr; c = tc;
        while (!(r == sr && c == sc)) { outR[idx] = r; outC[idx] = c; int prr = pr[r][c], pcc = pc[r][c]; r = prr; c = pcc; idx--; }
        return len;
    }

    private boolean startClearIfAny() {
        if (clearing) return true;
        boolean[][] mark = new boolean[N][N];
        int total = 0;

        // строки
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; ) {
                int col = board[r][c];
                if (col == 0) { c++; continue; }
                int s = c;
                while (c + 1 < N && board[r][c + 1] == col) c++;
                int len = c - s + 1;
                if (len >= 5) { for (int x = s; x <= c; x++) if (!mark[r][x]) { mark[r][x] = true; total++; } }
                c++;
            }
        }

        // столбцы
        for (int c = 0; c < N; c++) {
            for (int r = 0; r < N; ) {
                int col = board[r][c];
                if (col == 0) { r++; continue; }
                int s = r;
                while (r + 1 < N && board[r + 1][c] == col) r++;
                int len = r - s + 1;
                if (len >= 5) { for (int y = s; y <= r; y++) if (!mark[y][c]) { mark[y][c] = true; total++; } }
                r++;
            }
        }

        // диагонали (\)
        for (int sc = 0; sc < N; sc++) {
            int r = 0, c = sc;
            while (c < N && r < N) {
                int col = board[r][c];
                if (col == 0) { r++; c++; continue; }
                int rs = r, cs = c;
                while (r + 1 < N && c + 1 < N && board[r + 1][c + 1] == col) { r++; c++; }
                int len = r - rs + 1;
                if (len >= 5) { int rr = rs, cc = cs; while (rr <= r) { if (!mark[rr][cc]) { mark[rr][cc] = true; total++; } rr++; cc++; } }
                r++; c++;
            }
        }
        for (int sr = 1; sr < N; sr++) {
            int r = sr, c = 0;
            while (c < N && r < N) {
                int col = board[r][c];
                if (col == 0) { r++; c++; continue; }
                int rs = r, cs = c;
                while (r + 1 < N && c + 1 < N && board[r + 1][c + 1] == col) { r++; c++; }
                int len = r - rs + 1;
                if (len >= 5) { int rr = rs, cc = cs; while (rr <= r) { if (!mark[rr][cc]) { mark[rr][cc] = true; total++; } rr++; cc++; } }
                r++; c++;
            }
        }

        // диагонали (/)
        for (int sc = 0; sc < N; sc++) {
            int r = 0, c = sc;
            while (r < N && c >= 0) {
                int col = board[r][c];
                if (col == 0) { r++; c--; continue; }
                int rs = r, cs = c;
                while (r + 1 < N && c - 1 >= 0 && board[r + 1][c - 1] == col) { r++; c--; }
                int len = r - rs + 1;
                if (len >= 5) { int rr = rs, cc = cs; while (rr <= r) { if (!mark[rr][cc]) { mark[rr][cc] = true; total++; } rr++; cc--; } }
                r++; c--;
            }
        }
        for (int sr = 1; sr < N; sr++) {
            int r = sr, c = N - 1;
            while (r < N && c >= 0) {
                int col = board[r][c];
                if (col == 0) { r++; c--; continue; }
                int rs = r, cs = c;
                while (r + 1 < N && c - 1 >= 0 && board[r + 1][c - 1] == col) { r++; c--; }
                int len = r - rs + 1;
                if (len >= 5) { int rr = rs, cc = cs; while (rr <= r) { if (!mark[rr][cc]) { mark[rr][cc] = true; total++; } rr++; cc--; } }
                r++; c--;
            }
        }

        if (total == 0) return false;

        clearMark = mark;
        clearCountCached = total;
        clearTicks = 0;
        clearing = true;
        return true;
    }

    private void applyPreviewBalls() {
        for (int i = 0; i < nextCount; i++) {
            int r = nextR[i], c = nextC[i];
            if (board[r][c] == 0) board[r][c] = nextCol[i];
        }
    }

    private void generateQueuedColors() {
        for (int i = 0; i < PREVIEW_K; i++) {
            queuedColors[i] = 1 + rndRange(COLORS.length - 1);
        }
    }

    private void generateNextPositions() {
        int empties = emptyCount();
        nextCount = (empties < PREVIEW_K) ? empties : PREVIEW_K;
        if (nextCount <= 0) return;

        int m = empties;
        int[] pos = new int[m];
        int k = 0;
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++)
            if (board[r][c] == 0) pos[k++] = r * N + c;

        for (int i = 0; i < nextCount; i++) {
            int pick = rndRange(m - i);
            int idx = pos[pick];
            nextR[i] = idx / N;
            nextC[i] = idx % N;
            int tmp = pos[m - 1 - i];
            pos[m - 1 - i] = pos[pick];
            pos[pick] = tmp;
        }
    }

    private void takeColorsFromQueue() {
        for (int i = 0; i < nextCount; i++) {
            nextCol[i] = queuedColors[i];
        }
    }

    private void prepareNextTurnPreviews() {
        generateNextPositions();
        takeColorsFromQueue();
        generateQueuedColors();
    }

    private int emptyCount() {
        int cnt = 0;
        for (int r = 0; r < N; r++)
            for (int c = 0; c < N; c++)
                if (board[r][c] == 0) cnt++;
        return cnt;
    }

    private void clearBoard() {
        for (int r = 0; r < N; r++)
            for (int c = 0; c < N; c++)
                board[r][c] = 0;
        selR = selC = -1;
        curR = curC = 4;
        score = 0;
        moving = false;
        clearing = false;
        clearTicks = 0; clearCountCached = 0;
        clearMark = new boolean[N][N];
        nextCount = 0;
        histSize = 0;
    }

    private void startNewGame() {
        clearBoard();
        addRandomBalls(5);
        generateQueuedColors();
        prepareNextTurnPreviews();
        pushSnapshot();
    }

    private void addRandomBalls(int k) {
        for (int i = 0; i < k; i++) {
            int empties = emptyCount();
            if (empties == 0) return;
            int pick = rndRange(empties);
            int idx = 0;
            for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
                if (board[r][c] == 0) {
                    if (idx == pick) {
                        board[r][c] = 1 + rndRange(COLORS.length - 1);
                        r = N; break;
                    }
                    idx++;
                }
            }
        }
    }

    private void pushSnapshot() {
        State s = new State();
        s.cells = new byte[N*N];
        int p = 0;
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) s.cells[p++] = (byte) board[r][c];
        s.nextCount = (byte) nextCount;

        s.nR = new byte[PREVIEW_K];
        s.nC = new byte[PREVIEW_K];
        s.nCol = new byte[PREVIEW_K];
        for (int i = 0; i < PREVIEW_K; i++) {
            s.nR[i] = (byte) nextR[i];
            s.nC[i] = (byte) nextC[i];
            s.nCol[i] = (byte) nextCol[i];
        }

        s.qCol = new byte[PREVIEW_K];
        for (int i = 0; i < PREVIEW_K; i++) s.qCol[i] = (byte) queuedColors[i];

        s.score = score;
        s.seed = seed;
        s.curR = curR; s.curC = curC;
        s.selR = selR; s.selC = selC;
        s.state = state;

        if (histSize == hist.length) {
            State[] n = new State[hist.length * 2];
            for (int i = 0; i < hist.length; i++) n[i] = hist[i];
            hist = n;
        }
        hist[histSize++] = s;
    }

    public void undoOne() {
        if (moving || clearing) return;
        if (histSize <= 1) return;
        histSize--;
        State s = hist[histSize - 1];
        applySnapshot(s);
        render();
    }

    private void applySnapshot(State s) {
        int p = 0;
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) board[r][c] = s.cells[p++];
        nextCount = s.nextCount;
        for (int i = 0; i < PREVIEW_K; i++) {
            nextR[i] = s.nR[i];
            nextC[i] = s.nC[i];
            nextCol[i] = s.nCol[i];
            queuedColors[i] = s.qCol[i];
        }
        score = s.score;
        seed = s.seed;
        curR = s.curR; curC = s.curC;
        selR = s.selR; selC = s.selC;
        state = s.state;

        moving = false;
        clearing = false;
        clearTicks = 0; clearCountCached = 0;
        clearMark = new boolean[N][N];
    }

    private void render() {
        Graphics g = getGraphics();
        g.setColor(0x101010);
        g.fillRect(0, 0, getWidth(), getHeight());

        if (state == S_TITLE) { drawTitle(g); flushGraphics(); return; }
        if (state == S_ABOUT) { drawAbout(g); flushGraphics(); return; }

        int w = getWidth();
        int totalW = previewCell * 3 + previewGap * 2;
        int px = (w - totalW) / 2;
        int py = previewY;

        for (int i = 0; i < 3; i++) {
            int x = px + i * (previewCell + previewGap);
            g.setColor(0x2F2F2F);
            g.fillRect(x, py, previewCell, previewCell);
            g.setColor(0x606060);
            g.drawRect(x, py, previewCell, previewCell);
            drawBall(g, x, py, previewCell, COLORS[queuedColors[i]]);
        }

        g.setColor(0x2A2A2A);
        g.fillRoundRect(originX - 3, originY - 3, gridSizePx + 6, gridSizePx + 6, 8, 8);
        g.setColor(0x555555);
        g.drawRoundRect(originX - 3, originY - 3, gridSizePx + 6, gridSizePx + 6, 8, 8);

        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
            int x = originX + c * cell;
            int y = originY + r * cell;

            g.setColor(0x333333);
            g.fillRect(x, y, cell, cell);

            int colorIdx = board[r][c];
            if (colorIdx != 0 && !(clearing && clearMark[r][c])) {
                drawBall(g, x, y, cell, COLORS[colorIdx]);
            }

            g.setColor(0x666666);
            g.drawRect(x, y, cell, cell);
        }

        for (int i = 0; i < nextCount; i++) {
            int r = nextR[i], c = nextC[i];
            if (board[r][c] != 0) continue;
            int x = originX + c * cell;
            int y = originY + r * cell;
            drawSmallBall(g, x, y, cell, COLORS[nextCol[i]]);
        }

        if (moving && moveColor != 0) {
            int x = originX + moveC * cell;
            int y = originY + moveR * cell;
            drawBall(g, x, y, cell, COLORS[moveColor]);
        }

        if (clearing) {
            int scale = (clearTicksMax - clearTicks) * 256 / clearTicksMax; // 256..0
            if (scale < 0) scale = 0;
            for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
                if (!clearMark[r][c]) continue;
                int x = originX + c * cell;
                int y = originY + r * cell;
                int col = COLORS[board[r][c]];
                drawBallScaled(g, x, y, cell, col, scale);
            }
        }

        int cx = originX + curC * cell, cy = originY + curR * cell;
        g.setColor(0xFFFFFF);
        g.drawRect(cx + 1, cy + 1, cell - 3, cell - 3);

        if (!moving && !clearing && selR >= 0) {
            int sx = originX + selC * cell;
            int sy = originY + selR * cell;
            g.setColor(0xFFFF66);
            g.drawRect(sx + 2, sy + 2, cell - 5, cell - 5);
        }

        g.setColor(0xFFFFFF);
        g.drawString("Score: " + score, 2, 2, Graphics.TOP | Graphics.LEFT);

        drawSoftBar(g, "Undo", "Exit");

        if (state == S_GAMEOVER) {
            String a = "GAME OVER (full)";
            int lh = g.getFont().getHeight();
            int w1 = g.getFont().stringWidth(a);
            g.drawString(a, (w - w1) / 2, softBarY - lh - 6, Graphics.TOP | Graphics.LEFT);
        }

        flushGraphics();
    }

    private void drawTitle(Graphics g) {
        int w = getWidth();
        int h = getHeight();

        g.setColor(0x101010);
        g.fillRect(0, 0, w, h);

        if (titleLogo != null) {
            int x = (w - titleLogo.getWidth()) / 2;
            int y = h / 5 - titleLogo.getHeight() / 2;
            if (y < 4) y = 4;
            g.drawImage(titleLogo, x, y, Graphics.TOP | Graphics.LEFT);
        } else {
            String t = "LINES";
            g.setColor(0xFFD24A);
            int tw = g.getFont().stringWidth(t);
            g.drawString(t, (w - tw)/2, h/5, Graphics.TOP | Graphics.LEFT);
        }

        // пункты меню
        String[] items = {"START", "ABOUT"};
        int lh = g.getFont().getHeight();
        int y0 = h/2 - lh;
        for (int i = 0; i < items.length; i++) {
            boolean sel = (i == menuSel);
            g.setColor(sel ? 0xFFFFFF : 0xAAAAAA);
            int iw = g.getFont().stringWidth(items[i]);
            int ix = (w - iw)/2;
            int iy = y0 + i * (lh + 6);
            g.drawString(items[i], ix, iy, Graphics.TOP | Graphics.LEFT);

            if (sel) {
                g.drawString("<", ix - 10, iy, Graphics.TOP | Graphics.LEFT);
                g.drawString(">", ix + iw + 2, iy, Graphics.TOP | Graphics.LEFT);
            }
        }

        if (((titleBlink++/20) & 1) == 0) {
            g.setColor(0x808080);
            String tip = "FIRE / Select";
            int tw = g.getFont().stringWidth(tip);
            g.drawString(tip, (w - tw)/2, softBarY - lh - 6, Graphics.TOP | Graphics.LEFT);
        }

        drawSoftBar(g, "Select", "Exit");
    }

    private void drawAbout(Graphics g) {
        int w = getWidth();
        int lh = g.getFont().getHeight();

        g.setColor(0xFFFFFF);
        int y = previewY + 2;
        String a1 = "Color Lines (\"Цветные линии\")";
        String a2 = "- GAMOS, 1992, MS-DOS";
        String a3 = "- openm1ke, 2025, J2ME";
        g.drawString(a1, 4, y, Graphics.TOP | Graphics.LEFT); y += lh + 4;
        g.drawString(a2, 4, y, Graphics.TOP | Graphics.LEFT); y += lh + 2;
        g.drawString(a3, 4, y, Graphics.TOP | Graphics.LEFT);

        drawSoftBar(g, "Back", "Exit");
    }

    private void drawSoftBar(Graphics g, String left, String right) {
        int w = getWidth();
        g.setColor(0x303030);
        g.fillRect(0, softBarY, w, softBarH);
        g.setColor(0x888888);
        g.drawLine(0, softBarY, w, softBarY);

        int by = softBarY + 3;
        g.setColor(0xFFFFFF);
        g.drawString(left, 4, by, Graphics.TOP | Graphics.LEFT);
        g.drawString(right, w - 4, by, Graphics.TOP | Graphics.RIGHT);
    }

    private void drawBall(Graphics g, int x, int y, int size, int rgb) {
        int r = (size * 3) / 8;
        int cx = x + size / 2;
        int cy = y + size / 2;

        g.setColor(rgb);
        g.fillArc(cx - r, cy - r, r * 2, r * 2, 0, 360);

        g.setColor(0x202020);
        g.drawArc(cx - r, cy - r, r * 2, r * 2, 0, 360);

        g.setColor(0xFFFFFF);
        g.fillArc(cx - r + 3, cy - r + 3, r, r, 0, 180);
    }

    private void drawBallScaled(Graphics g, int x, int y, int size, int rgb, int scale) {
        if (scale <= 0) return;
        int baseR = (size * 3) / 8;
        int r = (baseR * scale) >> 8;
        if (r <= 0) r = 1;
        int cx = x + size / 2;
        int cy = y + size / 2;

        g.setColor(rgb);
        g.fillArc(cx - r, cy - r, r * 2, r * 2, 0, 360);
        g.setColor(0x202020);
        g.drawArc(cx - r, cy - r, r * 2, r * 2, 0, 360);
        int hl = (r <= 4) ? r/2 : r/2 + 1;
        if (hl > 0) { g.setColor(0xFFFFFF); g.fillArc(cx - r + 3, cy - r + 3, hl, hl, 0, 180); }
    }

    private void drawSmallBall(Graphics g, int x, int y, int size, int rgb) {
        int r = Math.max(3, size / 5);
        int cx = x + size / 2;
        int cy = y + size / 2;

        g.setColor(rgb);
        g.fillArc(cx - r, cy - r, r * 2, r * 2, 0, 360);
        g.setColor(0x202020);
        g.drawArc(cx - r, cy - r, r * 2, r * 2, 0, 360);
    }

    public void debugFillDemo() {
        state = S_PLAY;
        clearBoard();
        generateQueuedColors();
        prepareNextTurnPreviews();
        pushSnapshot();
    }
}
