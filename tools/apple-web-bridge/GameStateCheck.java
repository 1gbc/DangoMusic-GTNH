import me.sorihiko.dangomusicgtnh.music.*;
import java.util.Collections;

public class GameStateCheck {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args) {
        MusicSources.INSTANCE.setSelected(MusicSource.APPLE);
        NeteaseTrack song=new NeteaseTrack(-42,"Fixture",Collections.singletonList("Singer"),240000,"",MusicSource.APPLE,"123");
        MusicPlayer p=MusicPlayer.INSTANCE;
        p.acceptAppleState(song,92000,true);
        check(p.getPositionMs()==92000,"Game uses browser progress");
        check(p.getDurationMs()==240000,"Game uses complete duration");
        check(p.getState()==MusicPlayer.State.PLAYING,"HUD sees playing state");
        p.acceptAppleState(song,12000,false);
        check(p.getPositionMs()==12000&&p.getState()==MusicPlayer.State.PAUSED,"Manual web seek/pause updates game");
        p.stop();check(!p.getActive()&&p.getCurrent()==null,"Stop clears HUD immediately");
        MusicSources.INSTANCE.setSelected(MusicSource.NETEASE);
        p.acceptAppleState(song,13000,true);
        check(p.getCurrent()==null,"Apple telemetry cannot overwrite another source");
        System.out.println("Game playback state checks passed");
    }
}
