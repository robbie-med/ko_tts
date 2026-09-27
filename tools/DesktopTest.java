import org.robbiemed.kotts.engine.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;

/** Desktop smoke test: java DesktopTest <modelDir> <voice> <steps> <outDir> "text"... */
public class DesktopTest {
    public static void main(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        try (Supertonic tts = new Supertonic(new File(a[0]), 0);
             Supertonic.Style st = tts.loadStyle(new File(a[0], "voice_styles/" + a[1] + ".json"))) {
            System.out.println("load ms " + (System.currentTimeMillis() - t0));
            for (int k = 4; k < a.length; k++) {
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                long t = System.currentTimeMillis();
                tts.synth(a[k], "ko", st, 1.05f, Integer.parseInt(a[2]), (buf, len) -> {
                    ByteBuffer bb = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < len; i++) bb.putShort((short) Math.max(-32768, Math.min(32767, buf[i] * 32767)));
                    pcm.write(bb.array(), 0, bb.position());
                    return true;
                });
                byte[] d = pcm.toByteArray();
                System.out.printf("%d: %.2fs audio in %d ms%n", k - 4, d.length / 2.0 / tts.sampleRate, System.currentTimeMillis() - t);
                writeWav(Paths.get(a[3], (k - 4) + ".wav"), d, tts.sampleRate);
            }
        }
    }
    static void writeWav(Path p, byte[] d, int sr) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + d.length).put("WAVE".getBytes()).put("fmt ".getBytes())
         .putInt(16).putShort((short) 1).putShort((short) 1).putInt(sr).putInt(sr * 2).putShort((short) 2).putShort((short) 16)
         .put("data".getBytes()).putInt(d.length);
        try (OutputStream o = Files.newOutputStream(p)) { o.write(h.array()); o.write(d); }
    }
}
