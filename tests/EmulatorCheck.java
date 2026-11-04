import java.io.File;
import java.lang.reflect.*;
import java.util.Arrays;
import org.recompile.mobile.*;
import org.recompile.freej2me.FreeJ2ME;

/** Optional visible FreeJ2ME integration check; uses a dedicated RMS namespace. */
public class EmulatorCheck {
    static Object get(Object o,String name)throws Exception { Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o); }
    static void set(Object o,String name,Object value)throws Exception { Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value); }
    static Object call(Object o,String name)throws Exception { Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o); }
    static void key(Object c,int code)throws Exception { Method m=c.getClass().getDeclaredMethod("keyPressed",int.class);m.setAccessible(true);m.invoke(c,code); }
    static void click(Object c,int x,int y)throws Exception { Method m=c.getClass().getDeclaredMethod("pointerPressed",int.class,int.class);m.setAccessible(true);m.invoke(c,x,y); }
    static void release(Object c,int key)throws Exception { Method m=c.getClass().getDeclaredMethod("keyReleased",int.class);m.setAccessible(true);m.invoke(c,key); }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void state(Object c,int wanted)throws Exception {
        long end=System.currentTimeMillis()+5000;while((Integer)get(c,"state")!=wanted&&System.currentTimeMillis()<end)Thread.sleep(20);
        check((Integer)get(c,"state")==wanted,"screen "+wanted+" not reached");
    }
    static int[] cells(Object c)throws Exception { int[][] b=(int[][])get(c,"board");int[] a=new int[b.length*b.length];for(int r=0;r<b.length;r++)for(int col=0;col<b.length;col++)a[r*b.length+col]=b[r][col];return a; }
    static void kingBounds(Object c)throws Exception {
        synchronized(c) {
            Object king=get(c,"king");
            javax.microedition.lcdui.Image sprite=(javax.microedition.lcdui.Image)get(king,"sprite");
            int x=(Integer)get(king,"x"), right=(Integer)get(c,"originX")+(Integer)get(c,"gridSizePx");
            check(x-right>=10,"king too close to board");
            check(x+sprite.getWidth()<=((javax.microedition.lcdui.Displayable)c).getWidth()-2,"king clipped at screen edge");
            check((Integer)get(king,"baseY")-(Integer)get(king,"maxHeight")-sprite.getHeight()>=0,"raised king clips at top");
        }
    }
    static void screenshot(String name)throws Exception {
        Thread.sleep(100);File dir=new File(System.getProperty("lines.project","."),"build/emulator-check");dir.mkdirs();
        javax.imageio.ImageIO.write(Mobile.getPlatform().getLcdFrontbufferImage(),"png",new File(dir,name+".png"));
    }
    static void stable(Object c)throws Exception {
        long until=System.currentTimeMillis()+7000;
        while(((Boolean)get(c,"moving")||(Boolean)get(c,"clearing"))&&System.currentTimeMillis()<until)Thread.sleep(20);
        check(!(Boolean)get(c,"moving")&&!(Boolean)get(c,"clearing"),"animation stuck");
    }
    static void menu(Object c,int index,int screen)throws Exception {
        if((Integer)get(c,"state")!=0){key(c,-7);state(c,0);}
        synchronized(c){set(c,"menuSel",index);}key(c,-6);state(c,screen);
    }
    static void progressionJourney(Object c,int width,int height)throws Exception {
        String suffix="-"+width+"x"+height;
        Object progress=get(c,"progress");Class<?> type=progress.getClass();
        int today=(Integer)type.getMethod("today").invoke(null);
        synchronized(c) {
            set(progress,"awards",0);set(progress,"sizesMask",0);set(progress,"skin",0);
            type.getMethod("recordDaily",int.class,int.class).invoke(progress,today,3);
        }
        menu(c,6,9);screenshot("daily-menu"+suffix);
        int[] preserved=cells(c);key(c,-6);state(c,7);key(c,-6);state(c,0);
        check(Arrays.equals(preserved,cells(c)),"daily cancel lost active game");
        menu(c,6,9);key(c,-6);state(c,7);key(c,-7);state(c,1);
        check((Integer)get(c,"dailyDay")==today&&(Integer)get(c,"N")==9&&(Integer)get(c,"colorCount")==5&&(Integer)get(c,"ballsPerTurn")==2,"daily rules mismatch");
        screenshot("daily-game"+suffix);
        int initialColumn=(Integer)get(c,"curC");key(c,'6');Thread.sleep(100);release(c,'6');Thread.sleep(80);
        check((Integer)get(c,"curC")==initialColumn+1,"numeric key tap failed in emulator");
        key(c,'6');Thread.sleep(470);release(c,'6');Thread.sleep(80);
        check((Integer)get(c,"curC")>initialColumn+2,"direction hold failed in emulator");
        synchronized(c) {
            int[][] b=(int[][])get(c,"board");for(int[] row:b)Arrays.fill(row,0);
            for(int col=0;col<6;col++)b[4][col]=1;b[5][6]=1;
            set(c,"selR",-1);set(c,"selC",-1);call(c,"pushSnapshot");
        }
        int cell=(Integer)get(c,"cell"),ox=(Integer)get(c,"originX"),oy=(Integer)get(c,"originY");
        click(c,ox+6*cell+cell/2,oy+5*cell+cell/2);Thread.sleep(80);click(c,ox+6*cell+cell/2,oy+4*cell+cell/2);
        long clearDeadline=System.currentTimeMillis()+6000;
        while((Integer)get(c,"score")<7&&System.currentTimeMillis()<clearDeadline)Thread.sleep(20);
        stable(c);
        screenshot("daily-clear-check"+suffix);
        check((Integer)get(c,"score")==7&&(Boolean)get(c,"recordCelebrated"),"daily clear/record celebration failed: score="+get(c,"score")+" target="+get(c,"startingBest")+" selected="+get(c,"selR")+","+get(c,"selC")+" moving="+get(c,"moving")+" clearing="+get(c,"clearing"));
        check((Integer)type.getMethod("count").invoke(progress)>=3,"daily first clear did not unlock expected achievements");
        screenshot("king-record"+suffix);
        synchronized(c){
            int[][] b=(int[][])get(c,"board");for(int r=0;r<b.length;r++)for(int col=0;col<b.length;col++)b[r][col]=(r*2+col)%5+1;
            b[0][0]=b[0][1]=b[0][2]=0;set(c,"selR",-1);set(c,"selC",-1);
            Object king=get(c,"king");set(king,"saluteTicks",0);set(king,"victoryTicks",0);
        }
        screenshot("worried-king"+suffix);
        synchronized(c){
            int[][] b=(int[][])get(c,"board");b[0][0]=b[0][1]=b[0][2]=1;
            Method turn=c.getClass().getDeclaredMethod("completeTurn",int.class);turn.setAccessible(true);turn.invoke(c,0);
        }
        state(c,3);screenshot("game-results"+suffix);key(c,-6);state(c,1);
        check((Integer)get(c,"dailyDay")==today&&(Integer)get(c,"score")==0,"daily Retry lost mode");
        menu(c,7,8);screenshot("achievements"+suffix);
        key(c,'8');Thread.sleep(60);release(c,'8');Thread.sleep(60);
        check((Integer)get(c,"awardsScroll")>0,"achievement keyboard scroll failed");screenshot("achievements-scroll"+suffix);
        menu(c,2,4);
        synchronized(c){set(c,"settingsSel",9);call(c,"scrollSettings");}
        screenshot("vibration-setting"+suffix);
        key(c,'6');Thread.sleep(60);release(c,'6');Thread.sleep(60);check((Boolean)get(progress,"vibration"),"vibration setting did not toggle");
        synchronized(c){set(c,"settingsSel",10);call(c,"scrollSettings");}
        key(c,'6');Thread.sleep(60);release(c,'6');Thread.sleep(60);check((Integer)get(progress,"skin")==1,"unlocked king skin could not be selected");
        screenshot("king-skin-setting"+suffix);key(c,-6);state(c,0);key(c,-6);state(c,1);screenshot("emerald-king"+suffix);
        System.out.println("VISIBLE PROGRESSION PASSED: "+width+"x"+height+" daily, record/king, results/retry, achievements/skin, vibration controls and held numeric keys");
    }
    public static void main(String[] args) {
        try {
            int width=args.length>0?Integer.parseInt(args[0]):240,height=args.length>1?Integer.parseInt(args[1]):320;
            String name=System.getProperty("lines.store");check(name!=null&&name.startsWith("lines-ui-"),"dedicated RMS namespace required");
            Mobile.setPlatform(new MobilePlatform(width,height),()->{});
            String jar=new File(System.getProperty("lines.project","."),"dist/lines.jar").getAbsolutePath();check(Mobile.getPlatform().load(new File(jar).toURI().toString()),"JAR load failed");
            for(String n:new String[]{name,name+"-game",name+"-progress"})try{javax.microedition.rms.RecordStore.deleteRecordStore(n);}catch(Exception ignored){}
            Mobile.clearOldLog();MobilePlatform.lcdWidth=0;MobilePlatform.lcdHeight=0;
            FreeJ2ME.app=new FreeJ2ME(new String[]{jar,"0",""+width,""+height,"2"});
            Object midlet=Mobile.midlet;Object c=get(midlet,"canvas");
            check(get(get(c,"preferences"),"name").equals(name),"emulator did not isolate saved data");
            state(c,0);
            check(((javax.microedition.lcdui.Displayable)c).getWidth()==width&&((javax.microedition.lcdui.Displayable)c).getHeight()==height,"launch resolution ignored");
            if(width!=240||height!=320){
                screenshot("menu-"+width+"x"+height);key(c,-6);state(c,1);kingBounds(c);screenshot("game-"+width+"x"+height);
                int[] unchanged=cells(c);int sameSeed=(Integer)get(c,"seed");menu(c,2,4);screenshot("settings-"+width+"x"+height);
                synchronized(c){set(c,"settingsSel",8);call(c,"scrollSettings");}screenshot("settings-bottom-"+width+"x"+height);
                menu(c,4,6);String[] lines=(String[])get(c,"helpLines");
                for(String line:lines)check(javax.microedition.lcdui.Font.getDefaultFont().stringWidth(line)<=width-20,"help clips on small screen");
                screenshot("help-"+width+"x"+height);menu(c,1,7);screenshot("confirm-"+width+"x"+height);key(c,-6);state(c,0);
                menu(c,3,5);screenshot("statistics-"+width+"x"+height);
                check(Arrays.equals(unchanged,cells(c))&&(Integer)get(c,"seed")==sameSeed,"navigation changed saved game");
                progressionJourney(c,width,height);call(c,"stop");
                System.out.println("VISIBLE EMULATOR LAYOUT PASSED: "+width+"x"+height);System.exit(0);
            }
            key(c,-6);state(c,1);
            kingBounds(c);
            int initialKingHeight=(Integer)get(get(c,"king"),"height");
            check(initialKingHeight==2,"new game pedestal did not start small");screenshot("king-start");
            int[][] b=(int[][])get(c,"board");int n=b.length,cell=(Integer)get(c,"cell"),ox=(Integer)get(c,"originX"),oy=(Integer)get(c,"originY");boolean moved=false;
            outer:for(int r=0;r<n;r++)for(int col=0;col<n;col++)if(b[r][col]>0)for(int[] d:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}){
                int rr=r+d[0],cc=col+d[1];if(rr>=0&&rr<n&&cc>=0&&cc<n&&b[rr][cc]==0){click(c,ox+col*cell+cell/2,oy+r*cell+cell/2);Thread.sleep(80);
                    Method drag=c.getClass().getDeclaredMethod("pointerDragged",int.class,int.class);drag.setAccessible(true);drag.invoke(c,ox+cc*cell+cell/2,oy+rr*cell+cell/2);Thread.sleep(80);
                    check((Integer)get(c,"hintLen")>0,"route hint missing in emulator");screenshot("reachable-route");
                    click(c,ox+cc*cell+cell/2,oy+rr*cell+cell/2);Thread.sleep(80);moved=true;break outer;}
            }
            check(moved,"no move found");stable(c);check((Integer)get(c,"moves")==1,"emulator move not counted");
            int[] saved=cells(c);int seed=(Integer)get(c,"seed");screenshot("saved-turn");
            call(midlet,"pauseApp");call(c,"stop");
            Object resumed=midlet.getClass().getConstructor().newInstance();call(resumed,"startApp");c=get(resumed,"canvas");
            state(c,0);check((Boolean)get(c,"hasGame"),"Resume missing after MIDlet restart");key(c,-6);state(c,1);
            check(Arrays.equals(saved,cells(c))&&(Integer)get(c,"seed")==seed&&(Integer)get(c,"moves")==1,"MIDlet restart lost saved turn");
            screenshot("resumed-turn");
            synchronized(c) {
                call(c,"clearBoard");int[][] demo=(int[][])get(c,"board");
                for(int col=0;col<4;col++)demo[4][col]=1;demo[5][4]=1;call(c,"pushSnapshot");
            }
            cell=(Integer)get(c,"cell");ox=(Integer)get(c,"originX");oy=(Integer)get(c,"originY");
            click(c,ox+4*cell+cell/2,oy+5*cell+cell/2);Thread.sleep(80);click(c,ox+4*cell+cell/2,oy+4*cell+cell/2);
            long until=System.currentTimeMillis()+4000;while(!(Boolean)get(c,"clearing")&&System.currentTimeMillis()<until)Thread.sleep(10);
            check((Boolean)get(c,"clearing"),"real move did not clear a line");screenshot("staggered-clear");stable(c);
            check((Integer)get(c,"score")==5&&(Integer)get(c,"popupScore")==5,"clear score/popup wrong");screenshot("points-popup");
            Object king=get(c,"king");check((Integer)get(king,"height")>initialKingHeight,"king pedestal did not rise after clearing balls");
            screenshot("king-after-clear");
            synchronized(c){set(c,"score",100);set(c,"displayScore",100);king.getClass().getMethod("reset",int.class).invoke(king,100);}
            screenshot("king-high-pedestal");
            synchronized(c){set(c,"score",5);set(c,"displayScore",5);king.getClass().getMethod("reset",int.class).invoke(king,5);}
            key(c,-7);state(c,0);screenshot("main-menu");menu(c,2,4);
            int settingsTop=40,settingsStep=21; // Screen hit targets; constants are inlined/removed by ProGuard.
            check((Integer)call(c,"settingsVisible")==9,"nine settings must fit at 240x320");
            click(c,220,settingsTop+7*settingsStep+8);Thread.sleep(100);
            Object prefs=get(c,"preferences");check((Boolean)get(prefs,"patterns"),"Patterns did not turn on");
            click(c,220,settingsTop+8*settingsStep+8);Thread.sleep(100);
            check((Integer)get(prefs,"colors")==4&&(Integer)get(prefs,"balls")==1,"Relaxed preset not applied: colors="+get(prefs,"colors")+" balls="+get(prefs,"balls")+" selected="+get(c,"settingsSel"));
            check((Integer)get(c,"colorCount")==5,"preset changed a running game");screenshot("settings-new-options");
            key(c,-6);state(c,0);int[] keep=cells(c);menu(c,1,7);screenshot("new-game-confirmation");
            key(c,-6);state(c,0);check(Arrays.equals(keep,cells(c)),"confirmation Cancel lost board");
            menu(c,1,7);key(c,-7);state(c,1);
            check((Integer)get(c,"colorCount")==4&&(Integer)get(c,"ballsPerTurn")==1,"confirmed game ignored preset");
            b=(int[][])get(c,"board");cell=(Integer)get(c,"cell");ox=(Integer)get(c,"originX");oy=(Integer)get(c,"originY");
            outer2:for(int r=0;r<b.length;r++)for(int col=0;col<b.length;col++)if(b[r][col]>0){click(c,ox+col*cell+cell/2,oy+r*cell+cell/2);break outer2;}
            Thread.sleep(100);screenshot("patterned-game");
            menu(c,4,6);screenshot("help-first-page");for(int i=0;i<5;i++){click(c,120,220);Thread.sleep(50);}
            check((Integer)get(c,"helpScroll")>0,"Help did not scroll");screenshot("help-scrolled");
            menu(c,3,5);check((Integer)get(c,"statsCount")>0,"statistics empty after game");screenshot("expanded-statistics");
            for(int wanted:new int[]{7,11}){
                menu(c,2,4);
                while((Integer)get(prefs,"size")!=wanted){click(c,220,settingsTop+8);Thread.sleep(80);}
                key(c,-6);state(c,0);menu(c,1,7);key(c,-7);state(c,1);
                check((Integer)get(c,"N")==wanted,"manual board size not applied");kingBounds(c);screenshot("field-"+wanted+"x"+wanted);
            }
            progressionJourney(c,width,height);call(c,"stop");
            System.out.println("VISIBLE EMULATOR CHECK PASSED: all seven features, real input, RMS resume, patterns/presets, confirmation/help/statistics and 7x7/9x9/11x11 boards");System.exit(0);
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
