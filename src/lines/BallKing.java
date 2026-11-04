package lines;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Adaptive reference pixel art and a score-driven pedestal. */
public final class BallKing {
    private Image sprite, column, columnTop;
    private int x, baseY, maxHeight, scale = 1;
    private int height = 2, saluteTicks;
    private int record, moodTick, victoryTicks, celebrationLift = 1;
    private boolean worried, defeated;
    private int skin;

    public void setRecord(int value) { record = Math.max(0, value); }
    public void setMood(int empty, int capacity, boolean over) {
        worried = empty <= Math.max(5, capacity / 8); defeated = over;
    }
    public void triumph() { victoryTicks = 60; saluteTicks = 48; celebrationLift = 2; }
    public void setSkin(int value) {
        if (skin == value) return;
        skin = value;
        if (sprite != null) sprite = KingPixels.render(sprite.getWidth(), skin);
    }
    public void drawBadge(Graphics g, int left, int top) {
        g.drawImage(sprite, left, top, Graphics.TOP | Graphics.LEFT);
        g.setColor(0x69CFFF); g.fillArc(left + sprite.getWidth() / 2, top + sprite.getHeight() / 2, 2, 3, 0, 360);
    }

    private static Image scaled(String resource, int width) {
        return scaled(resource, width, 1);
    }

    private static Image scaled(String resource, int width, int minimumHeight) {
        try {
            Image source = Image.createImage(resource);
            int sw = source.getWidth(), sh = source.getHeight();
            int h = Math.max(minimumHeight, (sh * width + sw / 2) / sw);
            int[] pixels = new int[sw * sh], result = new int[width * h];
            source.getRGB(pixels, 0, sw, 0, 0, sw, sh);
            for (int y = 0; y < h; y++) for (int col = 0; col < width; col++)
                result[y * width + col] = pixels[(y * sh / h) * sw + col * sw / width];
            return Image.createRGBImage(result, width, h, true);
        } catch (java.io.IOException e) { throw new RuntimeException("Missing king image: " + resource); }
    }

    public void layout(int x, int baseY, int maxHeight, int scale, int score) {
        layout(x, baseY, maxHeight, scale, score, 28 * scale);
    }

    public void layout(int x, int baseY, int maxHeight, int scale, int score, int width) {
        this.x = x; this.baseY = baseY; this.maxHeight = Math.max(2, maxHeight);
        if (sprite == null || this.scale != scale || sprite.getWidth() != width) {
            this.scale = scale;
            sprite = KingPixels.render(width, skin);
            // Retain both dither phases even when the character is reduced by half.
            column = scaled("/images/king-column.png", Math.max(1, (45 * width + 35) / 70), 2);
            columnTop = scaled("/images/king-column-top.png", column.getWidth());
        }
        height = targetHeight(score);
    }

    private int targetHeight(int score) {
        int minimum = Math.min(maxHeight, 2 * scale);
        long points = Math.max(0, score);
        // Half of the available column at 500 points; a first line adds only
        // about 1% of its height. Round upward so small screens show progress.
        long denominator = points + 500;
        return minimum + (int) (((maxHeight - minimum) * points + denominator - 1) / denominator);
    }

    public void update(int score) {
        int target = targetHeight(score), difference = target - height;
        if (difference != 0) {
            int step = Math.max(1, Math.abs(difference) / 4);
            height += difference > 0 ? step : -step;
        }
        if (saluteTicks > 0) saluteTicks--;
        if (victoryTicks > 0) victoryTicks--;
        moodTick = (moodTick + 1) % 40;
    }

    public void celebrate(int removed) { if (removed > 0) { saluteTicks = removed >= 7 ? 40 : 24; celebrationLift = removed >= 7 ? 2 : 1; } }
    public void reset(int score) { height = targetHeight(score); saluteTicks = victoryTicks = moodTick = 0; worried = defeated = false; }

    public void draw(Graphics g) {
        int top = baseY - height;
        int cx = x + (6 * sprite.getWidth() + 35) / 70;
        int clipX = g.getClipX(), clipY = g.getClipY();
        int clipW = g.getClipWidth(), clipH = g.getClipHeight();
        g.clipRect(cx, top, column.getWidth(), height);
        // Repeat the original dithered silver pixels, without stretching the pattern.
        for (int y = top; y < baseY; y += column.getHeight())
            g.drawImage(column, cx, y, Graphics.TOP | Graphics.LEFT);
        g.drawImage(columnTop, cx, top, Graphics.TOP | Graphics.LEFT);
        g.setClip(clipX, clipY, clipW, clipH);
        if (record > 0) {
            int markY = baseY - targetHeight(record);
            g.setColor(0xFFD24A); g.drawLine(cx - 3, markY, cx + column.getWidth() + 2, markY);
            int labelY = markY - g.getFont().getHeight() - 1;
            if (labelY + g.getFont().getHeight() < top - sprite.getHeight() || labelY > top)
                g.drawString("PB", cx, labelY, Graphics.TOP | Graphics.LEFT);
        }
        int hop = saluteTicks > 0 && (saluteTicks / 4) % 2 == 0 ? scale * celebrationLift : 0;
        int sway = worried && saluteTicks == 0 && !defeated ? (moodTick < 20 ? 0 : 1) : 0;
        int sy = top - sprite.getHeight() - hop;
        g.drawImage(sprite, x + sway, sy, Graphics.TOP | Graphics.LEFT);
        if ((worried || defeated) && saluteTicks == 0) {
            g.setColor(0x69CFFF);
            int tearX = x + sprite.getWidth() / 2, tearY = sy + sprite.getHeight() / 2;
            g.fillArc(tearX, tearY + (defeated ? 2 : moodTick / 12), 2 * scale, 3 * scale, 0, 360);
        }
        if (saluteTicks > 0 || victoryTicks > 0) {
            int starX = x + sprite.getWidth() / 2, starY = Math.max(3, sy - 3);
            g.setColor(victoryTicks > 0 ? 0xFFFFFF : 0xFFD24A);
            g.drawLine(starX - 2, starY, starX + 2, starY);
            g.drawLine(starX, starY - 2, starX, starY + 2);
        }
    }
}
