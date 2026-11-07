import me.sorihiko.dangomusicgtnh.music.*;
import com.google.gson.*;
import java.util.*;
public class AppleQueueCheck {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        System.setProperty("sun.net.http.allowRestrictedHeaders","true");
        AppleWebServer s=new AppleWebServer();
        java.lang.reflect.Field field=AppleWebPlayer.class.getDeclaredField("server");field.setAccessible(true);field.set(null,s);
        try{
            String key=GameBridgeCheck.post(s,"/pair",s.pairingLink().split("#")[1],"{}",200).get("session").getAsString();
            String state="{\"authorized\":true,\"available\":true,\"playing\":true,\"id\":\"123\",\"title\":\"One\",\"positionMs\":65000,\"durationMs\":240000}";
            GameBridgeCheck.post(s,"/sync",key,"{\"ack\":0,\"state\":"+state+"}",200);
            MusicSources.INSTANCE.setSelected(MusicSource.APPLE);MusicPlayer p=MusicPlayer.INSTANCE;
            NeteaseTrack a=new NeteaseTrack(-1,"One",Collections.singletonList("Singer"),240000,"",MusicSource.APPLE,"123");
            NeteaseTrack b=new NeteaseTrack(-2,"Two",Collections.singletonList("Singer"),240000,"",MusicSource.APPLE,"456");
            p.playQueue(Arrays.asList(a,b),0);
            JsonObject load=GameBridgeCheck.post(s,"/sync",key,"{\"ack\":0}",200).getAsJsonObject("command");
            check(load.get("name").getAsString().equals("load"),"Selecting a search result loads the SDK song");
            long ack=load.get("id").getAsLong();
            GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+state+"}",200);
            AppleWebPlayer.INSTANCE.tick();
            p.selectMode(MusicPlayer.Mode.SHUFFLE);
            check(p.getCurrent()==a&&p.getPositionMs()==65000,"Shuffle retains song and progress");
            check(p.getQueue().size()==2&&p.getQueue().get(0)==a,"Actual shuffled queue is visible");
            p.selectMode(MusicPlayer.Mode.SEQUENTIAL);
            check(p.getCurrent()==a&&p.getPositionMs()==65000&&p.getQueue().equals(Arrays.asList(a,b)),"Sequential restores order without restarting the song");
            p.next();check(p.getCurrent()==b,"Next follows game queue");
            p.selectMode(MusicPlayer.Mode.REPEAT_ONE);p.finishAppleTrack();
            check(p.getCurrent()==b,"Repeat keeps current song");
            p.stop();AppleWebPlayer.INSTANCE.tick();check(!p.getActive()&&p.getCurrent()==null,"Stop remains idle after tick");
            System.out.println("Apple game queue/mode/stop checks passed");
        }finally{field.set(null,null);s.close();}
    }
}
