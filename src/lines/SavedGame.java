package lines;

/** A stable turn, never a half-finished animation. */
public final class SavedGame {
    public int size, colors, balls, score, seed, moves, longestLine, curR, curC, nextCount;
    public int dailyDay, startingBest = -1;
    public boolean recordCelebrated, threatened;
    public byte[] cells;
    public final byte[] nextR = new byte[3], nextC = new byte[3], nextCol = new byte[3], queued = new byte[3];

    public boolean valid() {
        if (startingBest < -1 || (dailyDay != 0 && (!ProgressStore.validDay(dailyDay) || size != 9 || colors != 5 || balls != 2))) return false;
        if (GameStore.index(size, colors, balls) < 0 || cells == null || cells.length != size * size
            || score < 0 || seed < 0 || moves < 0 || longestLine < 0 || longestLine > size
            || curR < 0 || curR >= size || curC < 0 || curC >= size || nextCount < 0 || nextCount > balls) return false;
        int empty = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] < 0 || cells[i] > colors) return false;
            if (cells[i] == 0) empty++;
        }
        if (empty == 0) return false;
        for (int i = 0; i < 3; i++) {
            if (queued[i] < 1 || queued[i] > colors) return false;
            if (i < nextCount) {
                if (nextR[i] < 0 || nextR[i] >= size || nextC[i] < 0 || nextC[i] >= size
                    || nextCol[i] < 1 || nextCol[i] > colors) return false;
                for (int j = 0; j < i; j++) if (nextR[i] == nextR[j] && nextC[i] == nextC[j]) return false;
            }
        }
        return true;
    }
}
