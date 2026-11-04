import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Run with: java tools/AntBootstrap.java package (JDK 17+). */
class AntBootstrap {
    private static final String[][] ARTIFACTS = {
        {"org/apache/ant/ant/1.10.15/ant-1.10.15.jar", "763acda4a69588c9ea8817a952851ff0c2fc4bffa1d081c2565dc407f29d5794"},
        {"org/apache/ant/ant-launcher/1.10.15/ant-launcher-1.10.15.jar", "5c8551990307a032336d98ddaed549a39a689f07d4d4c6b950601bf22b3d6a1b"},
        {"org/eclipse/jdt/ecj/3.26.0/ecj-3.26.0.jar", "ac0ba5876eaf7ebb47749a0d1be179c51f194b9dd0b875d1c09e1b530f5a2db5"}
    };

    private static void verify(Path jar, String expected) throws Exception {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        if (!expected.equals(actual)) throw new IOException("Checksum mismatch: " + jar);
    }

    public static void main(String[] args) {
        try { System.exit(run(args)); }
        catch (Exception e) { System.err.println("Ant launcher: " + e.getMessage()); System.exit(1); }
    }

    private static int run(String[] args) throws Exception {
        int first = 0;
        Path root = Path.of("").toAbsolutePath();
        if (args.length >= 2 && args[0].equals("--project-root")) {
            root = Path.of(args[1]).toAbsolutePath(); first = 2;
        }
        if (!Files.isRegularFile(root.resolve("build.xml"))) throw new IOException("Run from the project root or specify --project-root PATH");
        Path cache = root.resolve("tools/build"); Files.createDirectories(cache);
        List<String> jars = new ArrayList<>();
        for (String[] artifact : ARTIFACTS) {
            Path jar = cache.resolve(artifact[0].substring(artifact[0].lastIndexOf('/') + 1));
            if (!Files.exists(jar)) {
                System.out.println("Downloading " + jar.getFileName());
                Path temp = Files.createTempFile(cache, jar.getFileName() + ".", ".download");
                try {
                    var connection = URI.create("https://repo.maven.apache.org/maven2/" + artifact[0]).toURL().openConnection();
                    connection.setConnectTimeout(60_000); connection.setReadTimeout(60_000);
                    try (var input = connection.getInputStream()) { Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING); }
                    verify(temp, artifact[1]);
                    Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temp); }
            }
            verify(jar, artifact[1]); jars.add(jar.toString());
        }
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-cp", String.join(File.pathSeparator, jars), "org.apache.tools.ant.Main", "-f", root.resolve("build.xml").toString()));
        command.addAll(Arrays.asList(args).subList(first, args.length));
        Process ant = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
        Thread shutdown = new Thread(ant::destroy);
        Runtime.getRuntime().addShutdownHook(shutdown);
        try { return ant.waitFor(); }
        finally { Runtime.getRuntime().removeShutdownHook(shutdown); }
    }
}
