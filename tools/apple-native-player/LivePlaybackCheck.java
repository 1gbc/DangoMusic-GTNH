import me.sorihiko.dangomusicgtnh.music.AppleWebServer;
import com.google.gson.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Optional local integration check, not a simulated test; never prints credentials or library data. */
public class LivePlaybackCheck {
    public static void main(String[] args) throws Exception {
        AppleWebServer server=new AppleWebServer();Process child=null;
        try {
            child=new ProcessBuilder(args[0]).start();
            JsonObject bootstrap=new JsonObject();bootstrap.addProperty("link",server.pairingLink());
            bootstrap.addProperty("profile",args[1]);bootstrap.addProperty("parentPid",Long.parseLong(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]));
            child.getOutputStream().write((bootstrap.toString()+"\n").getBytes(StandardCharsets.UTF_8));child.getOutputStream().close();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            while(!server.authorized()&&System.nanoTime()<deadline&&child.isAlive())Thread.sleep(100);
            System.out.println("LIVE connected="+server.connected()+" authorized="+server.authorized()+" state="+server.authState());
            if(!server.error().isEmpty())System.out.println("LIVE readiness="+server.error());
            if(args.length>3&&args[3].equals("--recent-only")) {
                if(!server.authorized()){System.out.println("LIVE recent check: session not ready");return;}
                JsonObject recent=server.request("recent","",0);
                System.out.println("LIVE recent items="+recent.getAsJsonArray("items").size()+" error="+recent.get("error").getAsString());
                return;
            }
            if(args.length>3&&args[3].equals("--restore-only"))return;
            if(!server.authorized()) {
                server.command("login",null);System.out.println("LIVE awaiting official account authorization");
                deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
                while(!server.authorized()&&System.nanoTime()<deadline&&child.isAlive())Thread.sleep(100);
                System.out.println("LIVE authorization="+server.authorized());
                if(!server.authorized())return;
            }
            JsonObject search=server.request("search",args[2],0);
            if(!search.get("error").getAsString().isEmpty()){System.out.println(search.get("error").getAsString());return;}
            JsonObject selected=null;
            for(JsonElement item:search.getAsJsonArray("items"))if(item.getAsJsonObject().get("title").getAsString().equalsIgnoreCase(args[2])){selected=item.getAsJsonObject();break;}
            System.out.println("LIVE catalogResults="+search.getAsJsonArray("items").size());
            if(selected==null&&search.getAsJsonArray("items").size()>0)selected=search.getAsJsonArray("items").get(0).getAsJsonObject();
            if(selected==null){System.out.println("LIVE no catalog test song found");return;}
            server.playSong(selected.get("playId").getAsString(),selected.get("library").getAsBoolean());
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(70);String error="";long progress=0;
            while(System.nanoTime()<deadline){
                if(!server.error().isEmpty())error=server.error();
                JsonObject state=server.snapshot();if(state!=null)progress=Math.max(progress,state.get("positionMs").getAsLong());
                if(progress>3000)break;Thread.sleep(50);
            }
            System.out.println("LIVE progressMs="+progress+" error="+error);
            if(progress>3000&&selected.get("durationMs").getAsLong()>100000) {
                server.command("seek",90.0);long seekProgress=0;deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(System.nanoTime()<deadline){
                    JsonObject state=server.snapshot();if(state!=null&&state.get("playing").getAsBoolean())seekProgress=Math.max(seekProgress,state.get("positionMs").getAsLong());
                    if(seekProgress>93000)break;Thread.sleep(50);
                }
                System.out.println("LIVE afterSeekProgressMs="+seekProgress);
            }
        } finally {
            server.command("stop",null);Thread.sleep(1000);server.close();
            // Closing the bridge lets the component close its WebView normally,
            // flushing the profile instead of terminating it immediately after login.
            if(child!=null&&!child.waitFor(14,TimeUnit.SECONDS)){child.destroy();child.waitFor(3,TimeUnit.SECONDS);if(child.isAlive())child.destroyForcibly();}
        }
    }
}
