package lines;

import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;

public class LinesMidlet extends MIDlet implements CommandListener {
    private Display display;
    private LinesCanvas canvas;
    private final Command UNDO = new Command("Undo", Command.BACK, 1);
    private final Command EXIT = new Command("Exit", Command.EXIT, 2);

    protected void startApp() {
        if (display == null) {
            display = Display.getDisplay(this);
            canvas = new LinesCanvas(new LinesCanvas.Host() {
                public void onExitRequested() { exit(); }
            });
            canvas.setDisplay(display);

            canvas.addCommand(UNDO);
            canvas.addCommand(EXIT);
            canvas.setCommandListener(this);
        }
        display.setCurrent(canvas);
        canvas.start();
    }

    protected void pauseApp() { if (canvas != null) canvas.pause(); }
    protected void destroyApp(boolean unconditional) { if (canvas != null) canvas.stop(); }

    private void exit() {
        if (canvas != null) canvas.stop();
        notifyDestroyed();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == EXIT) {
            canvas.requestExit();
        } else if (c == UNDO) {
            canvas.undoOne();
        }
    }
}
