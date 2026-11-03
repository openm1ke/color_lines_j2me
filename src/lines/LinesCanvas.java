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
    private static final int S_SETTINGS = 4, S_STATS = 5, S_HELP = 6, S_CONFIRM = 7;
    private final GameStore preferences = new GameStore();
    private final SessionStore sessionStore = new SessionStore(preferences.sessionName());
    private int moves, longestLine;
    private final Soundtrack soundtrack = new Soundtrack();
    private final SoundEffects soundEffects = new SoundEffects();
    private int colorCount = 5, ballsPerTurn = 3;
    private boolean hasGame;
    private int settingsSel, settingsScroll, statsScroll, statsCount;
    private final int[] settingsBefore = new int[8];
    private boolean settingsEditing;
    private static final int SETTINGS_COUNT = 9, SETTINGS_VISIBLE = 9;
    private static final int SETTINGS_TOP = 40, SETTINGS_STEP = 21;
    private final int[] statsOrder = new int[45];
    private int selectionTick, helpScroll, confirmSel;
    private String[] helpLines = new String[0];
    private static final String[] HELP_TEXT = {
        "Select a ball, then a cell.", "Use arrows + FIRE or tap.",
        "Join 5+ matching balls.", "Rows, columns and diagonals all count.",
        "Lit cells are reachable.", "Dotted lines preview paths.", "A shake means no free path.",
        "Small balls show next spawns.", "Occupied previews relocate.", "Clear a line: no new balls.",
        "Undo returns one turn.", "Menu pauses your game.", "Resume loads the last saved turn.",
        "New Game asks before replacing a game.", "Rules change on New Game.",
        "Patterns identify colors.", "Music and effects are separate.",
        "Save keeps settings.", "Back cancels settings."
    };
    private static final int[] BOUNCE = {0, 0, 1, 3, 5, 7, 8, 8, 7, 5, 3, 1, 0, 0, 0, 0};
    private int state = S_TITLE;
    private Thread loop;
    private volatile boolean running = false, paused = false;
    // Callbacks only publish requests; tick() owns all gameplay and drawing.
    private static final int E_KEY = 1, E_POINTER = 2, E_UNDO = 3, E_DEMO = 4, E_HOVER = 5;
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
    private int cell, originX, originY, gridSizePx, fontHeight;
    private int softBarH = 0, softBarY = 0;
    private int previewCell = 0;
    private int previewY = 0;
    private int previewGap = 2;
    private Image titleLogo = null;
    private int menuSel = 0; // 0=START, 1=ABOUT
    private int titleBlink = 0;
    private int curR = 4, curC = 4;
    private int selR = -1, selC = -1;
    private int score = 0, displayScore;
    private int popupScore, popupTicks, popupX, popupY;
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
    private boolean[][] reachable = new boolean[N][N];
    private int[] hintR = new int[N*N], hintC = new int[N*N];
    private int hintLen, invalidTicks;
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
    private int clearCountCached = 0, clearLongestCached;
    private int[][] clearOrder = new int[N][N];

    private static final class State {
        byte[] cells;
        byte nextCount;
        byte[] nR, nC, nCol;
        byte[] qCol;
        int score, seed, moves, longestLine;
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
        restoreSavedGame();
    }

    protected void sizeChanged(int w, int h) { layoutDirty = true; }

    private void computeLayout() {
        int w = getWidth(), h = getHeight();
        Graphics g = getGraphics();

        int fh = g.getFont().getHeight();
        fontHeight = fh;
        softBarH = fh + 6;
        softBarY = h - softBarH;
        cell = Math.max(4, Math.min((w - 16) / N, (softBarY - fh - 28) / (N + 1)));
        gridSizePx = cell * N;
        previewCell = Math.max(8, cell - 4);
        previewGap = Math.max(2, cell / 6);
        previewY = fh + 6;
        originY = previewY + previewCell + 8;
        originX = (w - gridSizePx) / 2;
        if (state == S_HELP) buildHelp();
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
        saveStableGame();
        soundtrack.setActive(false);
        soundEffects.setSuspended(true);
        eventHead = eventCount = 0;
        resetKeys = true;
    }

    public synchronized void stop() {
        saveStableGame();
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
            else if (type == E_HOVER) handleHover(eventX[at], eventY[at]);
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
            preferences.effectsVolume = settingsBefore[6]; preferences.patterns = settingsBefore[7] != 0;
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
        settingsBefore[6] = preferences.effectsVolume; settingsBefore[7] = preferences.patterns ? 1 : 0;
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
        if (state == S_CONFIRM) {
            if (keyCode == -6) back();
            else if (keyCode == -7) startNewGame();
            return;
        }
        if (keyCode == -7) { back(); return; }
        if (state == S_TITLE) {
            if (keyCode == -6) titleSelect();
        } else if (state == S_SETTINGS) {
            if (keyCode == -6) saveSettings();
        } else if (state == S_STATS || state == S_ABOUT || state == S_HELP) {
            if (keyCode == -6) back();
        } else if (!moving && !clearing) {
            if (keyCode == -6 || keyCode == KEY_NUM1 || keyCode == 'u' || keyCode == 'U') undoNow();
        }
    }

    private int menuCount() { return hasGame ? 7 : 6; }

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
        } else if (settingsSel == 6) {
            preferences.effectsVolume = AudioVolume.clamp(preferences.effectsVolume + delta * 5);
            soundEffects.configure(preferences.effects, preferences.effectsVolume);
        } else if (settingsSel == 7) preferences.patterns = !preferences.patterns;
        else {
            int preset = presetIndex();
            applyPreset(preset < 0 ? (delta > 0 ? 0 : 2) : (preset + delta + 3) % 3);
        }
    }

    private int presetIndex() {
        if (preferences.size != 9) return -1;
        if (preferences.colors == 4 && preferences.balls == 1) return 0;
        if (preferences.colors == 5 && preferences.balls == 2) return 1;
        if (preferences.colors == 7 && preferences.balls == 3) return 2;
        return -1;
    }

    private void applyPreset(int preset) {
        preferences.size = 9;
        preferences.colors = preset == 0 ? 4 : preset == 1 ? 5 : 7;
        preferences.balls = preset == 0 ? 1 : preset == 1 ? 2 : 3;
    }

    private String presetName() {
        int preset = presetIndex();
        return preset == 0 ? "Relaxed" : preset == 1 ? "Normal" : preset == 2 ? "Classic" : "Custom";
    }

    private void scrollSettings() {
        if (settingsSel < settingsScroll) settingsScroll = settingsSel;
        if (settingsSel >= settingsScroll + settingsVisible()) settingsScroll = settingsSel - settingsVisible() + 1;
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

        if (state == S_CONFIRM) {
            if ((press & (UP_PRESSED | DOWN_PRESSED | LEFT_PRESSED | RIGHT_PRESSED)) != 0) confirmSel = 1 - confirmSel;
            if ((press & FIRE_PRESSED) != 0) { if (confirmSel == 1) startNewGame(); else back(); }
            return;
        }
        if (state == S_HELP) {
            if ((press & (UP_PRESSED | LEFT_PRESSED)) != 0 && helpScroll > 0) helpScroll--;
            if ((press & (DOWN_PRESSED | RIGHT_PRESSED)) != 0 && helpScroll + helpPageSize() < helpLines.length) helpScroll++;
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

        int oldR = curR, oldC = curC;
        if ((press & LEFT_PRESSED)  != 0 && curC > 0)     curC--;
        if ((press & RIGHT_PRESSED) != 0 && curC < N - 1) curC++;
        if ((press & UP_PRESSED)    != 0 && curR > 0)     curR--;
        if ((press & DOWN_PRESSED)  != 0 && curR < N - 1) curR++;

        if (oldR != curR || oldC != curC) refreshPathHint();
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
                else if (state == S_CONFIRM || state == S_HELP) back();
                else if (state == S_SETTINGS) saveSettings();
                else if (state == S_ABOUT || state == S_STATS) back();
                else undoNow();
            } else if (px > (w * 2) / 3) {
                if (state == S_CONFIRM) startNewGame(); else back();
            }
            return;
        }
        if (state == S_TITLE) {
            int row = (py - menuY()) / menuStep();
            if (py >= menuY() && row < menuCount()) { menuSel = row; titleSelect(); }
            return;
        }
        if (state == S_CONFIRM) {
            if (py >= 116 && py < 188) {
                confirmSel = py < 152 ? 0 : 1;
                if (confirmSel == 1) startNewGame(); else back();
            }
            return;
        }
        if (state == S_HELP) {
            if (py < getHeight() / 2 && helpScroll > 0) helpScroll--;
            else if (helpScroll + helpPageSize() < helpLines.length) helpScroll++;
            return;
        }
        if (state == S_SETTINGS) {
            int row = (py - SETTINGS_TOP) / SETTINGS_STEP;
            if (py >= SETTINGS_TOP && row < settingsVisible() && settingsScroll + row < SETTINGS_COUNT) {
                settingsSel = settingsScroll + row;
                adjustSetting(px < getWidth() / 2 ? -1 : 1);
            } else if (py >= SETTINGS_TOP + settingsVisible() * SETTINGS_STEP) {
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
        if (item == 0) {
            if (hasGame) { state = S_CONFIRM; confirmSel = 0; }
            else startNewGame();
        }
        else if (item == 1) enterSettings();
        else if (item == 2) { state = S_STATS; refreshStatistics(); }
        else if (item == 3) { state = S_HELP; helpScroll = 0; buildHelp(); }
        else if (item == 4) state = S_ABOUT;
        else exitNow();
    }

    private void refreshStatistics() {
        statsCount = statsScroll = 0;
        for (int i = 0; i < 45; i++) {
            if (preferences.scoreAt(i) <= 0 && preferences.gamesAt(i) == 0) continue;
            int j = statsCount;
            while (j > 0 && preferences.scoreAt(statsOrder[j - 1]) < preferences.scoreAt(i)) {
                statsOrder[j] = statsOrder[j - 1]; j--;
            }
            statsOrder[j] = i;
            statsCount++;
        }
    }

    private void handleSelectOrMoveAt(int r, int c) {
        if (board[r][c] != 0) {
            if (selR == r && selC == c) { selR = selC = -1; hintLen = 0; return; }
            selR = r; selC = c; selectionTick = invalidTicks = 0;
            refreshReachable(); refreshPathHint();
            return;
        }
        if (selR < 0) return;
        int len = findPath(selR, selC, r, c, pathR, pathC);
        if (len > 0) {
            startMove(selR, selC, len);
            selR = selC = -1;
        } else {
            invalidTicks = 8;
            hintLen = 0;
        }
    }

    private void refreshReachable() {
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) reachable[r][c] = false;
        if (selR < 0) return;
        int[] queue = new int[N*N]; int head = 0, tail = 0;
        queue[tail++] = selR * N + selC; reachable[selR][selC] = true;
        int[] dr = {-1, 1, 0, 0}, dc = {0, 0, -1, 1};
        while (head < tail) {
            int at = queue[head++], r = at / N, c = at % N;
            for (int i = 0; i < 4; i++) {
                int nr = r + dr[i], nc = c + dc[i];
                if (nr < 0 || nr >= N || nc < 0 || nc >= N || reachable[nr][nc] || board[nr][nc] != 0) continue;
                reachable[nr][nc] = true; queue[tail++] = nr * N + nc;
            }
        }
    }

    private void refreshPathHint() {
        hintLen = 0;
        if (selR >= 0 && board[curR][curC] == 0 && reachable[curR][curC])
            hintLen = findPath(selR, selC, curR, curC, hintR, hintC);
    }

    protected void pointerDragged(int px, int py) { enqueue(E_HOVER, px, py); }

    private void handleHover(int px, int py) {
        if (state != S_PLAY || moving || clearing || selR < 0) return;
        if (px < originX || px >= originX + gridSizePx || py < originY || py >= originY + gridSizePx) return;
        curR = (py - originY) / cell; curC = (px - originX) / cell;
        refreshPathHint();
    }

    private void startMove(int sr, int sc, int len) {
        hintLen = invalidTicks = 0;
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
        if (invalidTicks > 0) invalidTicks--;
        if (popupTicks > 0) popupTicks--;
        if (displayScore < score) displayScore += Math.max(1, (score - displayScore) / 4);
        else if (displayScore > score) displayScore = score;
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
                longestLine = Math.max(longestLine, clearLongestCached);
                popupScore = clearCountCached; popupTicks = 30;

                clearing = false;
                clearTicks = 0;
                clearCountCached = 0;
                clearMark = new boolean[N][N];

                if (refreshAfterClear) {
                    prepareNextTurnPreviews();
                    refreshAfterClear = false;
                }
                completeTurn(clearLongestCached);
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
                completeTurn(0);
            }
        }
    }

    private void completeTurn(int lineLength) {
        if (moves < Integer.MAX_VALUE) moves++;
        longestLine = Math.max(longestLine, lineLength);
        preferences.recordTurn(N, colorCount, ballsPerTurn, score, lineLength);
        if (emptyCount() == 0) { state = S_GAMEOVER; hasGame = false; }
        pushSnapshot(); saveStableGame();
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
        int total = 0, longest = 0;

        // строки
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; ) {
                int col = board[r][c];
                if (col == 0) { c++; continue; }
                int s = c;
                while (c + 1 < N && board[r][c + 1] == col) c++;
                int len = c - s + 1;
                if (len >= 5) { longest = Math.max(longest, len); for (int x = s; x <= c; x++) if (!mark[r][x]) { mark[r][x] = true; total++; } }
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
                if (len >= 5) { longest = Math.max(longest, len); for (int y = s; y <= r; y++) if (!mark[y][c]) { mark[y][c] = true; total++; } }
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
                if (len >= 5) { longest = Math.max(longest, len); int rr = rs, cc = cs; while (rr <= r) { if (!mark[rr][cc]) { mark[rr][cc] = true; total++; } rr++; cc++; } }
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
                if (len >= 5) { longest = Math.max(longest, len); int rr = rs, cc = cs; while (rr <= r) { if (!mark[rr][cc]) { mark[rr][cc] = true; total++; } rr++; cc++; } }
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
                if (len >= 5) { longest = Math.max(longest, len); int rr = rs, cc = cs; while (rr <= r) { if (!mark[rr][cc]) { mark[rr][cc] = true; total++; } rr++; cc--; } }
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
                if (len >= 5) { longest = Math.max(longest, len); int rr = rs, cc = cs; while (rr <= r) { if (!mark[rr][cc]) { mark[rr][cc] = true; total++; } rr++; cc--; } }
                r++; c--;
            }
        }

        if (total == 0) return false;

        clearMark = mark;
        clearCountCached = total; clearLongestCached = longest;
        clearOrder = new int[N][N]; int order = 0, sumR = 0, sumC = 0;
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) if (mark[r][c]) {
            clearOrder[r][c] = order++; sumR += r; sumC += c;
        }
        popupX = originX + sumC * cell / total + cell / 2;
        popupY = originY + sumR * cell / total + cell / 2;
        clearTicksMax = 6 + Math.min(total - 1, 11);
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
        selectionTick = hintLen = invalidTicks = 0;
        reachable = new boolean[N][N]; hintR = new int[N*N]; hintC = new int[N*N];
        score = displayScore = popupTicks = popupScore = 0;
        clearOrder = new int[N][N]; clearLongestCached = 0;
        moves = longestLine = 0;
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
        preferences.beginGame(N, colorCount, ballsPerTurn);
        state = S_PLAY;
        seed = newGameSeed();
        addRandomBalls(5);
        generateQueuedColors();
        prepareNextTurnPreviews();
        pushSnapshot();
        saveStableGame();
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
        s.moves = moves; s.longestLine = longestLine;
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
        saveStableGame();
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
        score = displayScore = s.score;
        popupTicks = 0;
        seed = s.seed;
        moves = s.moves; longestLine = s.longestLine;
        curR = s.curR; curC = s.curC;
        selR = s.selR; selC = s.selC;
        state = s.state;
        hintLen = invalidTicks = 0;
        refreshReachable(); refreshPathHint();
        hasGame = true;
        selectionTick = 0;

        moving = false;
        clearing = false;
        refreshAfterClear = false;
        clearTicks = 0; clearCountCached = 0;
        clearMark = new boolean[N][N];
    }

    private void saveStableGame() {
        if (!hasGame) { if (state == S_GAMEOVER) sessionStore.clear(); return; }
        if (histSize == 0) return;
        State s = hist[histSize - 1];
        SavedGame game = new SavedGame();
        game.size = N; game.colors = colorCount; game.balls = ballsPerTurn;
        game.cells = s.cells; game.score = s.score; game.seed = s.seed;
        game.moves = s.moves; game.longestLine = s.longestLine; game.curR = s.curR; game.curC = s.curC;
        game.nextCount = s.nextCount;
        System.arraycopy(s.nR, 0, game.nextR, 0, 3); System.arraycopy(s.nC, 0, game.nextC, 0, 3);
        System.arraycopy(s.nCol, 0, game.nextCol, 0, 3); System.arraycopy(s.qCol, 0, game.queued, 0, 3);
        sessionStore.save(game);
    }

    private void restoreSavedGame() {
        SavedGame game = sessionStore.load();
        if (game == null) return;
        N = game.size; colorCount = game.colors; ballsPerTurn = game.balls;
        board = new int[N][N]; pathR = new int[N*N]; pathC = new int[N*N];
        clearBoard();
        State s = new State(); s.cells = game.cells; s.score = game.score; s.seed = game.seed;
        s.moves = game.moves; s.longestLine = game.longestLine; s.curR = game.curR; s.curC = game.curC;
        s.selR = s.selC = -1; s.state = S_PLAY; s.nextCount = (byte) game.nextCount;
        s.nR = game.nextR; s.nC = game.nextC; s.nCol = game.nextCol; s.qCol = game.queued;
        applySnapshot(s); pushSnapshot(); state = S_TITLE; computeLayout();
    }

    private void render() {
        Graphics g = getGraphics();
        g.setColor(0x101010);
        g.fillRect(0, 0, getWidth(), getHeight());

        if (state == S_TITLE) { drawTitle(g); flushGraphics(); return; }
        if (state == S_ABOUT) { drawAbout(g); flushGraphics(); return; }
        if (state == S_SETTINGS) { drawSettings(g); flushGraphics(); return; }
        if (state == S_STATS) { drawStatistics(g); flushGraphics(); return; }
        if (state == S_HELP) { drawHelp(g); flushGraphics(); return; }
        if (state == S_CONFIRM) { drawConfirm(g); flushGraphics(); return; }

        int w = getWidth();
        int totalW = previewCell * ballsPerTurn + previewGap * (ballsPerTurn - 1);
        int px = (w - totalW) / 2;
        int py = previewY;
        String moveText = "M:" + moves;
        if (g.getFont().stringWidth(moveText) < px - 4) { g.setColor(0xAAAAAA); g.drawString(moveText, 2, py, Graphics.TOP | Graphics.LEFT); }

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

            g.setColor(!moving && !clearing && selR >= 0 && reachable[r][c] && board[r][c] == 0 ? 0x263F34 : 0x333333);
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

        if (!moving && !clearing && selR >= 0 && hintLen > 0) {
            g.setColor(0xA0D8B0); g.setStrokeStyle(Graphics.DOTTED);
            int hx = originX + selC * cell + cell / 2, hy = originY + selR * cell + cell / 2;
            for (int i = 0; i < hintLen; i++) {
                int nx = originX + hintC[i] * cell + cell / 2, ny = originY + hintR[i] * cell + cell / 2;
                g.drawLine(hx, hy, nx, ny); hx = nx; hy = ny;
            }
            g.setStrokeStyle(Graphics.SOLID);
        }
        if (moving && moveColor != 0) {
            int x = originX + moveC * cell;
            int y = originY + moveR * cell;
            drawBall(g, x, y, cell, COLORS[moveColor]);
        }

        if (clearing) {
            for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
                if (!clearMark[r][c]) continue;
                int elapsed = clearTicks - Math.min(clearOrder[r][c], 11);
                if (elapsed >= 6) continue;
                int scale = elapsed <= 0 ? 256 : (6 - elapsed) * 256 / 6;
                drawBallScaled(g, originX + c * cell, originY + r * cell, cell, COLORS[board[r][c]], scale);
            }
        }

        int cx = originX + curC * cell, cy = originY + curR * cell;
        g.setColor(0xFFFFFF);
        g.drawRect(cx + 1, cy + 1, cell - 3, cell - 3);

        if (!moving && !clearing && selR >= 0) {
            int sx = originX + selC * cell;
            int sy = originY + selR * cell;
            g.setColor(invalidTicks > 0 ? 0xFF7755 : 0xFFFF66);
            g.drawRect(sx + 2, sy + 2, cell - 5, cell - 5);
        }

        g.setColor(0xFFFFFF);
        g.drawString("Score: " + displayScore, 2, 2, Graphics.TOP | Graphics.LEFT);

        g.drawString("Best: " + preferences.best(N, colorCount, ballsPerTurn), w - 2, 2, Graphics.TOP | Graphics.RIGHT);
        String mode = N + "x" + N + " / " + colorCount + " colors / +" + ballsPerTurn;
        g.setColor(0xAAAAAA);
        g.drawString(mode, w / 2, softBarY - g.getFont().getHeight() - 2, Graphics.TOP | Graphics.HCENTER);
        drawSoftBar(g, "Undo", "Menu");

        if (popupTicks > 0) {
            int y = Math.max(originY, popupY - (30 - popupTicks) / 2);
            g.setColor(0x101010); g.drawString("+" + popupScore, popupX + 1, y + 1, Graphics.TOP | Graphics.HCENTER);
            g.setColor(0xFFD24A); g.drawString("+" + popupScore, popupX, y, Graphics.TOP | Graphics.HCENTER);
        }
        if (state == S_GAMEOVER) {
            int lh = g.getFont().getHeight(), panelW = Math.min(w - 20, 210);
            int y = originY + gridSizePx / 2 - lh - 10;
            g.setColor(0x181818); g.fillRoundRect((w - panelW) / 2, y, panelW, lh * 2 + 20, 8, 8);
            g.setColor(0xFFD24A); g.drawString("GAME OVER", w / 2, y + 6, Graphics.TOP | Graphics.HCENTER);
            g.setColor(0xFFFFFF); g.drawString("FIRE: new / Undo", w / 2, y + lh + 10, Graphics.TOP | Graphics.HCENTER);
        }

        flushGraphics();
    }

    private void drawTitle(Graphics g) {
        int w = getWidth();
        int h = getHeight();

        g.setColor(0x101010);
        g.fillRect(0, 0, w, h);

        if (titleLogo != null && h >= 280) {
            int x = (w - titleLogo.getWidth()) / 2;
            int y = h / 5 - titleLogo.getHeight() / 2;
            if (y < 4) y = 4;
            g.drawImage(titleLogo, x, y, Graphics.TOP | Graphics.LEFT);
        } else {
            String t = "LINES";
            g.setColor(0xFFD24A);
            int tw = g.getFont().stringWidth(t);
            g.drawString(t, (w - tw)/2, 12, Graphics.TOP | Graphics.LEFT);
        }

        // пункты меню
        String[] items = hasGame ? new String[]{"RESUME", "NEW GAME", "SETTINGS", "STATISTICS", "HELP", "ABOUT", "EXIT"}
                                 : new String[]{"START", "SETTINGS", "STATISTICS", "HELP", "ABOUT", "EXIT"};
        int lh = g.getFont().getHeight();
        int y0 = menuY();
        for (int i = 0; i < items.length; i++) {
            boolean sel = (i == menuSel);
            g.setColor(sel ? 0xFFFFFF : 0xAAAAAA);
            int iw = g.getFont().stringWidth(items[i]);
            int ix = (w - iw)/2;
            int iy = y0 + i * menuStep();
            g.drawString(items[i], ix, iy, Graphics.TOP | Graphics.LEFT);

            if (sel) {
                g.drawString("<", ix - 10, iy, Graphics.TOP | Graphics.LEFT);
                g.drawString(">", ix + iw + 2, iy, Graphics.TOP | Graphics.LEFT);
            }
        }

        if (((titleBlink++/20) & 1) == 0) {
            g.setColor(0x808080);
            String tip = sessionStore.storageOk ? "FIRE / Select" : "Save unavailable";
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

    private int menuStep() { return fontHeight + (getHeight() < 280 ? 1 : 4); }
    private int menuY() {
        int fh = fontHeight;
        return Math.max(fh + 16, Math.min(getHeight() / 2 - fh, softBarY - menuCount() * menuStep() - fh - 12));
    }

    private void buildHelp() {
        java.util.Vector lines = new java.util.Vector();
        javax.microedition.lcdui.Font font = getGraphics().getFont();
        for (int i = 0; i < HELP_TEXT.length; i++) {
            String text = HELP_TEXT[i]; int start = 0;
            while (start < text.length()) {
                int end = start + 1;
                while (end <= text.length() && font.stringWidth(text.substring(start, end)) <= getWidth() - 20) end++;
                end = Math.max(start + 1, end - 1);
                if (end < text.length()) { int space = text.lastIndexOf(' ', end); if (space > start) end = space; }
                lines.addElement(text.substring(start, end)); start = end;
                while (start < text.length() && text.charAt(start) == ' ') start++;
            }
        }
        helpLines = new String[lines.size()]; lines.copyInto(helpLines);
        helpScroll = Math.min(helpScroll, Math.max(0, helpLines.length - helpPageSize()));
    }

    private int helpPageSize() { return Math.max(1, (softBarY - 72) / (fontHeight + 4)); }

    private void drawHelp(Graphics g) {
        int w = getWidth(), fh = g.getFont().getHeight();
        g.setColor(0xFFD24A); g.drawString("HOW TO PLAY", w / 2, 12, Graphics.TOP | Graphics.HCENTER);
        g.setColor(0xFFFFFF);
        for (int row = helpScroll; row < Math.min(helpLines.length, helpScroll + helpPageSize()); row++)
            g.drawString(helpLines[row], 10, 46 + (row - helpScroll) * (fh + 4), Graphics.TOP | Graphics.LEFT);
        g.setColor(0xAAAAAA); g.drawString("Up / Down to scroll", w / 2, softBarY - fh - 2, Graphics.TOP | Graphics.HCENTER);
        drawSoftBar(g, "Back", "Back");
    }

    private void drawConfirm(Graphics g) {
        int w = getWidth(), fh = g.getFont().getHeight();
        g.setColor(0xFFD24A); g.drawString("NEW GAME?", w / 2, 20, Graphics.TOP | Graphics.HCENTER);
        g.setColor(0xFFFFFF); g.drawString("Replace current game?", w / 2, 56, Graphics.TOP | Graphics.HCENTER);
        g.setColor(0xAAAAAA); g.drawString("Saved turn will be lost.", w / 2, 58 + fh, Graphics.TOP | Graphics.HCENTER);
        for (int i = 0; i < 2; i++) {
            int y = 120 + i * 36;
            if (confirmSel == i) { g.setColor(0x303848); g.fillRoundRect(12, y - 4, w - 24, 30, 6, 6); }
            g.setColor(confirmSel == i ? 0xFFFFFF : 0xAAAAAA);
            g.drawString(i == 0 ? "KEEP GAME" : "NEW GAME", w / 2, y, Graphics.TOP | Graphics.HCENTER);
        }
        drawSoftBar(g, "Cancel", "New");
    }

    private int settingsVisible() {
        int fh = fontHeight;
        int footerY = softBarY - fh * 3 - 6;
        return Math.max(1, Math.min(SETTINGS_VISIBLE, (footerY - SETTINGS_TOP - fh) / SETTINGS_STEP + 1));
    }

    private void drawSettings(Graphics g) {
        int w = getWidth();
        g.setColor(0xFFD24A);
        g.drawString("SETTINGS", w / 2, 14, Graphics.TOP | Graphics.HCENTER);
        String[] labels = {"Board", "Colors", "New balls", "Music", "Music volume", "Effects", "Effects volume", "Patterns", "Difficulty"};
        if (w < 220) labels = new String[]{"Board", "Colors", "New balls", "Music", "Music vol.", "Effects", "FX vol.", "Patterns", "Level"};
        String[] values = {preferences.size + "x" + preferences.size,
                           "" + preferences.colors, "" + preferences.balls, preferences.music ? "On" : "Off",
                           preferences.musicVolume + "%", preferences.effects ? "On" : "Off", preferences.effectsVolume + "%", preferences.patterns ? "On" : "Off", presetName()};
        for (int i = settingsScroll; i < Math.min(SETTINGS_COUNT, settingsScroll + settingsVisible()); i++) {
            int y = SETTINGS_TOP + (i - settingsScroll) * SETTINGS_STEP;
            if (settingsSel == i) { g.setColor(0x303848); g.fillRoundRect(8, y - 1, w - 16, 20, 6, 6); }
            g.setColor(settingsSel == i ? 0xFFFFFF : 0xAAAAAA);
            g.drawString(labels[i], 16, y, Graphics.TOP | Graphics.LEFT);
            g.drawString("< " + values[i] + " >", w - 16, y, Graphics.TOP | Graphics.RIGHT);
        }
        g.setColor(0xAAAAAA);
        int fh = g.getFont().getHeight();
        g.drawString(w < 220 ? "Arrows: change" : "Up/Down, Left/Right: change", w / 2, softBarY - fh * 3 - 6, Graphics.TOP | Graphics.HCENTER);
        String note = (soundtrack.failed() || soundEffects.failed()) ? "Audio unavailable" : w < 220 ? "Rules: NEW GAME" : "Rules apply to NEW GAME";
        g.drawString(note, w / 2, softBarY - fh * 2 - 4, Graphics.TOP | Graphics.HCENTER);
        g.drawString(preferences.storageOk ? (w < 220 ? "Save / Back: cancel" : "Save applies / Back cancels") : "Storage unavailable!", w / 2,
                     softBarY - fh - 2, Graphics.TOP | Graphics.HCENTER);
        drawSoftBar(g, "Save", "Back");
    }

    private int statsPageSize() {
        return Math.max(1, (softBarY - 76) / (fontHeight * 3 + 12));
    }

    private void drawStatistics(Graphics g) {
        int w = getWidth(), fh = g.getFont().getHeight();
        g.setColor(0xFFD24A);
        g.drawString("STATISTICS", w / 2, 12, Graphics.TOP | Graphics.HCENTER);
        g.setColor(0xAAAAAA);
        g.drawString(w < 220 ? "G:games M:moves L:line" : "G games / M moves / L line", w / 2, 16 + fh, Graphics.TOP | Graphics.HCENTER);
        if (statsCount == 0) {
            g.drawString("No games yet", w / 2, getHeight() / 2, Graphics.TOP | Graphics.HCENTER);
            g.drawString("Start a game to begin", w / 2, getHeight() / 2 + fh + 4, Graphics.TOP | Graphics.HCENTER);
        }
        int limit = Math.min(statsCount, statsScroll + statsPageSize());
        for (int row = statsScroll; row < limit; row++) {
            int i = statsOrder[row], y = 54 + (row - statsScroll) * (fh * 3 + 12);
            g.setColor(row == 0 ? 0xFFD24A : 0xFFFFFF);
            g.drawString((row + 1) + ". " + preferences.scoreAt(i) + " points", 12, y, Graphics.TOP | Graphics.LEFT);
            g.setColor(0xAAAAAA);
            String mode = GameStore.sizeAt(i) + "x" + GameStore.sizeAt(i) + " / " + GameStore.colorsAt(i) + " colors / +" + GameStore.ballsAt(i);
            g.drawString(mode, 12, y + fh + 2, Graphics.TOP | Graphics.LEFT);
            String details = "G:" + preferences.gamesAt(i) + "  M:" + preferences.movesAt(i) + "  L:" + preferences.lineAt(i);
            g.drawString(details, 12, y + fh * 2 + 4, Graphics.TOP | Graphics.LEFT);
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
        int shake = invalidTicks > 0 ? ((invalidTicks & 1) == 0 ? -2 : 2) : 0;
        int bx = x + (size - bw) / 2 + shake, by = y + size * 7 / 8 - bh - lift;
        int cx = g.getClipX(), cy = g.getClipY(), cw = g.getClipWidth(), ch = g.getClipHeight();
        g.clipRect(x + 1, y + 1, size - 2, size - 2);
        g.setColor(0x181818);
        g.fillArc(x + size / 4 + lift / 2, y + size * 7 / 8 - 1, Math.max(2, size / 2 - lift), 3, 0, 360);
        g.setColor(rgb); g.fillArc(bx, by, bw, bh, 0, 360);
        g.setColor(0x202020); g.drawArc(bx, by, bw, bh, 0, 360);
        g.setColor(0xFFFFFF); g.fillArc(bx + 2, by + 2, Math.max(2, bw / 2), Math.max(2, bh / 2), 0, 180);
        drawPattern(g, bx + bw / 2, by + bh / 2, Math.min(bw, bh), rgb);
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
        drawPattern(g, cx, cy, r * 2, rgb);
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
        drawPattern(g, cx, cy, r * 2, rgb);
    }

    private void drawSmallBall(Graphics g, int x, int y, int size, int rgb) {
        int r = Math.max(3, size / 5);
        int cx = x + size / 2;
        int cy = y + size / 2;

        g.setColor(rgb);
        g.fillArc(cx - r, cy - r, r * 2, r * 2, 0, 360);
        g.setColor(0x202020);
        g.drawArc(cx - r, cy - r, r * 2, r * 2, 0, 360);
        drawPattern(g, cx, cy, r * 2, rgb);
    }

    private void drawPattern(Graphics g, int x, int y, int diameter, int rgb) {
        if (!preferences.patterns || diameter < 6) return;
        int color = 0;
        for (int i = 1; i < COLORS.length; i++) if (COLORS[i] == rgb) { color = i; break; }
        int r = Math.max(1, Math.min(3, diameter / 4));
        g.setColor(color == 4 || color == 6 || color == 7 ? 0x101010 : 0xFFFFFF);
        if (color == 1) g.fillArc(x - 1, y - 1, 3, 3, 0, 360);
        else if (color == 2) g.drawLine(x - r, y, x + r, y);
        else if (color == 3) g.drawLine(x, y - r, x, y + r);
        else if (color == 4) { g.drawLine(x - r, y, x + r, y); g.drawLine(x, y - r, x, y + r); }
        else if (color == 5) { g.drawLine(x - r, y - r, x + r, y + r); g.drawLine(x - r, y + r, x + r, y - r); }
        else if (color == 6) g.drawArc(x - r, y - r, r * 2, r * 2, 0, 360);
        else if (color == 7) g.drawRect(x - r, y - r, r * 2, r * 2);
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
