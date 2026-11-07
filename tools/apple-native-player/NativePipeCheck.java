import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
/** Match the game's ProcessBuilder pipe, with invalid fixture data that never opens a window. */
public class NativePipeCheck {
    public static void main(String[] args) throws Exception {
        Process child=new ProcessBuilder(args[0]).start();
        child.getOutputStream().write("{\"link\":\"https://invalid.example/\",\"profile\":\"测试路径\"}\n".getBytes(StandardCharsets.UTF_8));
        child.getOutputStream().close();
        if(!child.waitFor(5,TimeUnit.SECONDS))throw new AssertionError("Headless bootstrap did not finish");
        if(child.exitValue()!=7)throw new AssertionError("Expected validated rejection 7, got "+child.exitValue());
        System.out.println("Game-to-native UTF-8 pipe regression passed");
    }
}
