import java.util.Arrays;
import lines.*;
import org.recompile.mobile.*;

/** Integration checks for saved games, hints and the expanded UI. */
public class FeatureCheck extends GameplayCheck {
    static int[] flat(int[][] b) {
        int n=b.length;int[] result=new int[n*n];
        for(int r=0;r<n;r++)for(int c=0;c<n;c++)result[r*n+c]=b[r][c];return result;
    }
    static void settle(LinesCanvas c) throws Exception {
        for(int i=0;i<200&&((Boolean)get(c,"moving")||(Boolean)get(c,"clearing"));i++)call(c,"updateAnimation");
        check(!(Boolean)get(c,"moving")&&!(Boolean)get(c,"clearing"),"animation failed to settle");
    }
    static boolean makeMove(LinesCanvas c) throws Exception {
        int[][] b=(int[][])get(c,"board");int n=b.length;
        for(int r=0;r<n;r++)for(int col=0;col<n;col++)if(b[r][col]!=0){
            int[][] ds={{-1,0},{1,0},{0,-1},{0,1}};
            for(int[] d:ds){int rr=r+d[0],cc=col+d[1];if(rr>=0&&rr<n&&cc>=0&&cc<n&&b[rr][cc]==0){select(c,r,col);select(c,rr,cc);return true;}}
        }
        return false;
    }
    static void savedGameRoundTrip() throws Exception {
        LinesCanvas c=canvas();GameStore prefs=(GameStore)get(c,"preferences");
        prefs.size=11;prefs.colors=4;prefs.balls=2;call(c,"startNewGame");
        check(makeMove(c),"cannot make saved move");settle(c);
        int[] expected=flat((int[][])get(c,"board"));int seed=(Integer)get(c,"seed"),moves=(Integer)get(c,"moves");
        LinesCanvas restored=new LinesCanvas(null);
        check((Boolean)get(restored,"hasGame")&&(Integer)get(restored,"state")==0,"resume not offered on startup");
        check((Integer)get(restored,"N")==11&&(Integer)get(restored,"colorCount")==4&&(Integer)get(restored,"ballsPerTurn")==2,"saved rules lost");
        check(Arrays.equals(expected,flat((int[][])get(restored,"board")))&&(Integer)get(restored,"seed")==seed&&(Integer)get(restored,"moves")==moves,"saved turn changed");
        check(Arrays.equals((int[])get(c,"nextCol"),(int[])get(restored,"nextCol"))&&Arrays.equals((int[])get(c,"queuedColors"),(int[])get(restored,"queuedColors")),"saved previews lost");
        // A pause halfway through the next animation restores the last completed turn.
        check(makeMove(c),"cannot make interrupted move");c.pause();
        LinesCanvas paused=new LinesCanvas(null);
        check(Arrays.equals(expected,flat((int[][])get(paused,"board"))),"pause saved half a moving ball");
        check((Integer)get(paused,"seed")==seed,"interrupted turn changed saved RNG");
        SessionStore store=(SessionStore)get(c,"sessionStore");
        javax.microedition.rms.RecordStore rms=javax.microedition.rms.RecordStore.openRecordStore(prefs.sessionName(),false);
        byte[] broken=rms.getRecord(1);broken[broken.length-1]^=1;rms.setRecord(1,broken,0,broken.length);rms.closeRecordStore();
        check(store.load()==null&&!store.storageOk,"corrupt save accepted");
        store.clear();check(store.load()==null&&store.storageOk,"save slot did not clear");
        prefs.size=9;prefs.colors=5;prefs.balls=3;prefs.save();
        System.out.println("PASS: RMS resume preserves rules/board/RNG/previews/moves; pause is stable and corrupt saves are rejected");
    }
    static void hintsAndInvalidMoves() throws Exception {
        LinesCanvas c=canvas();call(c,"startNewGame");int[][] b=(int[][])get(c,"board");
        for(int[] row:b)Arrays.fill(row,0);b[4][4]=1;b[3][4]=2;b[5][4]=2;b[4][3]=2;b[4][5]=2;
        int seed=(Integer)get(c,"seed");int[] original=flat(b);select(c,4,4);set(c,"curR",0);set(c,"curC",0);call(c,"refreshPathHint");
        boolean[][] reachable=(boolean[][])get(c,"reachable");
        check(!reachable[0][0]&&(Integer)get(c,"hintLen")==0,"unreachable destination is highlighted");
        select(c,0,0);check((Integer)get(c,"invalidTicks")>0&&!(Boolean)get(c,"moving")&&(Integer)get(c,"selR")==4,"invalid move must shake and preserve selection");
        check(Arrays.equals(original,flat(b))&&(Integer)get(c,"seed")==seed,"invalid move changed game state");
        b[4][5]=0;call(c,"refreshReachable");call(c,"refreshPathHint");
        int length=(Integer)get(c,"hintLen");int[] rr=(int[])get(c,"hintR"),cc=(int[])get(c,"hintC");
        check(length>0&&rr[length-1]==0&&cc[length-1]==0,"reachable route not previewed");
        int r=4,col=4;for(int n=0;n<length;n++){check(Math.abs(rr[n]-r)+Math.abs(cc[n]-col)==1&&b[rr[n]][cc[n]]==0,"hint crosses occupied cell or jumps");r=rr[n];col=cc[n];}
        select(c,3,4);check((Integer)get(c,"selR")==3&&(Integer)get(c,"selC")==4,"switching selected ball failed");
        check((Integer)get(c,"seed")==seed,"hints consumed random sequence");c.stop();
        System.out.println("PASS: reachable cells and contiguous route hints, blocked-move feedback and switching selection");
    }
    static void staggeredClearAndScore() throws Exception {
        LinesCanvas c=canvas();call(c,"startNewGame");int[][] b=(int[][])get(c,"board");for(int[] row:b)Arrays.fill(row,0);
        for(int col=0;col<5;col++)b[4][col]=1;
        check((Boolean)call(c,"startClearIfAny"),"line not detected");
        int[][] order=(int[][])get(c,"clearOrder");for(int col=0;col<5;col++)check(order[4][col]==col,"clear animation order wrong");
        for(int n=0;n<6;n++)call(c,"updateAnimation");
        check((Boolean)get(c,"clearing")&&(Integer)get(c,"score")==0,"line did not stagger its removal");
        settle(c);check((Integer)get(c,"score")==5&&(Integer)get(c,"popupScore")==5&&(Integer)get(c,"popupTicks")>0,"clear score popup missing");
        check((Integer)get(c,"displayScore")<5,"score did not animate");
        for(int n=0;n<40;n++)call(c,"updateAnimation");
        check((Integer)get(c,"displayScore")==5&&(Integer)get(c,"popupTicks")==0,"score or popup failed to finish");c.stop();
        System.out.println("PASS: staggered removal, floating points, smooth score and exact logical scoring");
    }
    static void accessiblePatterns() throws Exception {
        LinesCanvas c=canvas();call(c,"startNewGame");GameStore prefs=(GameStore)get(c,"preferences");
        int seed=(Integer)get(c,"seed");int[] before=flat((int[][])get(c,"board"));
        java.lang.reflect.Field bf=javax.microedition.lcdui.game.GameCanvas.class.getDeclaredField("buffer");bf.setAccessible(true);
        org.recompile.mobile.PlatformImage image=(org.recompile.mobile.PlatformImage)bf.get(c);
        prefs.patterns=false;call(c,"render");int[] plain=image.getCanvas().getRGB(0,0,240,320,null,0,240);
        prefs.patterns=true;call(c,"render");int[] marked=image.getCanvas().getRGB(0,0,240,320,null,0,240);
        check(!Arrays.equals(plain,marked),"pattern mode did not change rendering");
        check(Arrays.equals(before,flat((int[][])get(c,"board")))&&(Integer)get(c,"seed")==seed,"patterns changed gameplay");
        prefs.save();check(new GameStore().patterns,"pattern preference did not persist");prefs.patterns=false;prefs.save();c.stop();
        System.out.println("PASS: accessible patterns change graphics only and persist without changing rules or RNG");
    }
    static void difficultyPresets() throws Exception {
        LinesCanvas c=canvas();call(c,"startNewGame");GameStore prefs=(GameStore)get(c,"preferences");
        java.lang.reflect.Method preset=LinesCanvas.class.getDeclaredMethod("applyPreset",int.class);preset.setAccessible(true);
        int[] colors={4,5,7},balls={1,2,3};int activeColors=(Integer)get(c,"colorCount");
        for(int i=0;i<3;i++){
            preset.invoke(c,i);check(prefs.size==9&&prefs.colors==colors[i]&&prefs.balls==balls[i],"wrong preset rules");
            check((Integer)get(c,"colorCount")==activeColors,"preset changed an active game");
            check((Integer)call(c,"presetIndex")==i,"preset label does not match rules");
        }
        prefs.size=7;check((Integer)call(c,"presetIndex")==-1,"manual rules not marked Custom");
        prefs.size=9;prefs.colors=5;prefs.balls=3;prefs.save();c.stop();
        System.out.println("PASS: Relaxed/Normal/Classic presets, Custom detection and active-game isolation");
    }
    static void helpAndNewGameConfirmation() throws Exception {
        LinesCanvas c=canvas();call(c,"startNewGame");int[] original=flat((int[][])get(c,"board"));int seed=(Integer)get(c,"seed");
        set(c,"state",0);set(c,"menuSel",1);call(c,"titleSelect");
        check((Integer)get(c,"state")==7,"New Game did not ask for confirmation");
        key(c,-6);call(c,"tick");check((Integer)get(c,"state")==0&&Arrays.equals(original,flat((int[][])get(c,"board")))&&(Integer)get(c,"seed")==seed,"Cancel changed current game");
        set(c,"menuSel",1);call(c,"titleSelect");key(c,-7);call(c,"tick");
        check((Integer)get(c,"state")==1&&(Integer)get(c,"moves")==0,"confirmation failed to start a new game");
        set(c,"state",0);set(c,"menuSel",4);call(c,"titleSelect");check((Integer)get(c,"state")==6,"Help menu missing");
        String[] lines=(String[])get(c,"helpLines");check(lines.length>10,"help content empty");
        for(String line:lines)check(javax.microedition.lcdui.Font.getDefaultFont().stringWidth(line)<=c.getWidth()-20,"help line clips");
        key(c,-7);call(c,"tick");check((Integer)get(c,"state")==0,"Help Back failed");c.stop();
        System.out.println("PASS: safe new-game confirmation/cancel and readable Help with Back navigation");
    }
    static void gameStatistics() throws Exception {
        LinesCanvas c=canvas();GameStore prefs=(GameStore)get(c,"preferences");prefs.size=9;prefs.colors=5;prefs.balls=3;
        int mode=GameStore.index(9,5,3),games=prefs.gamesAt(mode),turns=prefs.movesAt(mode);
        call(c,"startNewGame");check(prefs.gamesAt(mode)==games+1,"new game not counted");
        check(makeMove(c),"cannot play statistics move");settle(c);check(prefs.movesAt(mode)==turns+1,"turn not counted exactly once");
        call(c,"undoNow");check(prefs.movesAt(mode)==turns+1,"Undo erased lifetime moves");
        check(makeMove(c),"cannot replay turn");settle(c);check(prefs.movesAt(mode)==turns+2,"replayed move not counted");
        LinesCanvas restored=new LinesCanvas(null);GameStore loaded=(GameStore)get(restored,"preferences");
        check(loaded.gamesAt(mode)==games+1&&loaded.movesAt(mode)==turns+2,"resume counted another game or lost moves");
        int[][] b=(int[][])get(c,"board");call(c,"clearBoard");for(int n=2;n<=6;n++){b[4][n]=1;b[n][4]=1;}
        check((Boolean)call(c,"startClearIfAny"),"cross did not clear");settle(c);
        check((Integer)get(c,"score")==9&&prefs.lineAt(mode)==5,"longest line confused with total removed balls");
        GameStore finalStore=new GameStore();check(finalStore.gamesAt(mode)==games+1&&finalStore.movesAt(mode)==turns+3&&finalStore.lineAt(mode)==5,"statistics did not persist");
        c.stop();System.out.println("PASS: per-mode games, lifetime moves, resume/Undo accounting and longest contiguous line");
    }
    static void visibleMenuAndHelpText() throws Exception {
        LinesCanvas c=canvas();set(c,"state",0);call(c,"render");
        java.lang.reflect.Field f=javax.microedition.lcdui.game.GameCanvas.class.getDeclaredField("buffer");f.setAccessible(true);
        org.recompile.mobile.PlatformImage pixels=(org.recompile.mobile.PlatformImage)f.get(c);
        int menuY=(Integer)call(c,"menuY");int menuPixels=0;
        for(int y=menuY;y<menuY+18;y++)for(int x=0;x<240;x++)if((pixels.getCanvas().getRGB(x,y)&0xFFFFFF)>0x777777)menuPixels++;
        check(menuPixels>20,"main menu text is invisible");
        set(c,"menuSel",3);call(c,"titleSelect");call(c,"render");int helpPixels=0;
        for(int y=46;y<100;y++)for(int x=10;x<230;x++)if((pixels.getCanvas().getRGB(x,y)&0xFFFFFF)>0x777777)helpPixels++;
        check(helpPixels>50,"help body is invisible");
        c.stop();System.out.println("PASS: main menu and help text remain visible after layout calculations");
    }
    static void savedClearAndGameOver() throws Exception {
        LinesCanvas c=canvas();call(c,"startNewGame");int[][] b=(int[][])get(c,"board");for(int[] row:b)Arrays.fill(row,0);
        for(int col=0;col<4;col++)b[4][col]=1;b[5][4]=1;call(c,"pushSnapshot");int[] stableBoard=flat(b);
        select(c,5,4);select(c,4,4);for(int n=0;n<10&&!(Boolean)get(c,"clearing");n++)call(c,"updateAnimation");
        check((Boolean)get(c,"clearing"),"clear animation not reached");c.pause();LinesCanvas interrupted=new LinesCanvas(null);
        check(Arrays.equals(stableBoard,flat((int[][])get(interrupted,"board")))&&(Integer)get(interrupted,"score")==0,"interrupted clear saved a partial line");
        set(c,"paused",false);settle(c);LinesCanvas done=new LinesCanvas(null);
        check((Integer)get(done,"score")==5&&(Integer)get(done,"longestLine")==5&&Arrays.equals(flat(b),flat((int[][])get(done,"board"))),"completed clear not saved");
        check((Integer)get(get(done,"king"),"height")>6,"saved score did not restore pedestal height");
        c.stop();c=canvas();call(c,"startNewGame");b=(int[][])get(c,"board");for(int[] row:b)Arrays.fill(row,1);
        java.lang.reflect.Method end=LinesCanvas.class.getDeclaredMethod("completeTurn",int.class);end.setAccessible(true);end.invoke(c,0);
        check((Integer)get(c,"state")==3&&!((Boolean)get(c,"hasGame")),"full board did not end game");
        check(((SessionStore)get(c,"sessionStore")).load()==null&&!((Boolean)get(new LinesCanvas(null),"hasGame")),"game over kept a resumable slot");
        call(c,"undoNow");check((Boolean)get(c,"hasGame")&&((SessionStore)get(c,"sessionStore")).load()!=null,"Undo after game over did not restore resume");c.stop();
        System.out.println("PASS: interrupted/completed clear checkpoints, game-over save cleanup and Undo restores resumability");
    }
    static void migrateRecentSettings() throws Exception {
        for(int version=2;version<=3;version++){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
            out.writeInt(0x4C4E5300+version);out.writeByte(7);out.writeByte(3);out.writeByte(2);out.writeBoolean(true);
            for(int n=0;n<45;n++)out.writeInt(n==GameStore.index(7,3,2)?123:0);
            out.writeByte(25);out.writeByte(65);out.writeBoolean(true);if(version==3)out.writeBoolean(true);out.flush();
            javax.microedition.rms.RecordStore rms=javax.microedition.rms.RecordStore.openRecordStore(System.getProperty("lines.store"),true);
            byte[] data=bytes.toByteArray();if(rms.getNumRecords()==0)rms.addRecord(data,0,data.length);else rms.setRecord(1,data,0,data.length);rms.closeRecordStore();
            GameStore prefs=new GameStore();check(prefs.storageOk&&prefs.best(7,3,2)==123&&prefs.musicVolume==25&&prefs.effectsVolume==65,"migration lost recent preferences or records");
            check(prefs.patterns==(version==3)&&prefs.gamesAt(GameStore.index(7,3,2))==0,"migration invented old game counters");
            prefs.save();check(new GameStore().best(7,3,2)==123,"migrated settings failed to reload");
        }
        GameStore reset=new GameStore();reset.size=9;reset.colors=5;reset.balls=3;reset.music=false;reset.musicVolume=35;reset.effectsVolume=70;reset.patterns=false;reset.save();
        System.out.println("PASS: previous audio/pattern settings versions migrate without losing records");
    }
    static void kingPixelDetails() throws Exception {
        java.lang.reflect.Method render=Class.forName("lines.KingPixels").getDeclaredMethod("render",int.class);
        render.setAccessible(true);
        java.awt.image.BufferedImage reference=javax.imageio.ImageIO.read(new java.io.File("res/images/king.png"));
        java.io.File output=new java.io.File("build/king-pixels");output.mkdirs();
        for(int width:new int[]{1,16,28,35,42,56,69,70,105,140}) {
            javax.microedition.lcdui.Image image=(javax.microedition.lcdui.Image)render.invoke(null,width);
            int h=image.getHeight();int[] pixels=new int[width*h];image.getRGB(pixels,0,width,0,0,width,h);
            check(image.getWidth()==width&&Math.abs(h-width*66.0/70)<=0.5,"king proportions changed");
            if(width==70)for(int y=0;y<h;y++)for(int x=0;x<width;x++)
                check((pixels[y*width+x]&0xFFFFFF)==(reference.getRGB(x,y)&0xFFFFFF),"native king differs from source pixel");
            if(width==35) {
                // Both eyes retain equal white areas with separate inward pupils.
                check(pixels[15*width+12]==0xFFFFFFFF&&pixels[15*width+16]==0xFFFFFFFF,"eye whites disappeared");
                check(pixels[16*width+13]==0xFF000000&&pixels[16*width+15]==0xFF000000,"pupils disappeared");
                check(pixels[16*width+12]==0xFFFFFFFF&&pixels[16*width+16]==0xFFFFFFFF,"eyes merged or became asymmetric");
            }
            java.awt.image.BufferedImage preview=new java.awt.image.BufferedImage(width,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            preview.setRGB(0,0,width,h,pixels,0,width);
            javax.imageio.ImageIO.write(preview,"png",new java.io.File(output,"king-"+width+".png"));
        }
        System.out.println("PASS: king native pixel fidelity, varied output sizes and symmetric readable small eyes");
    }
    static void kingPedestal() throws Exception {
        BallKing king=new BallKing();king.layout(10,180,100,1,0);
        int initial=(Integer)get(king,"height"),previous=initial;
        check(initial==2,"new game pedestal must start as a tiny base");
        for(int n=0;n<40;n++)king.update(0);
        check((Integer)get(king,"height")==initial,"pedestal grew without scoring");
        java.lang.reflect.Method target=BallKing.class.getDeclaredMethod("targetHeight",int.class);target.setAccessible(true);
        for(int score:new int[]{5,10,50,100,1000,Integer.MAX_VALUE}){
            int goal=(Integer)target.invoke(king,score);check(goal>=previous&&goal<=100,"pedestal must grow monotonically within bounds");
            previous=goal;
        }
        king.celebrate(5);for(int n=0;n<40;n++)king.update(5);
        check((Integer)get(king,"height")>initial&&(Integer)get(king,"height")==((Integer)target.invoke(king,5)).intValue(),"pedestal did not animate upward");
        check((Integer)get(king,"saluteTicks")==0,"king celebration did not finish");king.reset(0);check((Integer)get(king,"height")==initial,"new game did not reset pedestal");
        king.layout(10,180,100,2,0);check((Integer)get(king,"height")==4,"large-screen starting base too tall");
        LinesCanvas c=canvas();call(c,"startNewGame");int seed=(Integer)get(c,"seed");int[] before=flat((int[][])get(c,"board"));
        BallKing actor=(BallKing)get(c,"king");actor.celebrate(5);for(int n=0;n<20;n++){call(c,"updateAnimation");call(c,"render");}
        check(Arrays.equals(before,flat((int[][])get(c,"board")))&&(Integer)get(c,"seed")==seed,"king animation changed game state");
        int ox=(Integer)get(c,"originX"),width=(Integer)get(c,"gridSizePx"),x=(Integer)get(actor,"x");
        javax.microedition.lcdui.Image sprite=(javax.microedition.lcdui.Image)get(actor,"sprite");
        check(x-(ox+width)>=10&&x+sprite.getWidth()<=c.getWidth()-2,"king lacks board spacing or leaves screen");c.stop();
        System.out.println("PASS: king pedestal growth/bounds/reset, celebration, separate layout and no gameplay/RNG changes");
    }
    public static void main(String[] args) {
        try {
            Mobile.minLogLevel=Mobile.LOG_ERROR;
            MobilePlatform platform=new MobilePlatform(240,320);Mobile.setPlatform(platform,new Runnable(){public void run(){}});
            check(platform.load(new java.io.File("dist/lines.jar").toURI().toString()),"cannot load JAR");
            for(String name:new String[]{System.getProperty("lines.store"),System.getProperty("lines.store")+"-game"})
                try{javax.microedition.rms.RecordStore.deleteRecordStore(name);}catch(Exception ignored){}
            new LinesMidlet();savedGameRoundTrip();hintsAndInvalidMoves();staggeredClearAndScore();accessiblePatterns();difficultyPresets();helpAndNewGameConfirmation();gameStatistics();savedClearAndGameOver();migrateRecentSettings();visibleMenuAndHelpText();kingPixelDetails();kingPedestal();
            System.out.println("ALL FEATURE CHECKS PASSED");System.exit(0);
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
