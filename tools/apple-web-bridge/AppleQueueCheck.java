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
            JsonObject volume=GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+"}",200).getAsJsonObject("command");
            ack=volume.get("id").getAsLong();
            p.selectMode(MusicPlayer.Mode.SHUFFLE);
            check(p.getCurrent()==a&&p.getPositionMs()==65000,"Shuffle retains song and progress");
            check(p.getQueue().size()==2&&p.getQueue().get(0)==a,"Actual shuffled queue is visible");
            p.selectMode(MusicPlayer.Mode.SEQUENTIAL);
            check(p.getCurrent()==a&&p.getPositionMs()==65000&&p.getQueue().equals(Arrays.asList(a,b)),"Sequential restores order without restarting the song");
            String pausedNearEnd=state.replace("\"playing\":true","\"playing\":false").replace("65000","239990");
            GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+pausedNearEnd+"}",200);
            AppleWebPlayer.INSTANCE.tick();check(p.getCurrent()==a,"Pausing at the end does not advance");
            String ended="{\"authorized\":true,\"available\":true,\"ended\":true}";
            GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+ended+"}",200);
            AppleWebPlayer.INSTANCE.tick();check(p.getCurrent()==b,"End without metadata advances to the next song");
            load=GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+ended+"}",200).getAsJsonObject("command");
            check(load.getAsJsonObject("value").get("id").getAsString().equals("456"),"Advance sends the next load to MusicKit");
            AppleWebPlayer.INSTANCE.tick();check(p.getCurrent()==b,"The previous end cannot advance again while the next song loads");
            ack=load.get("id").getAsLong();
            String second=state.replace("123","456").replace("One","Two").replace("240000","0");
            GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+second+"}",200);
            AppleWebPlayer.INSTANCE.tick();check(p.getCurrent()==b&&b.getDurationMs()==240000,"A transient zero duration keeps the catalog duration");
            GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+ended.replace("}",",\"id\":\"123\"}")+"}",200);
            AppleWebPlayer.INSTANCE.tick();check(p.getCurrent()==b,"A stale end for the first song is ignored");
            p.selectMode(MusicPlayer.Mode.REPEAT_ONE);
            GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+ended.replace("}",",\"id\":\"456\"}")+"}",200);
            AppleWebPlayer.INSTANCE.tick();
            load=GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+"}",200).getAsJsonObject("command");
            check(p.getCurrent()==b&&load.getAsJsonObject("value").get("id").getAsString().equals("456"),"Repeat reloads the same song after completion");
            ack=load.get("id").getAsLong();
            NeteaseTrack station=new NeteaseTrack(-3,"Pop Radio",Collections.emptyList(),0,"",MusicSource.APPLE,"ra.test","station:ra.test");
            p.playQueue(Collections.singletonList(station),0);
            load=GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+"}",200).getAsJsonObject("command");
            check(load.getAsJsonObject("value").get("id").getAsString().equals("station:ra.test"),"Radio uses the station descriptor");
            ack=load.get("id").getAsLong();
            String stationQueue=",\"stationQueue\":[{\"id\":\"123\",\"title\":\"One\"},{\"id\":\"456\",\"title\":\"Two\"}]}";
            GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+state.replace("}",stationQueue)+"}",200);
            AppleWebPlayer.INSTANCE.tick();
            check(p.getQueue().size()==2&&p.getCurrent().getNativeId().equals("123"),"The game displays MusicKit's upcoming station track");
            GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+ended+"}",200);
            AppleWebPlayer.INSTANCE.tick();
            check(GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+"}",200).get("command").isJsonNull(),"Station completion must not reload a song in repeat mode");
            GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+second.replace("}",stationQueue)+"}",200);
            AppleWebPlayer.INSTANCE.tick();
            check(p.getCurrent().getNativeId().equals("456")&&p.getIndex()==1,"The next station track becomes current");
            p.next();
            check(GameBridgeCheck.post(s,"/sync",key,"{\"ack\":"+ack+"}",200).getAsJsonObject("command").get("name").getAsString().equals("next"),"Manual station skip stays in MusicKit");
            p.stop();AppleWebPlayer.INSTANCE.tick();check(!p.getActive()&&p.getCurrent()==null,"Stop remains idle after tick");
            System.out.println("Apple game queue/mode/stop checks passed");
        }finally{field.set(null,null);s.close();}
    }
}
