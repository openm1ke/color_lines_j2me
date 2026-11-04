import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Distribution validation uses only the JDK; game regressions run separately. */
public class DistributionCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static byte[] read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name);
        check(entry != null, "Missing packaged resource: " + name);
        try (var in = jar.getInputStream(entry)) { return in.readAllBytes(); }
    }
    private static boolean tag(byte[] bytes, int offset, String value) {
        return bytes.length >= offset + value.length() && new String(bytes, offset, value.length(), StandardCharsets.US_ASCII).equals(value);
    }
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("local.properties"))) { props.load(reader); }
        Map<String,String> jad = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            int colon = line.indexOf(':');
            if (colon >= 0) jad.put(line.substring(0,colon),line.substring(colon+1).trim());
        }
        String profile = "MIDP-" + props.getProperty("midp.profile");
        check(Long.parseLong(jad.get("MIDlet-Jar-Size")) == Files.size(path), "JAD size does not match JAR");
        check(profile.equals(jad.get("MicroEdition-Profile")), "Wrong JAD profile");
        try (JarFile jar = new JarFile(path.toFile())) {
            check(profile.equals(jar.getManifest().getMainAttributes().getValue("MicroEdition-Profile")), "Wrong manifest profile");
            for (String name : List.of("img/lines2.png","lines/LinesMidlet.class","lines/GameStore.class","lines/Soundtrack.class")) read(jar,name);
            check(tag(read(jar,"music/minigames.mid"),0,"MThd"), "Invalid MIDI header");
            for (String name : List.of("music/LICENSE.txt","sfx/LICENSE.txt"))
                check(new String(read(jar,name),StandardCharsets.UTF_8).contains("CC0"), "Missing CC0 attribution: " + name);
            for (int n=1;n<=3;n++) {
                String name="sfx/pop"+n+".wav";
                byte[] pcm=read(jar,name);
                check(Arrays.equals(pcm,Files.readAllBytes(Path.of("res",name))),"Packaged WAV changed: "+name);
                check(tag(pcm,0,"RIFF")&&tag(pcm,8,"WAVE")&&tag(pcm,12,"fmt ")&&tag(pcm,36,"data"),"FreeJ2ME requires a canonical 44-byte PCM header: "+name);
                ByteBuffer wav=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
                check(wav.getInt(16)==16&&wav.getInt(40)==pcm.length-44,"Invalid WAV chunk sizes: "+name);
                check(wav.getShort(20)==1&&wav.getShort(22)==1&&wav.getInt(24)==16000&&wav.getShort(34)==16,"Expected mono 16kHz 16-bit PCM: "+name);
                double duration=(pcm.length-44)/32000.0;
                check(duration>0.02&&duration<0.3,"Invalid pop duration: "+name);
            }
            for (var entry : Collections.list(jar.entries())) {
                String name=entry.getName();
                check(!name.startsWith("java/")&&!name.startsWith("javax/"),"API stubs leaked into game: "+name);
                if (name.endsWith(".class")) {
                    byte[] data=read(jar,name);
                    check(ByteBuffer.wrap(data).getShort(6)==45,"Not Java 1.1 bytecode: "+name);
                    if (name.equals("lines/LinesCanvas.class"))
                        check(new String(data,StandardCharsets.ISO_8859_1).contains("StackMap"),"Missing CLDC preverification");
                }
            }
        }
        System.out.println("PASS: JAR/JAD metadata, resources, API isolation and CLDC preverification");
    }
}
