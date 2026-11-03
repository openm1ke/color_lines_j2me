package lines;

import java.io.InputStream;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;

/** Media I/O stays off the game and MIDP event threads. Unsupported audio is optional. */
public final class Soundtrack implements Runnable {
    private boolean active, closed;
    private Thread worker;
    private int volume = 35;
    private volatile boolean failed;

    public synchronized void setActive(boolean value) {
        if (closed) return;
        if (value && !active) failed = false;
        active = value;
        if (worker == null && value) {
            worker = new Thread(this);
            worker.start();
        }
        notifyAll();
    }

    public synchronized void setVolume(int value) {
        volume = AudioVolume.clamp(value);
        notifyAll();
    }

    public boolean failed() { return failed; }

    public synchronized void close() {
        closed = true;
        active = false;
        notifyAll();
    }

    public void run() {
        Player player = null;
        InputStream stream = null;
        try {
            while (true) {
                boolean play;
                synchronized (this) {
                    while (!closed && (!active || volume == 0 || failed)) {
                        if (player != null && player.getState() == Player.STARTED) {
                            try { player.stop(); } catch (Exception ignored) {}
                        }
                        try { wait(); } catch (InterruptedException ignored) {}
                    }
                    if (closed) break;
                    play = active;
                }
                try {
                    if (play && player == null) {
                        stream = getClass().getResourceAsStream("/music/minigames.mid");
                        if (stream == null) throw new java.io.IOException("Music resource missing");
                        player = Manager.createPlayer(stream, "audio/midi");
                        player.realize();
                        player.prefetch();
                        player.setLoopCount(-1);
                    }
                    synchronized (this) {
                        if (!closed && active && volume > 0) AudioVolume.apply(player, volume);
                        if (!closed && active && volume > 0 && player.getState() != Player.STARTED) player.start();
                        if (!closed && active && volume > 0) try { wait(); } catch (InterruptedException ignored) {}
                    }
                } catch (Exception ignored) {
                    failed = true;
                    if (player != null) { player.close(); player = null; }
                    if (stream != null) { try { stream.close(); } catch (Exception ignored2) {} stream = null; }
                } catch (NoClassDefFoundError ignored) {
                    failed = true;
                }
            }
        } finally {
            if (player != null) player.close();
            if (stream != null) try { stream.close(); } catch (Exception ignored) {}
        }
    }
}
