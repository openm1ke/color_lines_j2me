package lines;

import java.io.*;
import java.util.Calendar;
import java.util.TimeZone;
import javax.microedition.rms.*;

/** Optional progression, cosmetics and a bounded independent daily leaderboard. */
public final class ProgressStore {
    private static final int LEGACY_MAGIC = 0x4C505201;
    private static final int MAGIC = 0x4C505202;
    private final int[] awardDays = new int[10];
    private int bestLine, bestScore, bestMoves;
    public static final String[] TITLES = {"First line", "Magnificent seven", "Crossfire", "First hundred", "Five hundred",
        "Patient player", "Comeback", "Daily finisher", "Personal best", "All boards"};
    public static final String[] DETAILS = {"Clear a line", "Clear 7+ in one line", "Clear 2 lines at once", "Reach 100 points", "Reach 500 points",
        "Play 100 turns", "Clear after <=5 free", "Score and finish daily", "Beat a previous best", "Clear on all 3 sizes"};
    private final String name;
    public int awards, sizesMask, skin;
    public boolean vibration, storageOk = true;
    private final int[] dates = new int[14], scores = new int[14];

    public ProgressStore(String name) { this.name = name; load(); }
    public static int today() { return dayAt(System.currentTimeMillis()); }
    public static int dayAt(long millis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT")); c.setTime(new java.util.Date(millis));
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }
    public static boolean validDay(int day) {
        if (day < 20000101 || day > 99991231) return false;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        c.set(Calendar.YEAR, day / 10000); c.set(Calendar.MONTH, day / 100 % 100 - 1); c.set(Calendar.DAY_OF_MONTH, day % 100);
        return c.get(Calendar.YEAR) == day / 10000 && c.get(Calendar.MONTH) + 1 == day / 100 % 100 && c.get(Calendar.DAY_OF_MONTH) == day % 100;
    }
    public static String dayLabel(int day) {
        String s = "" + day;
        return s.length() == 8 ? s.substring(0,4) + "-" + s.substring(4,6) + "-" + s.substring(6) : "Unknown date";
    }
    public static int dailySeed(int day) {
        int v = day ^ 0x4C494E45; v ^= v >>> 16; v *= 0x45D9F3B; v ^= v >>> 16;
        return v & 0x7fffffff;
    }
    public int count() { int count = 0; for (int i=0;i<TITLES.length;i++) if ((awards & (1<<i)) != 0) count++; return count; }
    public int skins() { int n = count(); return n >= 6 ? 3 : n >= 3 ? 2 : 1; }
    public boolean unlocked(int i) { return (awards & (1<<i)) != 0; }
    public int awardDay(int i) { return unlocked(i) ? awardDays[i] : 0; }
    public String awardProgress(int i) {
        switch (i) {
            case 0: return bestLine + "/5 balls";
            case 1: return bestLine + "/7 balls";
            case 3: return bestScore + "/100 pts";
            case 4: return bestScore + "/500 pts";
            case 5: return bestMoves + "/100 turns";
            case 9: return ((sizesMask & 1) + (sizesMask >> 1 & 1) + (sizesMask >> 2 & 1)) + "/3 boards";
            default: return "0/1";
        }
    }
    public int award(int bits) { return recordProgress(bits, 0, 0, 0, sizesMask); }
    int recordProgress(int bits, int line, int score, int moves, int sizes) {
        int added = bits & ~awards & 1023;
        line = Math.max(bestLine, Math.min(line, 7));
        score = Math.max(bestScore, Math.min(score, 500));
        moves = Math.max(bestMoves, Math.min(moves, 100));
        if (added == 0 && sizes == sizesMask && line == bestLine && score == bestScore && moves == bestMoves) return 0;
        bestLine = line; bestScore = score; bestMoves = moves; sizesMask = sizes;
        if (added != 0) {
            int day = today();
            for (int i = 0; i < awardDays.length; i++) if ((added & (1 << i)) != 0) awardDays[i] = day;
            awards |= added;
        }
        save();
        return added;
    }
    public int dailyBest(int day) { for (int i=0;i<dates.length;i++) if (dates[i]==day) return scores[i]; return 0; }
    public int dateAt(int i) { return dates[i]; }
    public int scoreAt(int i) { return scores[i]; }
    public void recordDaily(int day, int score) {
        if (!validDay(day) || score <= 0) return;
        int at = -1;
        for (int i=0;i<dates.length;i++) if (dates[i]==day) { at=i; break; }
        if (at >= 0 && score <= scores[at]) return;
        if (at < 0) {
            at=0; for (int i=1;i<dates.length;i++) if (dates[i]<dates[at]) at=i;
            // A resumed old game must not evict a newer challenge.
            if (dates[at] > day) return;
        }
        dates[at]=day; scores[at]=score; save();
    }
    private void load() {
        RecordStore rs=null;
        try {
            rs=RecordStore.openRecordStore(name,true); if (rs.getNumRecords()==0) return;
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            int version = in.readInt();
            if (version != MAGIC && version != LEGACY_MAGIC) throw new IOException("Invalid progression version");
            int a=in.readInt(),m=in.readInt(),s=in.readUnsignedByte();boolean v=in.readBoolean();
            int[] d=new int[14],b=new int[14];
            if ((a & ~1023)!=0 || (m & ~7)!=0 || s>2) throw new IOException("Invalid progression");
            for (int i=0;i<14;i++) {
                d[i]=in.readInt(); b[i]=in.readInt();
                if (b[i]<0 || (d[i]==0 && b[i]!=0) || (d[i]!=0 && !validDay(d[i]))) throw new IOException("Invalid daily record");
                for (int j=0;j<i;j++) if (d[i]!=0 && d[i]==d[j]) throw new IOException("Duplicate date");
            }
            int[] earned = new int[10];
            int line = 0, score = 0, moves = 0;
            if (version == MAGIC) {
                for (int i = 0; i < earned.length; i++) {
                    earned[i] = in.readInt();
                    if (earned[i] != 0 && ((a & (1 << i)) == 0 || !validDay(earned[i]))) throw new IOException("Invalid award date");
                }
                line = in.readUnsignedByte(); score = in.readUnsignedShort(); moves = in.readUnsignedByte();
                if (line > 7 || score > 500 || moves > 100) throw new IOException("Invalid award progress");
            }
            if (in.available()!=0) throw new IOException("Extra progression data");
            System.arraycopy(earned, 0, awardDays, 0, earned.length);
            bestLine = line; bestScore = score; bestMoves = moves;
            awards=a; sizesMask=m; skin=s; vibration=v;
            if (skin>=skins()) skin=0;
            System.arraycopy(d,0,dates,0,14); System.arraycopy(b,0,scores,0,14);
        } catch (Exception ignored) { storageOk=false; }
        finally { if(rs!=null)try {rs.closeRecordStore();}catch(Exception ignored){} }
    }
    public void save() {
        RecordStore rs=null;
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(MAGIC);out.writeInt(awards);out.writeInt(sizesMask);out.writeByte(skin);out.writeBoolean(vibration);
            for (int i=0;i<14;i++){out.writeInt(dates[i]);out.writeInt(scores[i]);}
            for (int i = 0; i < awardDays.length; i++) out.writeInt(awardDay(i));
            out.writeByte(bestLine); out.writeShort(bestScore); out.writeByte(bestMoves);
            out.flush();byte[] data=bytes.toByteArray();
            rs=RecordStore.openRecordStore(name,true);
            if(rs.getNumRecords()==0)rs.addRecord(data,0,data.length);else rs.setRecord(1,data,0,data.length);
            storageOk=true;
        } catch(Exception ignored){storageOk=false;}
        finally {if(rs!=null)try{rs.closeRecordStore();}catch(Exception ignored){}}
    }
}
