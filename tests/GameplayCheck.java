import java.lang.reflect.*;
import java.util.Arrays;
import lines.*;
import org.recompile.mobile.*;

/** Runs the real game logic against FreeJ2ME's MIDP implementation. */
public class GameplayCheck {
    static Field field(Object o, String n) throws Exception {
        Field f=o.getClass().getDeclaredField(n); f.setAccessible(true); return f;
    }
    static Object get(Object o,String n) throws Exception { return field(o,n).get(o); }
    static void set(Object o,String n,Object v) throws Exception { field(o,n).set(o,v); }
    static Object call(Object o,String n) throws Exception {
        Method m=o.getClass().getDeclaredMethod(n); m.setAccessible(true);return m.invoke(o);
    }
    static void select(LinesCanvas c,int r,int col) throws Exception {
        Method m=LinesCanvas.class.getDeclaredMethod("handleSelectOrMoveAt",int.class,int.class);
        m.setAccessible(true); m.invoke(c,r,col);
    }
    static void check(boolean ok,String text) { if(!ok)throw new AssertionError(text); }
    static int[] cells(int[][] b) { int[] a=new int[81]; for(int r=0;r<9;r++)for(int c=0;c<9;c++)a[r*9+c]=b[r][c];return a; }
    static LinesCanvas canvas() throws Exception { return new LinesCanvas(null); }
    static void initialUndo() throws Exception {
        LinesCanvas c=canvas(); call(c,"titleSelect");
        int[][] b=(int[][])get(c,"board"); int[] before=cells(b);
        boolean moved=false;
        outer:for(int r=0;r<9;r++)for(int col=0;col<9;col++)if(b[r][col]!=0){
            int[][] ds={{-1,0},{1,0},{0,-1},{0,1}};
            for(int[] d:ds){int rr=r+d[0],cc=col+d[1];if(rr>=0&&rr<9&&cc>=0&&cc<9&&b[rr][cc]==0){select(c,r,col);select(c,rr,cc);moved=true;break outer;}}
        }
        check(moved,"could not make initial move");
        for(int i=0;i<100;i++)call(c,"updateAnimation");
        check((Integer)get(c,"histSize")>1,"move not recorded");call(c,"undoNow");
        check((Integer)get(c,"state")==1,"first Undo returned to menu");
        check(Arrays.equals(before,cells(b)),"Undo did not restore board");
        System.out.println("PASS: first move + Undo stays in game and restores board");
    }
    static void spawnClear(boolean playerLine) throws Exception {
        LinesCanvas c=canvas();call(c,"startNewGame");call(c,"clearBoard");
        int[][] b=(int[][])get(c,"board");for(int i=0;i<4;i++)b[0][i]=1;
        set(c,"moveR",playerLine?0:8);set(c,"moveC",playerLine?4:8);set(c,"moveColor",playerLine?1:2);
        int[] nr=(int[])get(c,"nextR"), nc=(int[])get(c,"nextC"), color=(int[])get(c,"nextCol");
        nr[0]=0;nc[0]=4;color[0]=1;nr[1]=4;nc[1]=4;color[1]=3;nr[2]=6;nc[2]=6;color[2]=4;set(c,"nextCount",3);
        int[] queue=(int[])get(c,"queuedColors");queue[0]=5;queue[1]=6;queue[2]=7;
        int seed=(Integer)get(c,"seed");call(c,"finishMove");
        check((Boolean)get(c,"clearing"),"line did not start clearing");
        for(int i=0;i<6;i++)call(c,"updateAnimation");
        check((Integer)get(c,"score")==5,"incorrect score");
        for(int i=0;i<5;i++)check(b[0][i]==0,"line not removed");
        if(playerLine){check((Integer)get(c,"seed")==seed,"player line consumed next turn");check(color[0]==1,"player line changed previews");}
        else {check(color[0]==5&&color[1]==6&&color[2]==7,"spawn line did not advance preview colors");for(int i=0;i<3;i++)check(b[nr[i]][nc[i]]==0,"preview on occupied cell");}
        System.out.println("PASS: "+(playerLine?"player line preserves next turn":"spawned line refreshes previews"));
    }
    static void exitStopsThread() throws Exception {
        LinesMidlet m=new LinesMidlet();LinesCanvas c=canvas();set(m,"canvas",c);
        c.pause();c.start();Thread t=(Thread)get(c,"loop");call(m,"exit");t.join(1500);
        check(!t.isAlive(),"Exit left game loop running");
        System.out.println("PASS: Exit stops game thread");
    }
    static void variedStarts() throws Exception {
        java.util.HashSet<String> starts=new java.util.HashSet<String>();
        java.util.HashSet<String> freshStarts=new java.util.HashSet<String>();
        LinesCanvas c=canvas();
        for(int i=0;i<16;i++) {
            if(i%2==0)c=canvas(); // Both new application instances and new games.
            call(c,"startNewGame");
            int[][] b=(int[][])get(c,"board");int count=0;
            for(int n:cells(b))if(n!=0)count++;
            check(count==5,"new game must start with five balls");
            starts.add(Arrays.toString(cells(b)));
            if(i%2==0)freshStarts.add(Arrays.toString(cells(b)));
        }
        check(starts.size()>1,"all starting boards are identical");
        check(freshStarts.size()>1,"fresh application instances always start identically");
        System.out.println("PASS: fresh instances and rapid restarts have varied starting boards");
    }
    static void occupiedPreview() throws Exception {
        LinesCanvas c=canvas();call(c,"startNewGame");call(c,"clearBoard");
        int[][] b=(int[][])get(c,"board");b[0][0]=7;
        int[] nr=(int[])get(c,"nextR"),nc=(int[])get(c,"nextC"),col=(int[])get(c,"nextCol");
        for(int i=0;i<3;i++){nr[i]=0;nc[i]=i;col[i]=i+1;}
        set(c,"nextCount",3);
        // Save and repeat from the same snapshot, including the RNG state.
        call(c,"pushSnapshot");Object snapshot=((Object[])get(c,"hist"))[0];
        call(c,"applyPreviewBalls");int[] first=cells(b);
        check(b[0][0]==7,"preview overwrote player's ball");
        check(b[0][1]==2&&b[0][2]==3,"relocation stole another preview cell");
        int count=0,relocated=0;for(int n:first){if(n!=0)count++;if(n==1)relocated++;}
        check(count==4&&relocated==1,"occupied preview lost its ball");
        Method apply=LinesCanvas.class.getDeclaredMethod("applySnapshot",snapshot.getClass());apply.setAccessible(true);apply.invoke(c,snapshot);
        call(c,"applyPreviewBalls");
        check(Arrays.equals(first,cells(b)),"restoring RNG does not reproduce relocated balls");
        // Near-full board: only available cells may be used, no loop or overwrite.
        for(int r=0;r<9;r++)Arrays.fill(b[r],7);b[8][7]=0;b[8][8]=0;
        call(c,"applyPreviewBalls");check((Integer)call(c,"emptyCount")==0,"remaining cells not filled");
        check(b[0][0]==7,"full-board relocation overwrote a ball");
        System.out.println("PASS: occupied previews relocate safely, replay deterministically and handle a full board");
    }
    static void boundedHistory() throws Exception {
        LinesCanvas c=canvas();call(c,"startNewGame");
        for(int i=1;i<=200;i++){set(c,"score",i);call(c,"pushSnapshot");}
        check(((Object[])get(c,"hist")).length==128,"Undo storage grew without bound");
        check((Integer)get(c,"histSize")==128,"incorrect history size");
        for(int i=0;i<127;i++)call(c,"undoNow");
        check((Integer)get(c,"score")==73,"bounded Undo lost retained history order");
        Object[] hist=(Object[])get(c,"hist");for(int i=1;i<hist.length;i++)check(hist[i]==null,"undone snapshot retained");
        call(c,"clearBoard");for(Object item:hist)check(item==null,"new game retains old snapshots");
        System.out.println("PASS: Undo is bounded, keeps order and releases old snapshots");
    }
    static void key(LinesCanvas c,int code) throws Exception {
        Method m=LinesCanvas.class.getDeclaredMethod("keyPressed",int.class);m.setAccessible(true);m.invoke(c,code);
    }
    static void pointer(LinesCanvas c,int x,int y) throws Exception {
        Method m=LinesCanvas.class.getDeclaredMethod("pointerPressed",int.class,int.class);m.setAccessible(true);m.invoke(c,x,y);
    }
    static void queuedInput() throws Exception {
        LinesCanvas c=canvas();key(c,-6);
        check((Integer)get(c,"state")==0,"key callback changed game state");
        call(c,"tick");check((Integer)get(c,"state")==1,"queued START not handled");
        int[][] b=(int[][])get(c,"board");
        for(int r=0;r<9;r++)Arrays.fill(b[r],0);b[4][4]=1;
        int x=(Integer)get(c,"originX"),y=(Integer)get(c,"originY"),cell=(Integer)get(c,"cell");
        pointer(c,x+4*cell+cell/2,y+4*cell+cell/2);
        pointer(c,x+5*cell+cell/2,y+4*cell+cell/2);
        check(!(Boolean)get(c,"moving"),"pointer callback moved a ball outside the loop");
        call(c,"tick");check((Boolean)get(c,"moving"),"queued pointer order lost");
        check(b[4][4]==0,"queued move not started");
        // Undo during animation is discarded, not deferred to a later turn.
        int history=(Integer)get(c,"histSize");c.undoOne();call(c,"tick");
        check((Integer)get(c,"histSize")==history,"Undo changed an animated turn");
        for(int n=0;n<100;n++)call(c,"tick");
        check((Integer)get(c,"histSize")==history+1,"turn did not complete");
        c.undoOne();check((Integer)get(c,"histSize")==history+1,"Undo callback changed history");
        call(c,"tick");check((Integer)get(c,"histSize")==history,"queued Undo not applied");
        c.pause();key(c,-6);pointer(c,x,y);c.undoOne();
        check((Integer)get(c,"eventCount")==0,"paused input accumulated");
        call(c,"tick");check((Integer)get(c,"histSize")==history,"paused frame changed game");
        System.out.println("PASS: callbacks defer state changes, preserve click order and ignore paused/animated Undo");
    }
    static void queueBoundsAndExit() throws Exception {
        final Thread[] exitedOn=new Thread[1];
        LinesCanvas c=new LinesCanvas(new LinesCanvas.Host(){public void onExitRequested(){exitedOn[0]=Thread.currentThread();}});
        for(int n=0;n<1000;n++)key(c,'u');
        check((Integer)get(c,"eventCount")==32,"input queue is unbounded");
        set(c,"moving",true);c.requestExit();call(c,"tick");
        check((Boolean)get(c,"stopped"),"full queue or animation blocked Exit");
        check((Integer)get(c,"eventCount")==0,"stopped canvas kept input");
        check(exitedOn[0]!=null,"Exit did not notify host");
        c=new LinesCanvas(new LinesCanvas.Host(){public void onExitRequested(){exitedOn[0]=Thread.currentThread();}});
        c.start();c.pause();Thread t=(Thread)get(c,"loop");
        c.requestExit();t.join(1500);
        check(!t.isAlive(),"paused loop ignored Exit");
        check(exitedOn[0]==t,"Exit was handled outside the game thread");
        c.start();check(get(c,"loop")==null,"stopped canvas started another game loop");
        System.out.println("PASS: bounded input, Exit during animation/full queue/pause and no resurrection after stop");
    }
    static void concurrentCallbacks() throws Exception {
        final LinesCanvas c=canvas();c.start();
        final Throwable[] errors=new Throwable[2];Thread[] producers=new Thread[2];
        for(int k=0;k<2;k++){
            final int at=k;
            producers[k]=new Thread(new Runnable(){public void run(){try{
                for(int i=0;i<500;i++){
                    key(c,-6);pointer(c,100,100);c.undoOne();
                    if(i%25==0){c.pause();c.start();}
                }
            }catch(Throwable e){errors[at]=e;}}});producers[k].start();
        }
        for(Thread t:producers){t.join(5000);check(!t.isAlive(),"callback producer deadlocked");}
        for(Throwable e:errors)if(e!=null)throw new AssertionError(e);
        c.pause();Thread loop=(Thread)get(c,"loop");
        check(loop!=null&&loop.isAlive(),"concurrent input killed the game loop");
        synchronized(c){check((Integer)get(c,"eventCount")<=32,"queue exceeded capacity");}
        c.stop();loop.join(1500);check(!loop.isAlive(),"loop did not stop after concurrent input");
        System.out.println("PASS: concurrent key, pointer, Undo and pause/resume callbacks do not deadlock or kill loop");
    }
    static void settingsAndModes() throws Exception {
        LinesCanvas c=canvas();call(c,"startNewGame");
        GameStore prefs=(GameStore)get(c,"preferences");
        // Settings remain separate from the active rules until a new game starts.
        key(c,-7);call(c,"tick");check((Integer)get(c,"state")==0,"Menu did not open");
        set(c,"menuSel",2);call(c,"titleSelect");check((Integer)get(c,"state")==4,"Settings missing");
        prefs.size=7;prefs.colors=3;prefs.balls=1;
        check((Integer)get(c,"N")==9,"settings resized an active game");
        key(c,-6);call(c,"tick");
        key(c,-6);call(c,"tick");check((Integer)get(c,"N")==9,"Resume applied new rules");
        key(c,-7);call(c,"tick");set(c,"menuSel",1);call(c,"titleSelect");
        check((Integer)get(c,"N")==7&&(Integer)get(c,"colorCount")==3&&(Integer)get(c,"ballsPerTurn")==1,"New Game ignored settings");
        for(int size:GameStore.SIZES)for(int colors=3;colors<=7;colors++)for(int balls=1;balls<=3;balls++){
            prefs.size=size;prefs.colors=colors;prefs.balls=balls;call(c,"startNewGame");
            int[][] board=(int[][])get(c,"board");check(board.length==size&&board[0].length==size,"wrong board dimensions");
            check(((int[])get(c,"pathR")).length==size*size,"path buffer does not fit board");
            check((Integer)get(c,"nextCount")==balls,"wrong number of previews");
            for(int[] row:board)for(int color:row)check(color>=0&&color<=colors,"generated disabled color");
            int count=0;for(int[] row:board)for(int color:row)if(color>0)count++;
            call(c,"applyPreviewBalls");check((Integer)call(c,"emptyCount")==size*size-count-balls,"wrong spawn count");
            int[][] original=new int[size][size];for(int r=0;r<size;r++)System.arraycopy(board[r],0,original[r],0,size);
            call(c,"pushSnapshot");for(int[] row:board)Arrays.fill(row,0);call(c,"pushSnapshot");call(c,"undoNow");
            for(int r=0;r<size;r++)check(Arrays.equals(original[r],board[r]),"Undo failed on nondefault board");
            call(c,"clearBoard");for(int col=size-5;col<size;col++)board[size-1][col]=1;
            check((Boolean)call(c,"startClearIfAny"),"line detection failed at board edge");
            for(int n=0;n<6;n++)call(c,"updateAnimation");
            check((Integer)get(c,"score")==5,"nondefault board line score wrong");
            check(prefs.best(size,colors,balls)>=5,"score lost its active ruleset");
        }
        prefs.size=9;prefs.colors=5;prefs.balls=3;prefs.music=false;prefs.save();c.stop();
        System.out.println("PASS: settings/resume/new game and all 45 size-color-spawn modes, Undo, line clearing and records");
    }
    static void persistentRecords() throws Exception {
        GameStore store=new GameStore();store.size=11;store.colors=4;store.balls=2;store.music=true;
        store.record(7,3,1,432);store.record(11,7,3,987);store.save();check(store.storageOk,"RMS save failed");
        GameStore loaded=new GameStore();
        check(loaded.size==11&&loaded.colors==4&&loaded.balls==2&&loaded.music,"preferences not persisted");
        check(loaded.best(7,3,1)==432&&loaded.best(11,7,3)==987,"records/settings not persisted");
        check(!loaded.record(7,3,1,12)&&loaded.best(7,3,1)==432,"lower score overwrote record");
        check(!loaded.record(8,2,0,50),"invalid mode accepted");
        LinesCanvas c=canvas();call(c,"refreshStatistics");int[] order=(int[])get(c,"statsOrder");
        GameStore cp=(GameStore)get(c,"preferences");check(cp.scoreAt(order[0])==987,"statistics not sorted by record");
        loaded.size=9;loaded.colors=5;loaded.balls=3;loaded.music=false;loaded.save();
        javax.microedition.rms.RecordStore rms=javax.microedition.rms.RecordStore.openRecordStore("lines-automated-check",true);
        byte[] invalid={1,2,3};rms.setRecord(1,invalid,0,invalid.length);rms.closeRecordStore();
        GameStore corrupt=new GameStore();check(!corrupt.storageOk&&corrupt.size==9&&corrupt.colors==5,"corrupt RMS not handled safely");
        loaded.save();check(new GameStore().storageOk,"RMS did not recover after saving valid data");
        System.out.println("PASS: RMS settings and per-mode records reload, sorting, lower-score protection and corrupt-data recovery");
    }
    static void bounceAndPreviews() throws Exception {
        MobilePlatform.appTerminated=false;Mobile.isPaused=false;
        LinesCanvas c=canvas();call(c,"startNewGame");
        int[][] board=(int[][])get(c,"board");for(int[] row:board)Arrays.fill(row,0);board[4][4]=2;
        select(c,4,4);int[] before=cells(board);int score=(Integer)get(c,"score"),seed=(Integer)get(c,"seed");
        for(int n=0;n<32;n++){call(c,"updateAnimation");call(c,"render");}
        check(Arrays.equals(before,cells(board))&&(Integer)get(c,"score")==score&&(Integer)get(c,"seed")==seed,"bounce changed gameplay");
        check((Integer)get(c,"selectionTick")==0,"bounce does not loop");
        java.io.File folder=new java.io.File("build/previews");folder.mkdirs();
        Mobile.getDisplay().setCurrent(c);
        Field buffer=javax.microedition.lcdui.game.GameCanvas.class.getDeclaredField("buffer");buffer.setAccessible(true);
        org.recompile.mobile.PlatformImage pixels=(org.recompile.mobile.PlatformImage)buffer.get(c);
        int[] phases={0,6};for(int phase:phases){set(c,"selectionTick",phase);call(c,"render");
            javax.imageio.ImageIO.write(pixels.getCanvas(),"png",new java.io.File(folder,"bounce-"+phase+".png"));}
        set(c,"state",0);set(c,"hasGame",false);call(c,"render");
        javax.imageio.ImageIO.write(pixels.getCanvas(),"png",new java.io.File(folder,"menu.png"));
        set(c,"state",4);call(c,"render");
        javax.imageio.ImageIO.write(pixels.getCanvas(),"png",new java.io.File(folder,"settings.png"));
        set(c,"state",5);call(c,"refreshStatistics");call(c,"render");
        javax.imageio.ImageIO.write(pixels.getCanvas(),"png",new java.io.File(folder,"records.png"));
        check(!java.util.Arrays.equals(java.nio.file.Files.readAllBytes(new java.io.File(folder,"bounce-0.png").toPath()),java.nio.file.Files.readAllBytes(new java.io.File(folder,"bounce-6.png").toPath())),"selected ball frames are identical");
        c.stop();
        javax.sound.midi.Sequence song=javax.sound.midi.MidiSystem.getSequence(new java.io.File("res/music/minigames.mid"));
        check(song.getTracks().length>0&&song.getMicrosecondLength()>10000000,"invalid soundtrack");
        System.out.println("PASS: bounce changes rendered frames without changing game state; MIDI is valid ("+song.getMicrosecondLength()/1000000+" seconds)");
    }
    static void optionalAudioFailure() throws Exception {
        // Test classpath deliberately has no /music resources: failure must be harmless.
        Soundtrack sound=new Soundtrack();sound.setActive(true);
        Field f=Soundtrack.class.getDeclaredField("worker");f.setAccessible(true);
        Thread worker=(Thread)f.get(sound);
        long until=System.currentTimeMillis()+2000;
        while(!sound.failed()&&System.currentTimeMillis()<until)Thread.sleep(10);
        check(sound.failed(),"missing music resource was not reported");
        sound.setActive(false);sound.close();worker.join(1500);
        check(!worker.isAlive(),"audio worker did not release after close");
        sound.setActive(true);check(!worker.isAlive(),"closed audio started again");
        System.out.println("PASS: audio resource failure stays optional and its worker closes cleanly");
    }
    static void audioPreferencesAndMigration() throws Exception {
        GameStore store=new GameStore();store.musicVolume=25;store.effectsVolume=80;store.effects=false;store.save();
        GameStore read=new GameStore();check(read.musicVolume==25&&read.effectsVolume==80&&!read.effects,"audio preferences were not saved");
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream data=new java.io.DataOutputStream(bytes);
        data.writeInt(0x4C4E5301);data.writeByte(9);data.writeByte(4);data.writeByte(2);data.writeBoolean(true);
        for(int n=0;n<45;n++)data.writeInt(n==GameStore.index(9,4,2)?3210:0);data.flush();
        javax.microedition.rms.RecordStore rms=javax.microedition.rms.RecordStore.openRecordStore("lines-automated-check",true);
        byte[] old=bytes.toByteArray();rms.setRecord(1,old,0,old.length);rms.closeRecordStore();
        GameStore migrated=new GameStore();check(migrated.storageOk&&migrated.size==9&&migrated.colors==4&&migrated.balls==2&&migrated.music,"old preferences did not load");
        check(migrated.best(9,4,2)==3210&&migrated.musicVolume==35&&migrated.effectsVolume==70&&migrated.effects,"migration lost records or default audio levels");
        migrated.save();check(new GameStore().best(9,4,2)==3210,"migration did not persist record");
        migrated.colors=5;migrated.balls=3;migrated.music=false;migrated.save();
        LinesCanvas c=canvas();set(c,"state",4);set(c,"settingsSel",4);
        Method adjust=LinesCanvas.class.getDeclaredMethod("adjustSetting",int.class);adjust.setAccessible(true);
        GameStore prefs=(GameStore)get(c,"preferences");
        for(int n=0;n<30;n++)adjust.invoke(c,-1);
        check(prefs.musicVolume==0&&(Integer)get(get(c,"soundtrack"),"volume")==0,"music mute not applied immediately");
        for(int n=0;n<30;n++)adjust.invoke(c,1);
        check(prefs.musicVolume==100&&(Integer)get(get(c,"soundtrack"),"volume")==100,"music volume not clamped/applied");
        check(prefs.effectsVolume==70&&(Integer)get(get(c,"soundEffects"),"volume")==70,"music volume changed effects volume");
        set(c,"settingsSel",6);call(c,"scrollSettings");check((Integer)get(c,"settingsScroll")==0,"all audio controls must fit on one screen");
        adjust.invoke(c,-1);check((Integer)get(get(c,"soundEffects"),"volume")==65,"effect volume not independently applied");
        check(prefs.musicVolume==100&&(Integer)get(get(c,"soundtrack"),"volume")==100,"effect volume changed music volume");
        check(!prefs.music,"volume adjustment unexpectedly enabled music");
        call(c,"render");
        Field buffer=javax.microedition.lcdui.game.GameCanvas.class.getDeclaredField("buffer");buffer.setAccessible(true);
        org.recompile.mobile.PlatformImage screen=(org.recompile.mobile.PlatformImage)buffer.get(c);
        javax.imageio.ImageIO.write(screen.getCanvas(),"png",new java.io.File("build/previews/settings-audio.png"));
        c.stop();
        System.out.println("PASS: independent audio levels, live setting changes, scrollable controls and migration retain old records");
    }
    static void volumeControlAndEffects() throws Exception {
        final int[] level={-1};final boolean[] muted={false};
        javax.microedition.media.control.VolumeControl control=(javax.microedition.media.control.VolumeControl)java.lang.reflect.Proxy.newProxyInstance(
            GameplayCheck.class.getClassLoader(),new Class[]{javax.microedition.media.control.VolumeControl.class},new InvocationHandler(){public Object invoke(Object o,Method m,Object[] a){
                if(m.getName().equals("setLevel")){level[0]=(Integer)a[0];return level[0];}
                if(m.getName().equals("setMute")){muted[0]=(Boolean)a[0];return null;}
                if(m.getName().equals("getLevel"))return level[0];
                if(m.getName().equals("isMuted"))return muted[0];return null;
            }});
        final javax.microedition.media.control.VolumeControl vc=control;
        javax.microedition.media.Player player=(javax.microedition.media.Player)java.lang.reflect.Proxy.newProxyInstance(
            GameplayCheck.class.getClassLoader(),new Class[]{javax.microedition.media.Player.class},new InvocationHandler(){public Object invoke(Object o,Method m,Object[] a){return m.getName().equals("getControl")?vc:null;}});
        Class<?> volume=Class.forName("lines.AudioVolume");Method apply=volume.getDeclaredMethod("apply",javax.microedition.media.Player.class,int.class);apply.setAccessible(true);
        apply.invoke(null,player,-10);check(level[0]==0&&muted[0],"zero volume is not muted");
        apply.invoke(null,player,45);check(level[0]==45&&!muted[0],"nonzero volume did not unmute");
        apply.invoke(null,player,120);check(level[0]==100,"VolumeControl exceeded 100");
        SoundEffects fx=new SoundEffects();fx.configure(false,70);fx.pop();check(get(fx,"worker")==null,"disabled effects started audio");
        fx.configure(true,0);fx.pop();check(get(fx,"worker")==null,"muted effects started audio");
        fx.configure(true,70);fx.setSuspended(true);fx.pop();check(get(fx,"worker")==null,"paused effects started audio");
        fx.setSuspended(false);fx.pop();Thread worker=(Thread)get(fx,"worker");check(worker!=null,"effect did not start without music");
        long until=System.currentTimeMillis()+2000;while(!fx.failed()&&System.currentTimeMillis()<until)Thread.sleep(10);
        check(fx.failed(),"missing pop resource did not fail safely");fx.close();worker.join(1500);check(!worker.isAlive(),"effects worker did not close");
        System.out.println("PASS: VolumeControl clamp/mute/unmute, effects independent of music, pause/disable/mute and safe audio failure");
    }
    static void settingsSaveAndBack() throws Exception {
        LinesCanvas c=canvas();GameStore prefs=(GameStore)get(c,"preferences");
        prefs.size=9;prefs.colors=5;prefs.balls=3;prefs.music=false;prefs.musicVolume=35;prefs.effects=true;prefs.effectsVolume=70;prefs.save();
        int best=prefs.best(9,4,2);
        call(c,"enterSettings");prefs.size=11;prefs.colors=3;prefs.balls=1;
        prefs.music=true;prefs.musicVolume=90;prefs.effects=false;prefs.effectsVolume=10;
        key(c,-7);call(c,"tick");
        check((Integer)get(c,"state")==0&&prefs.size==9&&prefs.colors==5&&prefs.balls==3,"Back did not discard rule edits");
        check(!prefs.music&&prefs.musicVolume==35&&prefs.effects&&prefs.effectsVolume==70,"Back did not discard audio edits");
        check((Integer)get(get(c,"soundtrack"),"volume")==35&&!(Boolean)get(get(c,"soundtrack"),"active"),"Back did not restore live music");
        check((Integer)get(get(c,"soundEffects"),"volume")==70&&(Boolean)get(get(c,"soundEffects"),"enabled"),"Back did not restore live effects");
        check(new GameStore().colors==5&&prefs.best(9,4,2)==best,"Back changed saved settings or records");
        call(c,"enterSettings");prefs.colors=4;prefs.musicVolume=45;key(c,-6);call(c,"tick");
        check((Integer)get(c,"state")==0&&new GameStore().colors==4&&new GameStore().musicVolume==45,"Save key did not persist edits");
        call(c,"enterSettings");prefs.colors=7;
        int y=(Integer)get(c,"softBarY")+2;pointer(c,c.getWidth()-4,y);call(c,"tick");
        check(prefs.colors==4&&new GameStore().colors==4,"Back click did not cancel");
        call(c,"enterSettings");prefs.colors=6;pointer(c,4,y);call(c,"tick");
        check(new GameStore().colors==6,"Save click did not persist");
        prefs.colors=5;prefs.musicVolume=35;prefs.save();c.stop();
        System.out.println("PASS: distinct Save/Back keys and clicks, cancel restores live audio and preserves records");
    }
    public static void main(String[] args) {
        try {
            Mobile.minLogLevel=Mobile.LOG_ERROR;
            MobilePlatform p=new MobilePlatform(240,320);Mobile.setPlatform(p,new Runnable(){public void run(){}});
            check(p.load(new java.io.File("dist/lines.jar").toURI().toString()),"cannot load packaged MIDlet");
            try { javax.microedition.rms.RecordStore.deleteRecordStore("lines-automated-check"); } catch(Exception ignored) {}
            new LinesMidlet();
            variedStarts();initialUndo();spawnClear(false);spawnClear(true);occupiedPreview();boundedHistory();exitStopsThread();queuedInput();queueBoundsAndExit();concurrentCallbacks();settingsAndModes();persistentRecords();bounceAndPreviews();optionalAudioFailure();audioPreferencesAndMigration();volumeControlAndEffects();settingsSaveAndBack();
            System.out.println("ALL GAMEPLAY CHECKS PASSED");System.exit(0);
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
