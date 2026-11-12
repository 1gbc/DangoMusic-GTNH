import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import me.sorihiko.dangomusicgtnh.music.BundledApplePlayer;

/** Run against the final mod JAR with a fresh test directory as the first argument. */
public class BundledPlayerCheck {
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectory(Path.of(args[0]));
        Path executable = BundledApplePlayer.INSTANCE.install(root);
        Path component = executable.getParent();
        Path library = component.resolve("DangoApplePlayer.dll");
        byte[] original = Files.readAllBytes(library);
        Files.writeString(library, "incomplete");
        if (!executable.equals(BundledApplePlayer.INSTANCE.install(root)) ||
            !Arrays.equals(original, Files.readAllBytes(library))) {
            throw new AssertionError("Reinstall must repair a damaged component in the same directory");
        }
        Path legacy = Files.writeString(root.resolve("DangoApplePlayer.exe"), "legacy");
        if (!executable.equals(BundledApplePlayer.INSTANCE.install(root)) ||
            !Files.readString(legacy).equals("legacy")) {
            throw new AssertionError("A legacy component must not override the bundled component");
        }
        if (!Files.isRegularFile(component.resolve("licenses/WebView2-SDK-NOTICE.txt"))) {
            throw new AssertionError("The bundled WebView2 notice must be installed with the component");
        }
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process player = new ProcessBuilder(executable.toString(), "--self-test")
                .directory(component.toFile()).start();
            if (!player.waitFor(10, TimeUnit.SECONDS) || player.exitValue() != 0) {
                throw new AssertionError("The bundled Windows component could not start with WebView2");
            }
        }
        System.out.println("Bundled Apple Music install, repair and platform checks passed");
    }
}
