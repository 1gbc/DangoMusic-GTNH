import me.sorihiko.dangomusicgtnh.music.AppleWebServer;
import com.google.gson.*;
import java.util.concurrent.*;
public class GameRpcCheck {
    public static void main(String[] args)throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders","true");
        AppleWebServer s=new AppleWebServer();ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            String key=GameBridgeCheck.post(s,"/pair",s.pairingLink().split("#")[1],"{}",200).get("session").getAsString();
            Future<JsonObject> result=worker.submit(()->s.request("search","test",0));
            JsonObject c=null;
            for(int i=0;i<100;i++){
                JsonObject sync=GameBridgeCheck.post(s,"/sync",key,"{\"ack\":0}",200);
                if(!sync.get("command").isJsonNull()){c=sync.getAsJsonObject("command");break;}
                Thread.sleep(10);
            }
            if(c==null)throw new AssertionError("RPC never queued");
            long id=c.get("id").getAsLong();
            GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+id+",\"commandResult\":{\"id\":"+id+",\"items\":[{\"id\":\"123\",\"title\":\"Song\",\"token\":\"secret\"}],\"hasMore\":false}}",200);
            JsonObject row=result.get(2,TimeUnit.SECONDS).getAsJsonArray("items").get(0).getAsJsonObject();
            if(!row.get("title").getAsString().equals("Song")||row.has("token"))throw new AssertionError("RPC metadata must be filtered");
            Future<JsonObject> cancelled=worker.submit(()->s.request("songs","",0));
            for(int i=0;i<100;i++){
                JsonObject sync=GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+id+"}",200);
                if(!sync.get("command").isJsonNull())break;
                Thread.sleep(10);
            }
            s.command("stop",null);
            try{cancelled.get(2,TimeUnit.SECONDS);throw new AssertionError("Stop must cancel pending browsing");}catch(ExecutionException expected){}
            System.out.println("Metadata RPC and stop cancellation checks passed");
        }finally{s.close();worker.shutdownNow();}
    }
}
