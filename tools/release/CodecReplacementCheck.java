import java.io.ByteArrayInputStream;
import me.sorihiko.dangomusicgtnh.shadow.javazoom.jl.decoder.*;
import me.sorihiko.dangomusicgtnh.shadow.jcraft.jogg.*;

/** Synthetic codec smoke check; no copyrighted song, account or audio device. */
public final class CodecReplacementCheck {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("probe")) {
            for (String name : new String[] {
                "me.sorihiko.dangomusicgtnh.shadow.javazoom.jl.decoder.DangoCodecRebuildProbe",
                "me.sorihiko.dangomusicgtnh.shadow.jcraft.jorbis.DangoCodecRebuildProbe"
            }) {
                Object result = Class.forName(name).getMethod("value").invoke(null);
                if (!"rebuilt-from-source".equals(result)) throw new AssertionError(name);
            }
        }
        // MPEG-1 Layer III, 128 kbit/s, 44.1 kHz stereo, no padding or main data.
        byte[] mp3 = new byte[417 * 12];
        for (int i = 0; i < mp3.length; i += 417) {
            mp3[i] = (byte) 0xff; mp3[i + 1] = (byte) 0xfb; mp3[i + 2] = (byte) 0x90;
        }
        Bitstream stream = new Bitstream(new ByteArrayInputStream(mp3));
        Decoder decoder = new Decoder();
        int samples = 0;
        Header header;
        while ((header = stream.readFrame()) != null) {
            samples += ((SampleBuffer) decoder.decodeFrame(header, stream)).getBufferLength();
            stream.closeFrame();
        }
        stream.close();
        if (samples < 20000) throw new AssertionError("MP3 samples " + samples);

        StreamState encoder = new StreamState(); encoder.init(42);
        Packet packet = new Packet();
        packet.packet_base = new byte[] {1, 2, 3, 4}; packet.bytes = 4;
        packet.b_o_s = 1; packet.e_o_s = 1;
        if (encoder.packetin(packet) != 0) throw new AssertionError("packetin");
        Page page = new Page();
        if (encoder.flush(page) != 1) throw new AssertionError("flush");
        StreamState reader = new StreamState(); reader.init(42);
        Packet decoded = new Packet();
        if (reader.pagein(page) != 0 || reader.packetout(decoded) != 1 || decoded.bytes != 4)
            throw new AssertionError("Ogg packet roundtrip");
        for (int i = 0; i < 4; i++) if (decoded.packet_base[decoded.packet + i] != i + 1)
            throw new AssertionError("Ogg data");
        System.out.println("Codec smoke passed: MP3 samples=" + samples + ", Ogg roundtrip=4 bytes");
    }
}
