package lines;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.microedition.media.MediaException;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;

/** One bounded pending effect, three tiny reusable players, no game-thread media I/O. */
public final class SoundEffects implements Runnable {
    private boolean enabled = true, suspended, closed, pending;
    private int volume = 70, variant;
    private Thread worker;
    private volatile boolean failed;

    public synchronized void configure(boolean value, int level) {
        enabled = value;
        volume = AudioVolume.clamp(level);
        if (!enabled || volume == 0) pending = false;
        notifyAll();
    }
    public synchronized void setSuspended(boolean value) {
        suspended = value;
        if (value) pending = false;
        notifyAll();
    }
    public synchronized void pop() {
        if (closed || suspended || !enabled || volume == 0 || failed) return;
        pending = true;
        if (worker == null) { worker = new Thread(this); worker.start(); }
        notifyAll();
    }
    public boolean failed() { return failed; }
    public synchronized void close() { closed = true; pending = false; notifyAll(); }

    private InputStream openPop(int number) throws IOException {
        InputStream resource = getClass().getResourceAsStream("/sfx/pop" + number + ".wav");
        if (resource == null) throw new IOException("Pop resource missing");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = resource.read(buffer)) != -1) bytes.write(buffer, 0, read);
            // FreeJ2ME's WAV reader uses mark/reset and available() on the stream.
            return new ByteArrayInputStream(bytes.toByteArray());
        } finally { resource.close(); }
    }

    public void run() {
        Player[] players = new Player[3];
        InputStream[] streams = new InputStream[3];
        try {
            while (true) {
                synchronized (this) {
                    for (int i = 0; i < players.length; i++) if (players[i] != null) {
                        if (closed || suspended || !enabled || volume == 0) {
                            try { players[i].stop(); } catch (Exception ignored) {}
                        } else try { AudioVolume.apply(players[i], volume); } catch (Exception ignored) { failed = true; }
                    }
                    if (closed) break;
                    if (!pending || suspended || !enabled || volume == 0 || failed) {
                        try { wait(); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    pending = false;
                }
                int i = variant;
                variant = (variant + 1) % players.length;
                try {
                    if (players[i] == null) {
                        streams[i] = openPop(i + 1);
                        players[i] = Manager.createPlayer(streams[i], "audio/x-wav");
                        players[i].realize(); players[i].prefetch();
                        // Some emulators log decode failures without throwing MediaException.
                        if (players[i].getState() != Player.PREFETCHED)
                            throw new MediaException("Cannot decode pop sound");
                        players[i].setLoopCount(1);
                    }
                    synchronized (this) {
                        if (!closed && !suspended && enabled && volume > 0) {
                            // A single short pop per removed line; do not stack loud bursts.
                            for (int j = 0; j < players.length; j++) if (players[j] != null)
                                try { players[j].stop(); } catch (Exception ignored) {}
                            AudioVolume.apply(players[i], volume);
                            players[i].setMediaTime(0);
                            players[i].start();
                        }
                    }
                } catch (Exception ignored) { failed = true; }
                  catch (NoClassDefFoundError ignored) { failed = true; }
            }
        } finally {
            for (int i = 0; i < players.length; i++) {
                if (players[i] != null) players[i].close();
                if (streams[i] != null) try { streams[i].close(); } catch (Exception ignored) {}
            }
        }
    }
}
