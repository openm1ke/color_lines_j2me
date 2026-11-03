package lines;

import javax.microedition.media.Player;
import javax.microedition.media.control.VolumeControl;

final class AudioVolume {
    static int clamp(int value) { return value < 0 ? 0 : value > 100 ? 100 : value; }
    static void apply(Player player, int level) {
        level = clamp(level);
        VolumeControl control = (VolumeControl) player.getControl("VolumeControl");
        if (control != null) {
            control.setLevel(clamp(level));
            control.setMute(level == 0);
        }
    }
}
