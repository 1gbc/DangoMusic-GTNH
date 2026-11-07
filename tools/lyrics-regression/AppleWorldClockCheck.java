import java.lang.reflect.*;
import java.util.*;
import me.sorihiko.dangomusicgtnh.music.*;
import me.sorihiko.dangomusicgtnh.lyrics.*;
import me.sorihiko.dangomusicgtnh.module.impls.music.LyricsModule;

/** Exercise the actual renderer clock updater without a Minecraft world or GPU. */
public class AppleWorldClockCheck {
    public static void main(String[] args)throws Exception {
        LyricsModule renderer=new LyricsModule();
        NeteaseTrack track=new NeteaseTrack(1,"Fixture",List.of("Singer"),20000,"",MusicSource.APPLE,"fixture");
        Field cache=LyricsModule.class.getDeclaredField("cache");cache.setAccessible(true);
        ((Map)cache.get(renderer)).put(track.getKey(),new Lyrics(List.of(new LyricLine(0,"One sentence"),new LyricLine(10000,"Next sentence")),"Fixture"));
        Method update=LyricsModule.class.getDeclaredMethod("updateClock");update.setAccessible(true);
        Field index=LyricsModule.class.getDeclaredField("lastIndex");index.setAccessible(true);
        MusicSources.INSTANCE.setSelected(MusicSource.APPLE);
        MusicPlayer.INSTANCE.acceptAppleState(track,1000,true);update.invoke(renderer);
        index.setInt(renderer,0);
        for(long position:new long[]{1700,2400,3100,4500,5200,9000}) {
            MusicPlayer.INSTANCE.acceptAppleState(track,position,true);update.invoke(renderer);
            if(index.getInt(renderer)!=0)throw new AssertionError("Telemetry must not invalidate the active line at "+position);
        }
        System.out.println("Actual world lyric clock retains its active line across 700ms telemetry and same-line seeks");
    }
}
