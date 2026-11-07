import me.sorihiko.dangomusicgtnh.music.AppleWebServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
public class GameNativeCheck {
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders","true");
        AppleWebServer s=new AppleWebServer();
        try {
            String key=s.pairingLink().split("#")[1];
            HttpURLConnection c=(HttpURLConnection)new URL(s.origin()+"/pair").openConnection();
            c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Origin","dango-native://player");
            c.setRequestProperty("Authorization","Bearer "+key);
            c.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            if(c.getResponseCode()!=200)throw new AssertionError("Owned native player must pair with the same one-time credential");
            c.disconnect();System.out.println("Native player transport check passed");
        }finally{s.close();}
    }
}
