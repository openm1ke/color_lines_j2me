import java.io.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.Arrays;
import lines.*;
import org.recompile.mobile.*;

/** Player-facing journeys and compatibility of the new optional RMS data. */
public class ProgressionCheck extends FeatureCheck {
    static void start(LinesCanvas c,int size,int colors,int balls,int day)throws Exception {
        Method m=LinesCanvas.class.getDeclaredMethod("startGame",int.class,int.class,int.class,int.class);m.setAccessible(true);m.invoke(c,size,colors,balls,day);
    }
    static void turn(LinesCanvas c,int longest)throws Exception {
        Method m=LinesCanvas.class.getDeclaredMethod("completeTurn",int.class);m.setAccessible(true);m.invoke(c,longest);
    }
    static void release(LinesCanvas c,int key)throws Exception {
        Method m=LinesCanvas.class.getDeclaredMethod("keyReleased",int.class);m.setAccessible(true);m.invoke(c,key);
    }
    static void recordsAndResults()throws Exception {
        LinesCanvas c=canvas();GameStore prefs=(GameStore)get(c,"preferences");prefs.record(7,4,1,5);
        start(c,7,4,1,0);check((Integer)get(c,"startingBest")==5,"old personal best not captured");
        BallKing king=(BallKing)get(c,"king");check((Integer)get(king,"record")==5,"record marker missing");
        set(c,"score",7);set(c,"popupScore",7);turn(c,7);
        check((Boolean)get(c,"recordCelebrated")&&(Integer)get(c,"recordTicks")>0&&(Integer)get(king,"victoryTicks")>0,"record did not trigger celebration");
        check(((ProgressStore)get(c,"progress")).unlocked(8),"personal record achievement missing");
        call(c,"pushSnapshot");c.pause();LinesCanvas resume=new LinesCanvas(null);
        check((Integer)get(resume,"startingBest")==5&&(Boolean)get(resume,"recordCelebrated"),"resume changed record target or forgot celebration");
        resume.stop();set(c,"paused",false);
        int[][] board=(int[][])get(c,"board");for(int[] row:board)Arrays.fill(row,1);turn(c,0);
        check((Integer)get(c,"state")==3&&!(Boolean)get(c,"hasGame"),"game over not reached");call(c,"render");
        prefs.size=11;prefs.colors=7;prefs.balls=3;
        key(c,-6);call(c,"tick");
        check((Integer)get(c,"N")==7&&(Integer)get(c,"colorCount")==4&&(Integer)get(c,"ballsPerTurn")==1&&(Integer)get(c,"score")==0,"Retry lost previous rules");
        check(prefs.size==11&&prefs.colors==7&&prefs.balls==3,"Retry changed selected future rules");
        c.stop();System.out.println("PASS: frozen record marker, one-time celebration, resume and same-rules Retry");
    }
    static void dailyJourney()throws Exception {
        LinesCanvas c=canvas();GameStore prefs=(GameStore)get(c,"preferences");
        int mode=GameStore.index(9,5,2), games=prefs.gamesAt(mode), moves=prefs.movesAt(mode), best=prefs.best(9,5,2);
        start(c,9,5,2,20261104);int[] board=flat((int[][])get(c,"board"));int seed=(Integer)get(c,"seed");int[] colors=((int[])get(c,"nextCol")).clone();
        check(makeMove(c),"no daily move available");settle(c);
        int[] after=flat((int[][])get(c,"board"));int nextSeed=(Integer)get(c,"seed");
        call(c,"retryGame");check(Arrays.equals(board,flat((int[][])get(c,"board")))&&seed==(Integer)get(c,"seed")&&Arrays.equals(colors,(int[])get(c,"nextCol")),"daily retry did not restore fixed sequence");
        check(makeMove(c),"no repeated daily move");settle(c);check(Arrays.equals(after,flat((int[][])get(c,"board")))&&nextSeed==(Integer)get(c,"seed"),"same daily move produced a different turn");
        set(c,"score",20);turn(c,0);c.pause();LinesCanvas loaded=new LinesCanvas(null);
        check((Integer)get(loaded,"dailyDay")==20261104&&(Integer)get(loaded,"score")==20,"daily mode did not survive restart");
        ProgressStore p=(ProgressStore)get(loaded,"progress");check(p.dailyBest(20261104)==20,"daily best not persisted");
        check(prefs.gamesAt(mode)==games&&prefs.movesAt(mode)==moves&&prefs.best(9,5,2)==best,"daily game polluted normal records/statistics");
        loaded.stop();set(c,"paused",false);
        int[][] full=(int[][])get(c,"board");for(int[] row:full)Arrays.fill(row,1);turn(c,0);
        check(((ProgressStore)get(c,"progress")).unlocked(7),"daily finisher achievement missing");
        start(c,9,5,2,20261105);
        check(!Arrays.equals(board,flat((int[][])get(c,"board"))),"next day reused starting board");
        set(c,"state",0);set(c,"menuSel",6);call(c,"titleSelect");check((Integer)get(c,"state")==9,"daily menu missing");
        int[] keep=flat((int[][])get(c,"board"));key(c,-6);call(c,"tick");check((Integer)get(c,"state")==7,"daily overwrite not confirmed");
        key(c,-6);call(c,"tick");check(Arrays.equals(keep,flat((int[][])get(c,"board"))),"daily cancel replaced game");
        c.stop();
        long edge=Instant.parse("2026-11-04T23:59:59Z").toEpochMilli();
        check(ProgressStore.dayAt(edge)==20261104&&ProgressStore.dayAt(edge+1000)==20261105,"daily boundary must be UTC");
        check(!ProgressStore.validDay(20260230)&&ProgressStore.validDay(20280229),"invalid daily dates accepted");
        System.out.println("PASS: deterministic daily starts/turns/retry, UTC dates, safe confirmation and isolated persisted scores");
    }
    static String checkpointProgress() throws Exception {
        String name = System.getProperty("lines.store") + "-checkpoint";
        try {
            ProgressStore store = new ProgressStore(name);
            Method record = ProgressStore.class.getDeclaredMethod("recordProgress", int.class, int.class, int.class, int.class, int.class);
            record.setAccessible(true);
            record.invoke(store, 8, 0, 100, 20, 0);
            return new ProgressStore(name).awardProgress(4);
        } finally {
            javax.microedition.rms.RecordStore.deleteRecordStore(name);
        }
    }

    static void awardsAndControls()throws Exception {
        LinesCanvas c=canvas();ProgressStore p=(ProgressStore)get(c,"progress");p.awards=p.sizesMask=0;p.skin=0;p.vibration=false;p.save();
        start(c,9,5,2,0);
        int[][] b=(int[][])get(c,"board");for(int[] row:b)Arrays.fill(row,0);
        for(int k=1;k<=7;k++)b[4][k]=1;
        check((Boolean)call(c,"startClearIfAny"),"long line not detected");settle(c);
        check(p.unlocked(0)&&p.unlocked(1)&&!p.unlocked(2),"long line achievements confused with cross");
        for(int k=2;k<=6;k++){b[4][k]=1;b[k][4]=1;}
        check((Boolean)call(c,"startClearIfAny"),"cross not detected");settle(c);
        check(p.unlocked(2)&&p.skins()==2,"third achievement did not unlock Emerald");
        set(c,"score",500);set(c,"moves",99);set(c,"threatened",true);set(c,"popupScore",5);turn(c,5);
        check(p.unlocked(3)&&p.unlocked(4)&&p.unlocked(5)&&p.unlocked(6)&&p.skins()==3,"score/moves/comeback rewards missing");
        for(int size:new int[]{7,11}) {
            start(c,size,5,2,0);b=(int[][])get(c,"board");for(int[] row:b)Arrays.fill(row,0);
            for(int k=0;k<5;k++)b[0][k]=1;
            check((Boolean)call(c,"startClearIfAny"),"board-size achievement fixture failed");settle(c);
        }
        check(p.unlocked(9),"all-board-sizes achievement missing");
        start(c,9,5,2,0);
        int flags=p.awards;check(p.award(flags)==0&&p.awards==flags,"achievements awarded twice");
        ProgressStore reload=new ProgressStore(((GameStore)get(c,"preferences")).progressName());
        check(reload.awards==flags,"achievements did not reload");
        check(ProgressStore.validDay(p.awardDay(0)) && reload.awardDay(0)==p.awardDay(0),
              "unlock date did not persist");
        check("100/500 pts".equals(checkpointProgress()),"partial progress did not persist");
        call(c,"enterSettings");set(c,"settingsSel",9);Method adjust=LinesCanvas.class.getDeclaredMethod("adjustSetting",int.class);adjust.setAccessible(true);adjust.invoke(c,1);
        set(c,"settingsSel",10);adjust.invoke(c,1);check(p.vibration&&p.skin==1,"new settings did not change");
        call(c,"back");check(!p.vibration&&p.skin==0,"Back did not cancel vibration/skin");
        call(c,"enterSettings");set(c,"settingsSel",9);adjust.invoke(c,1);set(c,"settingsSel",10);adjust.invoke(c,1);call(c,"saveSettings");
        reload=new ProgressStore(((GameStore)get(c,"preferences")).progressName());check(reload.vibration&&reload.skin==1,"new settings did not persist");
        set(c,"state",1);set(c,"curC",4);set(c,"curR",4);set(c,"resetKeys",false);set(c,"keysPrev",0);
        key(c,'6');call(c,"tick");check((Integer)get(c,"curC")==5,"numeric direction did not move once");
        // Repeated native keyPressed events must not bypass the initial delay.
        key(c,'6');
        for(int i=0;i<7;i++)call(c,"tick");check((Integer)get(c,"curC")==5,"held key repeated before delay");
        call(c,"tick");check((Integer)get(c,"curC")==6,"held key did not repeat after delay");
        release(c,'6');call(c,"tick");for(int i=0;i<10;i++)call(c,"tick");check((Integer)get(c,"curC")==6,"released key kept repeating");
        key(c,'4');release(c,'4');call(c,"tick");check((Integer)get(c,"curC")==5,"short tap between frames was lost");
        c.pause();key(c,'6');set(c,"paused",false);set(c,"resetKeys",true);call(c,"tick");check((Integer)get(c,"curC")==5,"paused key leaked into game");
        set(c,"curC",2);key(c,'6');call(c,"tick");
        for(int i=0;i<32;i++)key(c,'x');release(c,'6');call(c,"tick");
        for(int i=0;i<12;i++)call(c,"tick");check((Integer)get(c,"curC")==3,"full input queue lost key release");
        c.stop();System.out.println("PASS: real clear achievements, cosmetic unlocks, Save/Back, numeric tap/hold/release/pause");
    }
    static void vibrationRequests()throws Exception {
        LinesCanvas c=canvas();start(c,9,5,2,0);
        c.setDisplay(javax.microedition.lcdui.Display.getDisplay(new LinesMidlet()));
        ProgressStore p=(ProgressStore)get(c,"progress");p.vibration=true;
        int baseline=(Integer)get(c,"startingBest");set(c,"score",baseline+5);
        Mobile.vibrationDuration=0;turn(c,5);check(Mobile.vibrationDuration==180,"new best must request record vibration");
        Mobile.vibrationDuration=0;set(c,"score",baseline+10);turn(c,5);check(Mobile.vibrationDuration==50,"line must request short vibration");
        p.vibration=false;Mobile.vibrationDuration=0;turn(c,5);check(Mobile.vibrationDuration==0,"disabled vibration still requested a pulse");
        p.vibration=true;c.pause();
        Method pulse=LinesCanvas.class.getDeclaredMethod("pulse",int.class);pulse.setAccessible(true);pulse.invoke(c,180);
        check(Mobile.vibrationDuration==0,"paused game vibrated");c.stop();pulse.invoke(c,180);check(Mobile.vibrationDuration==0,"stopped game vibrated");
        System.out.println("PASS: real MIDP vibration requests for clear/record, mute and pause/stop");
    }
    static void oldSaveAndProgressRecovery()throws Exception {
        LinesCanvas c=canvas();start(c,9,5,2,0);SessionStore sessions=(SessionStore)get(c,"sessionStore");SavedGame game=sessions.load();
        // Convert current payload into the previous exact byte layout and checksum.
        String name=((GameStore)get(c,"preferences")).sessionName();
        javax.microedition.rms.RecordStore rs=javax.microedition.rms.RecordStore.openRecordStore(name,false);byte[] now=rs.getRecord(1);byte[] old=Arrays.copyOf(now,now.length-10);old[3]=1;
        int hash=0x811C9DC5;for(int i=0;i<old.length-4;i++)hash=(hash^(old[i]&255))*0x01000193;
        int end=old.length-4;old[end]=(byte)(hash>>>24);old[end+1]=(byte)(hash>>>16);old[end+2]=(byte)(hash>>>8);old[end+3]=(byte)hash;
        rs.setRecord(1,old,0,old.length);rs.closeRecordStore();SavedGame migrated=sessions.load();
        check(migrated!=null&&migrated.dailyDay==0&&migrated.score==game.score&&Arrays.equals(migrated.cells,game.cells),"old session failed to migrate");
        String progressName=((GameStore)get(c,"preferences")).progressName();rs=javax.microedition.rms.RecordStore.openRecordStore(progressName,true);
        rs.setRecord(1,new byte[]{1,2,3},0,3);rs.closeRecordStore();ProgressStore corrupt=new ProgressStore(progressName);
        check(!corrupt.storageOk&&corrupt.awards==0&&corrupt.skin==0,"corrupt progress partially loaded");corrupt.save();check(new ProgressStore(progressName).storageOk,"progress could not recover");
        c.stop();String history=System.getProperty("lines.store")+"-hist";
        try{javax.microedition.rms.RecordStore.deleteRecordStore(history);}catch(Exception ignored){}
        ProgressStore daily=new ProgressStore(history);
        for(int day=1;day<=20;day++)daily.recordDaily(20261100+day,day);
        check(daily.dailyBest(20261101)==0&&daily.dailyBest(20261120)==20,"daily history did not keep newest 14 dates");
        daily.recordDaily(20261101,999);check(daily.dailyBest(20261120)==20&&daily.dailyBest(20261101)==0,"old resumed game displaced recent history");
        daily.recordDaily(20261120,1);check(daily.dailyBest(20261120)==20,"lower daily score replaced record");
        javax.microedition.rms.RecordStore.deleteRecordStore(history);
        System.out.println("PASS: previous session migration and corrupt optional progression recovery");
    }
    public static void main(String[] args) {
        try {
            Mobile.minLogLevel=Mobile.LOG_ERROR;MobilePlatform platform=new MobilePlatform(240,320);Mobile.setPlatform(platform,()->{});
            check(platform.load(new File("dist/lines.jar").toURI().toString()),"cannot load JAR");
            String base=System.getProperty("lines.store");for(String n:new String[]{base,base+"-game",base+"-progress"})try{javax.microedition.rms.RecordStore.deleteRecordStore(n);}catch(Exception ignored){}
            new LinesMidlet();recordsAndResults();dailyJourney();awardsAndControls();vibrationRequests();oldSaveAndProgressRecovery();
            System.out.println("ALL PROGRESSION CHECKS PASSED");System.exit(0);
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
