import me.sorihiko.dangomusicgtnh.music.AppleWebServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;

public class GameBridgeCheck {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static JsonObject post(AppleWebServer s, String path, String key, String json, int expected) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(s.origin()+path).openConnection();
        c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Origin","chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Content-Type","application/json");
        c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        check(c.getResponseCode()==expected,"Unexpected HTTP status: "+c.getResponseCode());
        if(expected!=200){c.disconnect();return new JsonObject();}
        JsonObject result=new JsonParser().parse(new java.io.InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8)).getAsJsonObject();c.disconnect();return result;
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders","true");
        AppleWebServer s=new AppleWebServer();
        try {
            String code=s.pairingLink().split("#")[1];
            post(s,"/pair","wrong","{}",403);
            String key=post(s,"/pair",code,"{}",200).get("session").getAsString();
            post(s,"/pair",code,"{}",403);
            String state="{\"id\":\"123\",\"title\":\"Song\",\"artist\":\"Singer\",\"available\":true,\"playing\":true,\"positionMs\":92000,\"durationMs\":240000,\"cookie\":\"secret\"}";
            post(s,"/sync",key,"{\"ack\":0,\"state\":"+state+"}",200);
            check(s.snapshot().get("positionMs").getAsLong()==92000,"Actual clock");
            check(!s.snapshot().has("cookie"),"Credentials excluded");
            s.command("stop",null);
            JsonObject stop=post(s,"/sync",key,"{\"ack\":0,\"state\":"+state+"}",200);
            long ack=stop.getAsJsonObject("command").get("id").getAsLong();
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+state+"}",200);
            check(s.snapshot()==null,"Stop cannot be revived by late telemetry");
            s.command("play",null);
            JsonObject play=post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+state+"}",200);
            ack=play.getAsJsonObject("command").get("id").getAsLong();
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+state+"}",200);
            check(s.snapshot()!=null,"Explicit play resumes");
            s.reconnect();post(s,"/sync",key,"{\"ack\":0}",403);
            System.out.println("Game bridge protocol checks passed");
        } finally {s.close();}
    }
}
