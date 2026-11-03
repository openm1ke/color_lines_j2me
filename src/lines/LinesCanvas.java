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
    private static final int S_SETTINGS = 4, S_STATS = 5;
    private final GameStore preferences = new GameStore();
    private final Soundtrack soundtrack = new Soundtrack();
    private final SoundEffects soundEffects = new SoundEffects();
    private int colorCount = 5, ballsPerTurn = 3;
    private boolean hasGame;
    private int settingsSel, settingsScroll, statsScroll, statsCount;
    private final int[] settingsBefore = new int[7];
    private boolean settingsEditing;
    private static final int SETTINGS_COUNT = 7, SETTINGS_VISIBLE = 7;
    private final int[] statsOrder = new int[45];
    private int selectionTick;
    private static final int[] BOUNCE = {0, 0, 1, 3, 5, 7, 8, 8, 7, 5, 3, 1, 0, 0, 0, 0};
    private int state = S_TITLE;
    private Thread loop;
    private volatile boolean running = false, paused = false;
    // Callbacks only publish requests; tick() owns all gameplay and drawing.
    private static final int E_KEY = 1, E_POINTER = 2, E_UNDO = 3, E_DEMO = 4;
    private static final int INPUT_CAPACITY = 32;
    private final int[] eventType = new int[INPUT_CAPACITY];
    private final int[] eventX = new int[INPUT_CAPACITY];
    private final int[] eventY = new int[INPUT_CAPACITY];
    private int eventHead, eventCount;
    private boolean stopped, exitRequested;
    private volatile boolean layoutDirty = true;
    private boolean resetKeys = true;
    private int keysPrev = 0;
    private final int tickDelay = 40;
    private int N = 9;
    private int[][] board = new int[N][N];
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
    private static int seedSequence;
    private int seed;

    private static synchronized int newGameSeed() {
        long now = System.currentTimeMillis();
        // Mix time with a sequence so rapid restarts do not reuse the same seed.
        seedSequence += 0x9E3779B9;
        int value = (int) (now ^ (now >>> 32)) ^ seedSequence;
        value ^= value >>> 16;
        value *= 0x45D9F3B;
        value ^= value >>> 16;
        return value & 0x7fffffff;
    }
    private int rnd() { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed; }
    private int rndRange(int n) { if (n <= 0) return 0; int r = rnd() % n; return (r < 0) ? -r : r; }
    private boolean moving = false;
    private int moveColor = 0;
    private int moveR, moveC;
    private int[] pathR = new int[N*N];
    private int[] pathC = new int[N*N];
    private int pathLen = 0, pathIdx = 0;
    private int stepDelay = 3, stepTick = 0;
    private static final int PREVIEW_K = 3;
    private final int[] nextR = new int[PREVIEW_K];
    private final int[] nextC = new int[PREVIEW_K];
    private final int[] nextCol = new int[PREVIEW_K];
    private int nextCount = 0;
    private final int[] queuedColors = new int[PREVIEW_K];
    private boolean clearing = false;
    private boolean refreshAfterClear = false;
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
    private final State[] hist = new State[128];
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

    protected void sizeChanged(int w, int h) { layoutDirty = true; }

    private void computeLayout() {
        int w = getWidth(), h = getHeight();
        Graphics g = getGraphics();

        int fh = g.getFont().getHeight();
        softBarH = fh + 6;
        softBarY = h - softBarH;
        cell = Math.max(4, Math.min((w - 16) / N, (softBarY - fh - 28) / (N + 1)));
        gridSizePx = cell * N;
        previewCell = Math.max(8, cell - 4);
        previewGap = Math.max(2, cell / 6);
        previewY = fh + 6;
        originY = previewY + previewCell + 8;
        originX = (w - gridSizePx) / 2;
    }

    public synchronized void start() {
        if (stopped) return;
        paused = false;
        soundtrack.setVolume(preferences.musicVolume);
        soundtrack.setActive(preferences.music);
        soundEffects.configure(preferences.effects, preferences.effectsVolume);
        soundEffects.setSuspended(false);
        resetKeys = true;
        if (loop == null) {
            running = true;
            loop = new Thread(this);
            loop.start();
        }
    }

    public synchronized void pause() {
        paused = true;
        soundtrack.setActive(false);
        soundEffects.setSuspended(true);
        eventHead = eventCount = 0;
        resetKeys = true;
    }

    public synchronized void stop() {
        stopped = true;
        soundtrack.close();
        soundEffects.close();
        running = false;
        eventHead = eventCount = 0;
    }

    public synchronized void requestExit() {
        if (!stopped) exitRequested = true;
    }

    private synchronized void enqueue(int type, int x, int y) {
        if (stopped || paused) return;
        // Keep memory bounded and preserve the order of accepted input.
        if (eventCount == INPUT_CAPACITY) return;
        int at = (eventHead + eventCount) % INPUT_CAPACITY;
        eventType[at] = type;
        eventX[at] = x;
        eventY[at] = y;
        eventCount++;
    }

    public void run() {
        try {
            while (running) {
                long t0 = System.currentTimeMillis();
                tick();
                int sleep = tickDelay - (int)(System.currentTimeMillis() - t0);
                if (sleep < 5) sleep = 5;
                try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
            }
        } finally {
            synchronized (this) { running = false; loop = null; }
        }
    }

    private void exitNow() {
        stop();
        if (host != null) host.onExitRequested();
    }

    private synchronized void tick() {
        if (stopped) return;
        // Exit is independent of pause, animations and a full input queue.
        if (exitRequested) { exitNow(); return; }
        if (paused) return;
        if (layoutDirty) {
            layoutDirty = false;
            computeLayout();
        }
        if (resetKeys) {
            keysPrev = getKeyStates();
            resetKeys = false;
        }
        // Process only this frame's batch; callbacks cannot change it mid-frame.
        int pending = eventCount;
        while (pending-- > 0 && !stopped) {
            int at = eventHead;
            eventHead = (eventHead + 1) % INPUT_CAPACITY;
            eventCount--;
            int type = eventType[at];
            if (type == E_KEY) handleKey(eventX[at]);
            else if (type == E_POINTER) handlePointer(eventX[at], eventY[at]);
            else if (type == E_UNDO) undoNow();
            else if (type == E_DEMO) fillDemoNow();
        }
        if (stopped) return;
        input();
        updateAnimation();
        render();
    }

    protected void keyPressed(int keyCode) {
        if (keyCode == 'q' || keyCode == 'Q') {
            requestExit();
        } else enqueue(E_KEY, keyCode, 0);
    }

    private void back() {
        if (state == S_TITLE) { exitNow(); return; }
        if (state == S_SETTINGS && settingsEditing) {
            preferences.size = settingsBefore[0]; preferences.colors = settingsBefore[1];
            preferences.balls = settingsBefore[2]; preferences.music = settingsBefore[3] != 0;
            preferences.musicVolume = settingsBefore[4]; preferences.effects = settingsBefore[5] != 0;
            preferences.effectsVolume = settingsBefore[6];
            soundtrack.setVolume(preferences.musicVolume);
            soundtrack.setActive(preferences.music);
            soundEffects.configure(preferences.effects, preferences.effectsVolume);
            settingsEditing = false;
        }
        state = S_TITLE;
        menuSel = 0;
    }

    private void enterSettings() {
        settingsBefore[0] = preferences.size; settingsBefore[1] = preferences.colors;
        settingsBefore[2] = preferences.balls; settingsBefore[3] = preferences.music ? 1 : 0;
        settingsBefore[4] = preferences.musicVolume; settingsBefore[5] = preferences.effects ? 1 : 0;
        settingsBefore[6] = preferences.effectsVolume;
        settingsEditing = true;
        state = S_SETTINGS;
        settingsSel = settingsScroll = 0;
    }

    private void saveSettings() {
        preferences.save();
        if (!preferences.storageOk) return;
        settingsEditing = false;
        state = S_TITLE;
        menuSel = 0;
    }

    private void handleKey(int keyCode) {
        if (keyCode == -7) { back(); return; }
        if (state == S_TITLE) {
            if (keyCode == -6) titleSelect();
        } else if (state == S_SETTINGS) {
            if (keyCode == -6) saveSettings();
        } else if (state == S_STATS || state == S_ABOUT) {
            if (keyCode == -6) back();
        } else if (!moving && !clearing) {
            if (keyCode == -6 || keyCode == KEY_NUM1 || keyCode == 'u' || keyCode == 'U') undoNow();
        }
    }

    private int menuCount() { return hasGame ? 6 : 5; }

    private void adjustSetting(int delta) {
        if (settingsSel == 0) {
            int i = preferences.size == 7 ? 0 : preferences.size == 9 ? 1 : 2;
            preferences.size = GameStore.SIZES[(i + delta + 3) % 3];
        } else if (settingsSel == 1) {
            preferences.colors = 3 + (preferences.colors - 3 + delta + 5) % 5;
        } else if (settingsSel == 2) {
            preferences.balls = 1 + (preferences.balls - 1 + delta + 3) % 3;
        } else if (settingsSel == 3) {
            preferences.music = !preferences.music;
            soundtrack.setVolume(preferences.musicVolume);
            soundtrack.setActive(preferences.music);
        } else if (settingsSel == 4) {
            preferences.musicVolume = AudioVolume.clamp(preferences.musicVolume + delta * 5);
            soundtrack.setVolume(preferences.musicVolume);
        } else if (settingsSel == 5) {
            preferences.effects = !preferences.effects;
            soundEffects.configure(preferences.effects, preferences.effectsVolume);
        } else {
            preferences.effectsVolume = AudioVolume.clamp(preferences.effectsVolume + delta * 5);
            soundEffects.configure(preferences.effects, preferences.effectsVolume);
        }
    }

    private void scrollSettings() {
        if (settingsSel < settingsScroll) settingsScroll = settingsSel;
        if (settingsSel >= settingsScroll + SETTINGS_VISIBLE) settingsScroll = settingsSel - SETTINGS_VISIBLE + 1;
    }

    private void input() {
        int ks = getKeyStates();
        int press = ks & ~keysPrev;
        keysPrev = ks;

        if (state == S_TITLE) {
            if ((press & (LEFT_PRESSED | UP_PRESSED)) != 0)  menuSel = (menuSel + menuCount() - 1) % menuCount();
            if ((press & (RIGHT_PRESSED | DOWN_PRESSED)) != 0) menuSel = (menuSel + 1) % menuCount();
            if ((press & FIRE_PRESSED) != 0) titleSelect();
            return;
        }

        if (state == S_SETTINGS) {
            if ((press & UP_PRESSED) != 0) settingsSel = (settingsSel + SETTINGS_COUNT - 1) % SETTINGS_COUNT;
            if ((press & DOWN_PRESSED) != 0) settingsSel = (settingsSel + 1) % SETTINGS_COUNT;
            scrollSettings();
            if ((press & LEFT_PRESSED) != 0) adjustSetting(-1);
            if ((press & (RIGHT_PRESSED | FIRE_PRESSED)) != 0) adjustSetting(1);
            return;
        }
        if (state == S_STATS) {
            if ((press & (UP_PRESSED | LEFT_PRESSED)) != 0 && statsScroll > 0) statsScroll--;
            if ((press & (DOWN_PRESSED | RIGHT_PRESSED)) != 0 && statsScroll + statsPageSize() < statsCount) statsScroll++;
            return;
        }
        if (state == S_ABOUT) {
            if ((press & (FIRE_PRESSED | LEFT_PRESSED)) != 0) back();
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
        enqueue(E_POINTER, px, py);
    }

    private void handlePointer(int px, int py) {
        if (py >= softBarY && py < softBarY + softBarH) {
            int w = getWidth();
            if (px < w / 3) {
                if (state == S_TITLE) titleSelect();
                else if (state == S_SETTINGS) saveSettings();
                else if (state == S_ABOUT || state == S_STATS) back();
                else undoNow();
            } else if (px > (w * 2) / 3) {
                back();
            }
            return;
        }
        if (state == S_TITLE) {
            int row = (py - menuY()) / (getGraphics().getFont().getHeight() + 6);
            if (py >= menuY() && row < menuCount()) { menuSel = row; titleSelect(); }
            return;
        }
        if (state == S_SETTINGS) {
            int row = (py - 50) / 26;
            if (py >= 50 && row < SETTINGS_VISIBLE && settingsScroll + row < SETTINGS_COUNT) {
                settingsSel = settingsScroll + row;
                adjustSetting(px < getWidth() / 2 ? -1 : 1);
            } else if (py >= 218) {
                settingsSel = (settingsSel + 1) % SETTINGS_COUNT;
                scrollSettings();
            }
            return;
        }
        if (state == S_STATS) {
            if (py < getHeight() / 2 && statsScroll > 0) statsScroll--;
            else if (statsScroll + statsPageSize() < statsCount) statsScroll++;
            return;
        }
        if (state == S_ABOUT) return;

        if (moving || clearing || state == S_GAMEOVER) return;
        if (py >= previewY && py < previewY + previewCell) {
            return;
        }

        if (px < originX || px >= originX + gridSizePx) return;
        if (py < originY || py >= originY + gridSizePx) return;
        curC = (px - originX) / cell;
        curR = (py - originY) / cell;
        handleSelectOrMoveAt(curR, curC);
    }

    private void titleSelect() {
        if (hasGame && menuSel == 0) { state = S_PLAY; return; }
        int item = hasGame ? menuSel - 1 : menuSel;
        if (item == 0) startNewGame();
        else if (item == 1) enterSettings();
        else if (item == 2) { state = S_STATS; refreshStatistics(); }
        else if (item == 3) state = S_ABOUT;
        else exitNow();
    }

    private void refreshStatistics() {
        statsCount = statsScroll = 0;
        for (int i = 0; i < 45; i++) {
            if (preferences.scoreAt(i) <= 0) continue;
            int j = statsCount;
            while (j > 0 && preferences.scoreAt(statsOrder[j - 1]) < preferences.scoreAt(i)) {
                statsOrder[j] = statsOrder[j - 1]; j--;
            }
            statsOrder[j] = i;
            statsCount++;
        }
    }

    private void handleSelectOrMoveAt(int r, int c) {
        if (selR < 0) {
            if (board[r][c] != 0) { selR = r; selC = c; selectionTick = 0; }
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
        if (state != S_PLAY && state != S_GAMEOVER) return;
        if (selR >= 0 && !moving && !clearing) selectionTick = (selectionTick + 1) % BOUNCE.length;
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
                preferences.record(N, colorCount, ballsPerTurn, score);

                clearing = false;
                clearTicks = 0;
                clearCountCached = 0;
                clearMark = new boolean[N][N];

                if (refreshAfterClear) {
                    prepareNextTurnPreviews();
                    refreshAfterClear = false;
                }
                pushSnapshot();

                if (emptyCount() == 0) { state = S_GAMEOVER; hasGame = false; }
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
            refreshAfterClear = started;
            if (!started) {
                prepareNextTurnPreviews();
                pushSnapshot();
                if (emptyCount() == 0) { state = S_GAMEOVER; hasGame = false; }
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
        soundEffects.pop();
        return true;
    }

    private void applyPreviewBalls() {
        boolean[] displaced = new boolean[PREVIEW_K];
        // Place uncontested previews first; relocation must not steal their cells.
        for (int i = 0; i < nextCount; i++) {
            int r = nextR[i], c = nextC[i];
            if (board[r][c] == 0) board[r][c] = nextCol[i];
            else displaced[i] = true;
        }
        for (int i = 0; i < nextCount; i++) {
            if (!displaced[i]) continue;
            int empties = emptyCount();
            if (empties == 0) return;
            int pick = rndRange(empties);
            for (int r = 0; r < N; r++) {
                for (int c = 0; c < N; c++) {
                    if (board[r][c] == 0 && pick-- == 0) {
                        board[r][c] = nextCol[i];
                        r = N;
                        break;
                    }
                }
            }
        }
    }

    private void generateQueuedColors() {
        for (int i = 0; i < PREVIEW_K; i++) {
            queuedColors[i] = 1 + rndRange(colorCount);
        }
    }

    private void generateNextPositions() {
        int empties = emptyCount();
        nextCount = (empties < ballsPerTurn) ? empties : ballsPerTurn;
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
        curR = curC = N / 2;
        selectionTick = 0;
        score = 0;
        moving = false;
        clearing = false;
        refreshAfterClear = false;
        clearTicks = 0; clearCountCached = 0;
        clearMark = new boolean[N][N];
        nextCount = 0;
        for (int i = 0; i < hist.length; i++) hist[i] = null;
        histSize = 0;
    }

    private void startNewGame() {
        N = preferences.size;
        colorCount = preferences.colors;
        ballsPerTurn = preferences.balls;
        board = new int[N][N];
        pathR = new int[N*N]; pathC = new int[N*N];
        clearBoard();
        hasGame = true;
        computeLayout();
        preferences.save();
        state = S_PLAY;
        seed = newGameSeed();
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
                        board[r][c] = 1 + rndRange(colorCount);
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
            System.arraycopy(hist, 1, hist, 0, hist.length - 1);
            histSize--;
        }
        hist[histSize++] = s;
    }

    public void undoOne() { enqueue(E_UNDO, 0, 0); }

    private void undoNow() {
        if (state != S_PLAY && state != S_GAMEOVER) return;
        if (moving || clearing) return;
        if (histSize <= 1) return;
        hist[--histSize] = null;
        State s = hist[histSize - 1];
        applySnapshot(s);
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
        hasGame = true;
        selectionTick = 0;

        moving = false;
        clearing = false;
        refreshAfterClear = false;
        clearTicks = 0; clearCountCached = 0;
        clearMark = new boolean[N][N];
    }

    private void render() {
        Graphics g = getGraphics();
        g.setColor(0x101010);
        g.fillRect(0, 0, getWidth(), getHeight());

        if (state == S_TITLE) { drawTitle(g); flushGraphics(); return; }
        if (state == S_ABOUT) { drawAbout(g); flushGraphics(); return; }
        if (state == S_SETTINGS) { drawSettings(g); flushGraphics(); return; }
        if (state == S_STATS) { drawStatistics(g); flushGraphics(); return; }

        int w = getWidth();
        int totalW = previewCell * ballsPerTurn + previewGap * (ballsPerTurn - 1);
        int px = (w - totalW) / 2;
        int py = previewY;

        for (int i = 0; i < ballsPerTurn; i++) {
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
                if (!moving && !clearing && r == selR && c == selC)
                    drawBouncingBall(g, x, y, cell, COLORS[colorIdx]);
                else drawBall(g, x, y, cell, COLORS[colorIdx]);
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

        g.drawString("Best: " + preferences.best(N, colorCount, ballsPerTurn), w - 2, 2, Graphics.TOP | Graphics.RIGHT);
        String mode = N + "x" + N + " / " + colorCount + " colors / +" + ballsPerTurn;
        g.setColor(0xAAAAAA);
        g.drawString(mode, w / 2, softBarY - g.getFont().getHeight() - 2, Graphics.TOP | Graphics.HCENTER);
        drawSoftBar(g, "Undo", "Menu");

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
        String[] items = hasGame ? new String[]{"RESUME", "NEW GAME", "SETTINGS", "STATISTICS", "ABOUT", "EXIT"}
                                 : new String[]{"START", "SETTINGS", "STATISTICS", "ABOUT", "EXIT"};
        int lh = g.getFont().getHeight();
        int y0 = menuY();
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
        int lh = g.getFont().getHeight();

        g.setColor(0xFFFFFF);
        int y = previewY + 2;
        String a1 = "Color Lines (\"Цветные линии\")";
        String a2 = "- GAMOS, 1992, MS-DOS";
        String a3 = "- openm1ke, 2025, J2ME";
        g.drawString(a1, 4, y, Graphics.TOP | Graphics.LEFT); y += lh + 4;
        g.drawString(a2, 4, y, Graphics.TOP | Graphics.LEFT); y += lh + 2;
        g.drawString(a3, 4, y, Graphics.TOP | Graphics.LEFT);

        y += lh + 10;
        g.drawString("Music: Minigame soundtrack", 4, y, Graphics.TOP | Graphics.LEFT); y += lh + 3;
        g.drawString("Kirard / CC0 1.0", 4, y, Graphics.TOP | Graphics.LEFT); y += lh + 3;
        g.drawString("Pop sounds: EZduzziteh / CC0", 4, y, Graphics.TOP | Graphics.LEFT); y += lh + 3;
        g.drawString("opengameart.org", 4, y, Graphics.TOP | Graphics.LEFT);
        drawSoftBar(g, "Back", "Back");
    }

    private int menuY() { return getHeight() / 2 - getGraphics().getFont().getHeight(); }

    private void drawSettings(Graphics g) {
        int w = getWidth();
        g.setColor(0xFFD24A);
        g.drawString("SETTINGS", w / 2, 14, Graphics.TOP | Graphics.HCENTER);
        String[] labels = {"Board", "Colors", "New balls", "Music", "Music volume", "Effects", "Effects volume"};
        String[] values = {preferences.size + "x" + preferences.size,
                           "" + preferences.colors, "" + preferences.balls, preferences.music ? "On" : "Off",
                           preferences.musicVolume + "%", preferences.effects ? "On" : "Off", preferences.effectsVolume + "%"};
        for (int i = settingsScroll; i < Math.min(SETTINGS_COUNT, settingsScroll + SETTINGS_VISIBLE); i++) {
            int y = 50 + (i - settingsScroll) * 26;
            if (settingsSel == i) { g.setColor(0x303848); g.fillRoundRect(8, y - 4, w - 16, 24, 6, 6); }
            g.setColor(settingsSel == i ? 0xFFFFFF : 0xAAAAAA);
            g.drawString(labels[i], 16, y, Graphics.TOP | Graphics.LEFT);
            g.drawString("< " + values[i] + " >", w - 16, y, Graphics.TOP | Graphics.RIGHT);
        }
        g.setColor(0xAAAAAA);
        int fh = g.getFont().getHeight();
        g.drawString("Up/Down, Left/Right: change", w / 2, softBarY - fh * 3 - 6, Graphics.TOP | Graphics.HCENTER);
        String note = (soundtrack.failed() || soundEffects.failed()) ? "Audio unavailable on device" : "Rules apply to NEW GAME";
        g.drawString(note, w / 2, softBarY - fh * 2 - 4, Graphics.TOP | Graphics.HCENTER);
        g.drawString(preferences.storageOk ? "Save applies / Back cancels" : "Storage unavailable!", w / 2,
                     softBarY - fh - 2, Graphics.TOP | Graphics.HCENTER);
        drawSoftBar(g, "Save", "Back");
    }

    private int statsPageSize() {
        return Math.max(1, (softBarY - 76) / (getGraphics().getFont().getHeight() * 2 + 12));
    }

    private void drawStatistics(Graphics g) {
        int w = getWidth(), fh = g.getFont().getHeight();
        g.setColor(0xFFD24A);
        g.drawString("RECORDS", w / 2, 12, Graphics.TOP | Graphics.HCENTER);
        g.setColor(0xAAAAAA);
        g.drawString("Separate best for each mode", w / 2, 16 + fh, Graphics.TOP | Graphics.HCENTER);
        if (statsCount == 0) {
            g.drawString("No records yet", w / 2, getHeight() / 2, Graphics.TOP | Graphics.HCENTER);
            g.drawString("Clear a line to set one!", w / 2, getHeight() / 2 + fh + 4, Graphics.TOP | Graphics.HCENTER);
        }
        int limit = Math.min(statsCount, statsScroll + statsPageSize());
        for (int row = statsScroll; row < limit; row++) {
            int i = statsOrder[row], y = 54 + (row - statsScroll) * (fh * 2 + 12);
            g.setColor(row == 0 ? 0xFFD24A : 0xFFFFFF);
            g.drawString((row + 1) + ". " + preferences.scoreAt(i) + " points", 12, y, Graphics.TOP | Graphics.LEFT);
            g.setColor(0xAAAAAA);
            String mode = GameStore.sizeAt(i) + "x" + GameStore.sizeAt(i) + " / " + GameStore.colorsAt(i) + " colors / +" + GameStore.ballsAt(i);
            g.drawString(mode, 12, y + fh + 2, Graphics.TOP | Graphics.LEFT);
        }
        g.setColor(0xAAAAAA);
        String footer = !preferences.storageOk ? "Records not saved!" : statsCount > statsPageSize() ? "Up / Down to scroll" : "Saved on this device";
        g.drawString(footer, w / 2, softBarY - fh - 2, Graphics.TOP | Graphics.HCENTER);
        drawSoftBar(g, "Back", "Back");
    }

    private void drawBouncingBall(Graphics g, int x, int y, int size, int rgb) {
        int phase = selectionTick % BOUNCE.length;
        int lift = BOUNCE[phase] * Math.max(2, size / 8) / 8;
        int diameter = size * 3 / 4;
        int squash = phase == 0 || phase == 12 ? Math.max(1, diameter / 12) : 0;
        int stretch = phase >= 3 && phase <= 9 ? Math.max(1, diameter / 16) : 0;
        int bw = diameter + squash - stretch;
        int bh = diameter - squash + stretch;
        int bx = x + (size - bw) / 2, by = y + size * 7 / 8 - bh - lift;
        int cx = g.getClipX(), cy = g.getClipY(), cw = g.getClipWidth(), ch = g.getClipHeight();
        g.clipRect(x + 1, y + 1, size - 2, size - 2);
        g.setColor(0x181818);
        g.fillArc(x + size / 4 + lift / 2, y + size * 7 / 8 - 1, Math.max(2, size / 2 - lift), 3, 0, 360);
        g.setColor(rgb); g.fillArc(bx, by, bw, bh, 0, 360);
        g.setColor(0x202020); g.drawArc(bx, by, bw, bh, 0, 360);
        g.setColor(0xFFFFFF); g.fillArc(bx + 2, by + 2, Math.max(2, bw / 2), Math.max(2, bh / 2), 0, 180);
        g.setClip(cx, cy, cw, ch);
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

    public void debugFillDemo() { enqueue(E_DEMO, 0, 0); }

    private void fillDemoNow() {
        state = S_PLAY;
        clearBoard();
        generateQueuedColors();
        prepareNextTurnPreviews();
        pushSnapshot();
    }
}
