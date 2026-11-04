package lines;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import javax.microedition.rms.RecordStore;

/** One small RMS record: preferences and a separate best score for each ruleset. */
public final class GameStore {
    public static final int[] SIZES = {7, 9, 11};
    private static final int MAGIC = 0x4C4E5301;
    public int size = 9, colors = 5, balls = 3;
    public boolean music;
    public int musicVolume = 35, effectsVolume = 70;
    public boolean effects = true, patterns;
    public boolean storageOk = true;
    private final int[] best = new int[45];
    private final int[] games = new int[45], turns = new int[45], line = new int[45];
    private final String name;

    public GameStore() {
        String override = null;
        try { override = System.getProperty("lines.store"); } catch (SecurityException ignored) {}
        name = override == null ? "lines-preferences-v1" : override;
        load();
    }

    public String sessionName() { return name + "-game"; }
    public String progressName() { return name + "-progress"; }

    public static int index(int size, int colors, int balls) {
        int s = size == 7 ? 0 : size == 9 ? 1 : size == 11 ? 2 : -1;
        if (s < 0 || colors < 3 || colors > 7 || balls < 1 || balls > 3) return -1;
        return s * 15 + (colors - 3) * 3 + balls - 1;
    }

    public int best(int size, int colors, int balls) {
        int i = index(size, colors, balls);
        return i < 0 ? 0 : best[i];
    }

    public int gamesAt(int i) { return games[i]; }
    public int movesAt(int i) { return turns[i]; }
    public int lineAt(int i) { return line[i]; }

    public void beginGame(int size, int colors, int balls) {
        int i = index(size, colors, balls);
        if (i < 0) return;
        if (games[i] < Integer.MAX_VALUE) games[i]++;
        save();
    }

    public void recordTurn(int size, int colors, int balls, int score, int longest) {
        int i = index(size, colors, balls);
        if (i < 0 || score < 0 || longest < 0 || longest > size) return;
        if (turns[i] < Integer.MAX_VALUE) turns[i]++;
        best[i] = Math.max(best[i], score); line[i] = Math.max(line[i], longest);
        save();
    }

    public int scoreAt(int i) { return best[i]; }
    public static int sizeAt(int i) { return SIZES[i / 15]; }
    public static int colorsAt(int i) { return 3 + (i % 15) / 3; }
    public static int ballsAt(int i) { return 1 + i % 3; }

    public boolean record(int size, int colors, int balls, int score) {
        int i = index(size, colors, balls);
        if (i < 0 || score <= best[i]) return false;
        best[i] = score;
        save();
        return true;
    }

    private void load() {
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore(name, true);
            if (store.getNumRecords() == 0) return;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(store.getRecord(1)));
            int version = in.readInt();
            if (version < MAGIC || version > MAGIC + 3) throw new java.io.IOException("Invalid version");
            int s = in.readUnsignedByte(), c = in.readUnsignedByte(), b = in.readUnsignedByte();
            boolean m = in.readBoolean();
            if (index(s, c, b) < 0) throw new java.io.IOException("Invalid settings");
            int[] scores = new int[best.length];
            for (int i = 0; i < scores.length; i++) {
                scores[i] = in.readInt();
                if (scores[i] < 0) throw new java.io.IOException("Invalid score");
            }
            int mv = 35, ev = 70; boolean fx = true;
            if (version >= MAGIC + 1) {
                mv = in.readUnsignedByte(); ev = in.readUnsignedByte(); fx = in.readBoolean();
                if (mv > 100 || ev > 100) throw new java.io.IOException("Invalid volume");
            }
            boolean marks = version >= MAGIC + 2 ? in.readBoolean() : false;
            int[] played = new int[45], moved = new int[45], longest = new int[45];
            if (version >= MAGIC + 3) for (int i = 0; i < 45; i++) {
                played[i] = in.readInt(); moved[i] = in.readInt(); longest[i] = in.readInt();
                if (played[i] < 0 || moved[i] < 0 || longest[i] < 0 || longest[i] > sizeAt(i))
                    throw new java.io.IOException("Invalid statistics");
            }
            if (in.available() != 0) throw new java.io.IOException("Unexpected settings data");
            System.arraycopy(played, 0, games, 0, 45); System.arraycopy(moved, 0, turns, 0, 45);
            System.arraycopy(longest, 0, line, 0, 45);
            patterns = marks;
            musicVolume = mv; effectsVolume = ev; effects = fx;
            size = s; colors = c; balls = b; music = m;
            System.arraycopy(scores, 0, best, 0, best.length);
        } catch (Exception ignored) {
            storageOk = false; // Keep playing with defaults; the UI reports the failure.
        } finally {
            if (store != null) try { store.closeRecordStore(); } catch (Exception ignored) {}
        }
    }

    public void save() {
        RecordStore store = null;
        try {
            if (index(size, colors, balls) < 0) throw new java.io.IOException("Invalid settings");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC + 3);
            out.writeByte(size); out.writeByte(colors); out.writeByte(balls); out.writeBoolean(music);
            for (int i = 0; i < best.length; i++) out.writeInt(best[i]);
            out.writeByte(AudioVolume.clamp(musicVolume));
            out.writeByte(AudioVolume.clamp(effectsVolume));
            out.writeBoolean(effects);
            out.writeBoolean(patterns);
            for (int i = 0; i < 45; i++) { out.writeInt(games[i]); out.writeInt(turns[i]); out.writeInt(line[i]); }
            out.flush();
            byte[] data = bytes.toByteArray();
            store = RecordStore.openRecordStore(name, true);
            if (store.getNumRecords() == 0) store.addRecord(data, 0, data.length);
            else store.setRecord(1, data, 0, data.length);
            storageOk = true;
        } catch (Exception ignored) {
            storageOk = false;
        } finally {
            if (store != null) try { store.closeRecordStore(); } catch (Exception ignored) {}
        }
    }
}
