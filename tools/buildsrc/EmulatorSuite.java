import java.io.File;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Separate visible JVMs avoid FreeJ2ME's live-resize buffer bug. */
public class EmulatorSuite {
    public static void main(String[] args) throws Exception {
        Path root=Path.of("").toAbsolutePath();
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String cp=root.resolve("build/tests")+File.pathSeparator+root.resolve("tools/freej2me.jar");
        for (int[] dimensions : new int[][]{{240,320},{176,208},{320,240}}) {
            int width=dimensions[0],height=dimensions[1];
            Path runtime=root.resolve("build/emulator-check/runtime-"+width+"x"+height);
            Path config=runtime.resolve("config/Lines/game.conf");
            Files.createDirectories(config.getParent());
            Files.createDirectories(runtime.resolve("freej2me_system"));
            Files.writeString(config,"""
                backlightcolor:Disabled
                compatfantasyzonefix:off
                compatignorevolumechanges:off
                compatimmediaterepaints:off
                compatoverrideplatchecks:on
                compatsiemensfriendlydrawing:off
                compattranstooriginonreset:off
                dojaversion:200
                fontoffset:0
                fps:0
                fpshack:Disabled
                phone:Standard
                rotate:0
                scrheight:%d
                scrwidth:%d
                sound:on
                soundfont:Default
                spdhackm3ghalfres:off
                spdhacknoalpha:off
                textfont:Default
                """.formatted(height,width));
            System.out.println("FreeJ2ME visible check: "+width+"x"+height);
            Process child=new ProcessBuilder(java,"-Dfile.encoding=ISO_8859_1","-Dlines.store=lines-ui-"+width+"x"+height,
                "-Dlines.project="+root,"-cp",cp,"EmulatorCheck",""+width,""+height).directory(runtime.toFile()).inheritIO().start();
            Thread shutdown=new Thread(child::destroy);Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                if (!child.waitFor(60,TimeUnit.SECONDS)) {
                    child.destroyForcibly().waitFor();throw new IllegalStateException("Emulator timed out: "+width+"x"+height);
                }
                if (child.exitValue()!=0) throw new IllegalStateException("Emulator check failed: "+width+"x"+height);
            } finally { Runtime.getRuntime().removeShutdownHook(shutdown); }
        }
        System.out.println("ALL VISIBLE EMULATOR CHECKS PASSED");
    }
}
