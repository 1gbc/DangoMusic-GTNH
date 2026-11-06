import java.util.*;
import me.sorihiko.dangomusicgtnh.lyrics.*;

class Lyrics527Check {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        LyricSplit.INSTANCE.setEnabled(false);
        LyricLine longLine = new LyricLine(1000L, "abcdef");
        Lyrics lrc = new Lyrics(List.of(longLine, new LyricLine(13000L, "next")), "test");
        check(Math.abs(longLine.sungFraction(7000L, 13000L) - 0.5f) < 0.001f, "Long LRC line must be half sung at its midpoint");
        for (long t = 1000; t < 13000; t += 100) {
            check(lrc.ktvIndexAt(t) == 0, "Untimed long line changed early at " + t);
            check(lrc.instrumentalAt(t, 25000L) == null, "False interlude at " + t);
        }
        check(lrc.ktvIndexAt(13000L) == 1, "Next line starts on its timestamp");
        check(lrc.instrumentalAt(22000L, 25000L) == null, "Cannot infer LRC outro from a fixed duration");

        LyricSplit.INSTANCE.setEnabled(true);
        Lyrics unsplit = new Lyrics(List.of(new LyricLine(1000L, "甲乙 丙丁"), new LyricLine(13000L, "戊己")), "test");
        check(unsplit.getLines().size() == 2, "LRC must preserve original timestamps");
        LyricLine timed = new LyricLine(1000L, "abcdef", List.of(new LyricWord(1000L, 6000L, "abcdef")));
        Lyrics yrc = new Lyrics(List.of(timed, new LyricLine(13000L, "next")), "test");
        check(yrc.instrumentalAt(4000L, 20000L) == null, "Still singing a timed line");
        check(yrc.instrumentalAt(8000L, 20000L) == Lyrics.Instrumental.INTERLUDE, "Timed gap still identifies interlude");
        check(yrc.ktvIndexAt(11500L) == 1, "Timed gap previews next line");
        LyricLine splitTimed = new LyricLine(1000L, "甲乙 丙丁", List.of(
            new LyricWord(1000L, 3000L, "甲乙 "), new LyricWord(6000L, 3000L, "丙丁")));
        List<LyricLine> split = new Lyrics(List.of(splitTimed), "test").getLines();
        check(split.size() == 2 && split.get(1).getTimeMs() == 6000L, "Real word timing preserved during split");
        System.out.println("Long LRC, no false instrumental gaps, exact next timestamps, and timed split/preview passed.");
    }
}
