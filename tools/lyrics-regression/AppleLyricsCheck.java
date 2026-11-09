import java.util.*;
import me.sorihiko.dangomusicgtnh.music.*;
import me.sorihiko.dangomusicgtnh.lyrics.*;

public class AppleLyricsCheck {
    static void check(boolean value, String message) { if(!value)throw new AssertionError(message); }
    static NeteaseTrack song(long id,String name,String artist,long duration) {
        return new NeteaseTrack(id,name,List.of(artist),duration,"",MusicSource.APPLE,"123");
    }
    public static void main(String[] args) {
        AppleLyrics provider=AppleLyrics.INSTANCE;
        NeteaseTrack target=song(1,"Song","Singer",200000);
        NeteaseTrack good=song(2,"Song","Singer",200050);
        check(provider.selectMatch(target,List.of(good))==good,"Same title/artist/duration matches");
        check(provider.selectMatch(target,List.of(song(3,"Song (Live)","Singer",200000)))==null,"Do not borrow live versions");
        check(provider.selectMatch(target,List.of(song(3,"Song","Other singer",200000)))==null,"Reject other artist");
        check(provider.selectMatch(target,List.of(song(3,"Song","Singer",204000)))==null,"Reject other duration");
        check(provider.selectMatch(target,List.of(good,song(3,"Song","Singer",200050)))==null,"Reject ambiguous matches");
        NeteaseTrack duet=song(4,"Duet","Singer A & Singer B",200000);
        NeteaseTrack separate=new NeteaseTrack(5,"Duet",List.of("Singer B","Singer A"),200050,"",MusicSource.NETEASE,"5");
        check(provider.selectMatch(duet,List.of(separate))==separate,"Match Apple combined credits against separate NetEase credits in either order");
        check(provider.selectMatch(duet,List.of(song(6,"Duet","Singer A",200000)))==null,"Do not match a solo recording to a duet");
        NeteaseTrack band=song(7,"Song","AC/DC",200000);
        check(provider.selectMatch(band,List.of(song(8,"Song","AC/DC",200000)))!=null,"A slash in a band name still matches");
        Lyrics lrc=provider.parse("[00:01.00]first\n[00:05.00]second","lrc");
        check(lrc!=null&&lrc.indexAt(2000)==0&&lrc.indexAt(6000)==1,"Existing world lyric clock consumes Apple lines");
        Lyrics shifted=provider.shift(lrc,500);
        check(shifted.getLines().get(0).getTimeMs()==1500,"Local timing offset");
        String xml="<tt xmlns='http://www.w3.org/ns/ttml'><body><div><p begin='1s' end='4s'><span begin='1s' end='2s'>Hello </span><span begin='2s' end='4s'>world</span></p></div></body></tt>";
        Lyrics ttml=provider.parse(xml,"ttml");
        check(ttml!=null&&ttml.getLines().get(0).getWords().size()==2,"TTML word timestamps");
        LyricLine line=ttml.getLines().get(0);
        check(line.sungCharacters(1000,4000)==0&&line.sungCharacters(3000,4000)>5,"Shared 3D/KTV word progress");
        check(provider.parse("<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///does-not-exist'>]><tt><p begin='1s'>&x;</p></tt>","ttml")==null,"Reject XML external entities");
        check(provider.parse("<tt><p begin='20f'>unsupported timing</p></tt>","ttml")==null,"Do not invent frame timestamps");
        NeteaseTrack unsafe=new NeteaseTrack(1,"Song",List.of("Singer"),200000,"",MusicSource.APPLE,"../../outside");
        check(provider.fileStem(unsafe).matches("apple-[a-f0-9]{64}"),"Local lyric filename cannot traverse directories");
        System.out.println("Apple lyric matching, LRC/TTML, world clock, offsets and XML safety checks passed");
    }
}
