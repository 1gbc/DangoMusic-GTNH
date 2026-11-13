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
            final long beforeFavorite=ack;
            java.util.concurrent.FutureTask<JsonObject> favorite=new java.util.concurrent.FutureTask<>(()->s.request("favoriteAdd","123",0));
            Thread favoriteWorker=new Thread(favorite,"Favorite request test");
            favoriteWorker.start();
            JsonObject command=null;
            for(int i=0;i<40&&command==null;i++){
                JsonElement pending=post(s,"/sync",key,"{\"ack\":"+beforeFavorite+"}",200).get("command");
                if(pending.isJsonObject())command=pending.getAsJsonObject();
                else Thread.sleep(25);
            }
            check(command!=null&&command.get("name").getAsString().equals("query"),"Favorite write is queued");
            s.playSong("456",false);
            check(post(s,"/sync",key,"{\"ack\":"+ack+"}",200).getAsJsonObject("command").equals(command),
                "Changing songs must preserve the pending favorite write");
            ack=command.get("id").getAsLong();
            JsonObject next=post(s,"/sync",key,"{\"ack\":"+ack+",\"commandResult\":{\"id\":"+ack+",\"favorite\":true}}",200);
            check(favorite.get(2,java.util.concurrent.TimeUnit.SECONDS).get("favorite").getAsBoolean(),"The confirmed favorite reaches its caller");
            check(next.getAsJsonObject("command").get("name").getAsString().equals("load"),"The newly selected song still loads");
            s.reconnect();post(s,"/sync",key,"{\"ack\":0}",403);
            System.out.println("Game bridge protocol checks passed");
        } finally {s.close();}
    }
}
