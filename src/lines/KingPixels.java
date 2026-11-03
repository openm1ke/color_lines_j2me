package lines;

import javax.microedition.lcdui.Image;

/** Exact 70x66 reference map plus small-size facial pixel hints.
 * k=black, r=red, d=dark red, w=white, s=dark silver, g=silver,
 * y=yellow, o=gold, .=transparent. Source screenshot had uniform 3x3 blocks.
 */
final class KingPixels {
    private static final String INK = "krdwsgyo";
    private static final int[] COLORS = {0x000000,0xFF0000,0xAA0000,0xFFFFFF,0x555555,0xAAAAAA,0xFFFF00,0xFFAA00};
    private static final String[] ROWS = {
        "............................................................y.........",
        "...........................................................yyo........",
        "..........................................................yyyyo.......",
        "...........................................................yyo........",
        "............................................................y.........",
        ".....................................................oo.....y.....oo..",
        "....................................................yyoo....y....yyoo.",
        "............................r......................yyyyyyyyyyyyyyyyyoo",
        "..........................rrrrr.....................yyoo....y....yyoo.",
        "...........................rrr.......................oo.....y.....oo..",
        "............................r...............................y.........",
        ".....................yoo....r....yoy........................y.........",
        "...................yyoyooo.oko.yyyyoyy....................yoyoo.......",
        "..................yyoyoyyoo.o.yyyoyyoyo.................yyyyyoooo.....",
        "..................yoyyyoyyo.o.yyoyyyyoo................yywyyoooooo....",
        "..................yyykkyoyo.o.yoyykkyoo................ywwyyoooooo....",
        "...................ykwrkyyo.o.yyykwdko.................yyyyyoooooo....",
        "...................ykrrkyyo.o.yyykddko..................yyyyooooo.....",
        "...................yykkyyyo.o.yyyykkoo....................yyyoo.......",
        "....................yyyyyyo.o.yyyoooo......................ooo........",
        ".....................yyyyyo.o.yyoooo.......................yoo........",
        "......................yyyyy.o.ooooo........................yoo........",
        "...........................................................yoo........",
        "......................oooooooooyyyy........................yoo........",
        "......................yryyryyyryyry........................yoo........",
        "......................yoooooooooooy........................yoo........",
        "...........................................................yoo........",
        "......................rrrrrrrrrrrrr........................yoo........",
        "...................rrrrkkkkkrkkkkkrrrrr....................yoo........",
        ".................rrrrrkwwwwwkwwwwwkrrrrr...................yoo........",
        "...............rrrrrrkwwwkkkwkkkwwwkrrrrrr.................yoo........",
        ".............rrrrrrrrkkwkkkkrkkkkwkkrrrrrrrr...............yoo........",
        "............rrrrrrrkrrkkwkkkrkkkwkkrrkrrrrrrr..............yoo........",
        "...........rrrrrrrrrkkrkkkrrrrrkkkrkkrrrrrrrrr.............yoo........",
        "..........rrrrrrrrrrrrkrkrrrrrrrkrkrrrrrrrrrrrr............yyo........",
        ".........rrrrrrrrrrrrrkkrrwwwrrrrkkrrrrrrrrrrrrr......................",
        ".........rrrrrrrrrrrrkrrrrwrrrrrrrrkrrrrrrrrrrrr..........rrr.rr......",
        ".........rrrrrrrrrrrrkrrrrrrrrrrrrrkrrrrrrrrrrrr.d.....rrrrrr..r......",
        ".......d.rrrrrrrrrrrrkrrrrrrrrrrrrrkrrrrrrrrrrr..d.....dddrrrd.r......",
        "......drdkkrrrrrrrrrrrkkkrrrrrrrkkkrrrrrrrrrrrr.dddd...dkkkd...r......",
        ".....drrrddkkrrrrrrrrrrrrkkkkkkkrrrrrrrrrrrrrr..ddddd..dkrrrrrrr......",
        ".....drrrrrrrkkrrrrrrkkrrrrrrrrrrrkkrrrrkrrrr..dddddddddkddddrrr......",
        "....drrrrrrrrrrkkrrrrrrkkkkkkkkkkkrrrrkkykkrkdddddddddddkrrrrrrr......",
        "....drrrrrrrrrrddkkkrrrrrrrrrrrrrrrrkkyydyykkddd..ddddddkddddrrr......",
        "...drrrrddddddddddddkkkkkkkkrrrrrrrkyyyrrdyyy......dddddkrrrrrrr......",
        "...drrrddrrrrrrrrrrdddddddddkkkkkkkyrrrrrddddy.rr...ddddd.............",
        "..drrrdrrrrrrrdrrrrrrrrrrrrdddddddkyrrwrrddddy.r....ddddd..yoo........",
        "..drrrrrrrrddddrrrrrrrrrdddddddddddkyrrrrdddykrr.....ddd...yoo........",
        "..drrrrrrdddrrrrrrrdddddrrdddddddddkyrrrddddykrkd....dd....yoo........",
        ".drrrrrddrrrrrrrrrrddddddddkkkkkkkkkkyydddyykrkd...........yoo........",
        ".drrrddrrrrrrrddddddkkkkkkkrrrrrrrrrrkkydykkrkdkd..........yoo........",
        ".drrddrrrrrrddrkkkkkdddddddrrrrrrrrrrrrkykrrkdkd...........yoo........",
        ".drrdrrrrrr....ddddddrrrrrrrrrrrrrrrrrrrkrkkdkd............yoo........",
        ".drrdrrrrr..d.dkkrrrrrrrrrrrrrrrrrrrrrrrkkkdkdkd...........yoo........",
        "drrrrrr....dkdkdkkkrrrrrrrrrrrrrrrrrrrkkdkdkdkd............yoo........",
        "drrrr...d.dkdkdkdkdkkkrrrrrrrrrrrrrkkkkdkdkdkdkd...........yoo........",
        "drrr...dkdkdkdkdkdkdkkkrrkkkkkkkrrkkdkdkdkdkdkd............yoo........",
        "drrr..dkdkdkkkdkdkdkkrrrrkkdkdkkrrrrkdkdkdkdkdkd...........yoo........",
        "drr..dkdkdkkrkkdkdkdkkkrrrrkdkrrrrkkdkdkdkdkrkd............yoo........",
        "drr.dkdkdkkrrrkkdkdkdkrrrkkdkdkkrrrkkdkdkkkrrr.............yoo........",
        "ddr..dkdkrrrrrrrkkkkkrrrrkdkdkdkrrdrkkkkkrrrrrrr...........yoo........",
        "dddd..d.rrwwwwrrrkrrrrrddrkdkdkrddddrrrkrrrwwwwrr..........yoo........",
        "d.......rrwwrrrrrrkddddddddkkkddddddddkrrrrrrwwrr..........yoo........",
        "..d.d.d.rrrrrrrrrrdddddddddsssdddddddddrrrrrrrrrr..........yoo........",
        ".d.d.dksrrrrrrrrrrdddddddddsssdddddddddrrrrrrrrrrs.........yoo........",
        "......s..dddddddddddddddddsssssdddddddddddddddddsss........yoo........"
    };

    static Image render(int width) {
        int height = Math.max(1, (66 * width + 35) / 70);
        int[] pixels = new int[width * height];
        // Palette-preserving area voting avoids phase-dependent missing features.
        int[] votes = new int[9];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            for (int i = 0; i < votes.length; i++) votes[i] = 0;
            int left = x * 70, right = (x + 1) * 70;
            int top = y * 66, bottom = (y + 1) * 66;
            for (int sy = top / height; sy <= (bottom - 1) / height; sy++)
                for (int sx = left / width; sx <= (right - 1) / width; sx++) {
                    int area = (Math.min(right, (sx + 1) * width) - Math.max(left, sx * width))
                             * (Math.min(bottom, (sy + 1) * height) - Math.max(top, sy * height));
                    int ink = INK.indexOf(ROWS[sy].charAt(sx)) + 1;
                    votes[ink] += area;
                }
            int winner = 0;
            for (int i = 1; i < votes.length; i++) if (votes[i] > votes[winner]) winner = i;
            pixels[y * width + x] = winner == 0 ? 0 : 0xFF000000 | COLORS[winner - 1];
        }
        if (width < 70) {
            face(pixels, width, height);
            if (width >= 28) regalia(pixels, width, height);
        }
        return Image.createRGBImage(pixels, width, height, true);
    }

    private static void rect(int[] p, int w, int h, int x, int y, int rw, int rh, int color) {
        for (int yy = Math.max(0,y); yy < Math.min(h,y+rh); yy++)
            for (int xx = Math.max(0,x); xx < Math.min(w,x+rw); xx++) p[yy*w+xx] = 0xFF000000 | color;
    }

    private static void face(int[] p, int w, int h) {
        // Snap both eyes to a shared grid. Give each pupil a whole pixel instead
        // of letting fractional sampling decide which eye survives.
        int eyeWhite = w >= 42 ? 3 : 2;
        int eyeHeight = w >= 42 ? 3 : 2;
        int gap = 1;
        int pairWidth = 2 * eyeWhite + gap;
        int cx = (28 * w + 35) / 70;
        int left = cx - pairWidth / 2;
        int top = (29 * h + 33) / 66;
        rect(p,w,h,left-1,top-1,pairWidth+2,eyeHeight+3,0xFF0000);
        rect(p,w,h,left-1,top-1,eyeWhite+2,eyeHeight+2,0x000000);
        rect(p,w,h,left+eyeWhite+gap-1,top-1,eyeWhite+2,eyeHeight+2,0x000000);
        rect(p,w,h,left,top,eyeWhite,eyeHeight,0xFFFFFF);
        rect(p,w,h,left+eyeWhite+gap,top,eyeWhite,eyeHeight,0xFFFFFF);
        rect(p,w,h,left+eyeWhite-1,top+eyeHeight-1,1,1,0x000000);
        rect(p,w,h,left+eyeWhite+gap,top+eyeHeight-1,1,1,0x000000);
        // Red nose keeps the inner pupils separate; one white tooth is readable.
        rect(p,w,h,cx,top+eyeHeight,1,1,0xFF0000);
        rect(p,w,h,cx-1,(35*h+33)/66,Math.max(1,w/35),1,0xFFFFFF);
    }

    private static void regalia(int[] p, int w, int h) {
        // One-source-pixel strokes can lose every area vote below 1:1.
        // Reconnect the sceptre cross and retain the crown cross and red jewels.
        int sx = (60*w+35)/70, bar = (7*h+33)/66;
        rect(p,w,h,sx,0,1,Math.max(1,(13*h+33)/66),0xFFFF00);
        int armLeft = (52*w+35)/70;
        rect(p,w,h,armLeft,bar,w-armLeft,1,0xFFFF00);
        int cx = (28*w+35)/70, cy = (7*h+33)/66;
        rect(p,w,h,cx,cy,1,3,0xFF0000);
        rect(p,w,h,cx-1,cy+1,3,1,0xFF0000);
        int gemY = (16*h+33)/66;
        for (int i=0;i<2;i++) {
            int gemX = ((i==0 ? 21 : 35)*w+35)/70;
            if (w>=35) rect(p,w,h,gemX-1,gemY-1,2,2,0x000000);
            rect(p,w,h,gemX,gemY,1,1,0xFF0000);
        }
    }
}
