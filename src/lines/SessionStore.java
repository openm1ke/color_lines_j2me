package lines;

import java.io.*;
import javax.microedition.rms.*;

/** Separate RMS record so previewing settings cannot overwrite the current game. */
public final class SessionStore {
    private static final int MAGIC = 0x4C534701;
    private final String name;
    public boolean storageOk = true;

    public SessionStore(String name) { this.name = name; }

    private static int checksum(byte[] data, int length) {
        int value = 0x811C9DC5;
        for (int i = 0; i < length; i++) value = (value ^ (data[i] & 255)) * 0x01000193;
        return value;
    }

    public SavedGame load() {
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore(name, false);
            if (store.getNumRecords() == 0) return null;
            byte[] bytes = store.getRecord(1);
            if (bytes.length < 36 || bytes.length > 256) throw new IOException("Invalid saved game size");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = in.readInt();
            if (version != MAGIC && version != MAGIC + 1) throw new IOException("Invalid saved game version");
            SavedGame game = new SavedGame();
            game.size = in.readUnsignedByte(); game.colors = in.readUnsignedByte(); game.balls = in.readUnsignedByte();
            if (GameStore.index(game.size, game.colors, game.balls) < 0) throw new IOException("Invalid rules");
            game.score = in.readInt(); game.seed = in.readInt(); game.moves = in.readInt(); game.longestLine = in.readInt();
            game.curR = in.readUnsignedByte(); game.curC = in.readUnsignedByte(); game.nextCount = in.readUnsignedByte();
            game.cells = new byte[game.size * game.size]; in.readFully(game.cells);
            in.readFully(game.nextR); in.readFully(game.nextC); in.readFully(game.nextCol); in.readFully(game.queued);
            if (version >= MAGIC + 1) {
                game.dailyDay = in.readInt(); game.startingBest = in.readInt();
                game.recordCelebrated = in.readBoolean(); game.threatened = in.readBoolean();
            }
            if (in.available() != 4 || in.readInt() != checksum(bytes, bytes.length - 4) || !game.valid())
                throw new IOException("Invalid saved game data");
            storageOk = true;
            return game;
        } catch (RecordStoreNotFoundException ignored) {
            return null;
        } catch (Exception ignored) {
            storageOk = false;
            return null;
        } finally {
            if (store != null) try { store.closeRecordStore(); } catch (Exception ignored) {}
        }
    }

    public void save(SavedGame game) {
        RecordStore store = null;
        try {
            if (!game.valid()) throw new IOException("Invalid turn");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC + 1); out.writeByte(game.size); out.writeByte(game.colors); out.writeByte(game.balls);
            out.writeInt(game.score); out.writeInt(game.seed); out.writeInt(game.moves); out.writeInt(game.longestLine);
            out.writeByte(game.curR); out.writeByte(game.curC); out.writeByte(game.nextCount);
            out.write(game.cells); out.write(game.nextR); out.write(game.nextC); out.write(game.nextCol); out.write(game.queued);
            out.writeInt(game.dailyDay); out.writeInt(game.startingBest);
            out.writeBoolean(game.recordCelebrated); out.writeBoolean(game.threatened);
            out.flush(); byte[] payload = bytes.toByteArray(); out.writeInt(checksum(payload, payload.length)); out.flush();
            byte[] data = bytes.toByteArray();
            store = RecordStore.openRecordStore(name, true);
            if (store.getNumRecords() == 0) store.addRecord(data, 0, data.length); else store.setRecord(1, data, 0, data.length);
            storageOk = true;
        } catch (Exception ignored) { storageOk = false; }
        finally { if (store != null) try { store.closeRecordStore(); } catch (Exception ignored) {} }
    }

    public void clear() {
        try { RecordStore.deleteRecordStore(name); storageOk = true; }
        catch (RecordStoreNotFoundException ignored) { storageOk = true; }
        catch (Exception ignored) { storageOk = false; }
    }
}
