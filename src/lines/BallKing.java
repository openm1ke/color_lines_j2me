package lines;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Adaptive reference pixel art and a score-driven pedestal. */
public final class BallKing {
    private Image sprite, column, columnTop;
    private int x, baseY, maxHeight, scale = 1;
    private int height = 2, saluteTicks;

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
            sprite = KingPixels.render(width);
            // Retain both dither phases even when the character is reduced by half.
            column = scaled("/images/king-column.png", Math.max(1, (45 * width + 35) / 70), 2);
            columnTop = scaled("/images/king-column-top.png", column.getWidth());
        }
        height = targetHeight(score);
    }

    private int targetHeight(int score) {
        int minimum = Math.min(maxHeight, 2 * scale);
        long points = Math.max(0, score);
        return minimum + (int) ((maxHeight - minimum) * points / (points + 40));
    }

    public void update(int score) {
        int target = targetHeight(score), difference = target - height;
        if (difference != 0) {
            int step = Math.max(1, Math.abs(difference) / 4);
            height += difference > 0 ? step : -step;
        }
        if (saluteTicks > 0) saluteTicks--;
    }

    public void celebrate(int removed) { if (removed > 0) saluteTicks = 24; }
    public void reset(int score) { height = targetHeight(score); saluteTicks = 0; }

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
        int hop = saluteTicks > 0 && (saluteTicks / 4) % 2 == 0 ? scale : 0;
        g.drawImage(sprite, x, top - sprite.getHeight() - hop, Graphics.TOP | Graphics.LEFT);
    }
}
