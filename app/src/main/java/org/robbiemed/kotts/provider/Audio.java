package org.robbiemed.kotts.provider;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Turns whatever an online API returns (raw PCM, WAV, MP3/Opus/AAC) into 16-bit mono PCM for the sink. */
final class Audio {
    private Audio() { }

    /** Raw little-endian 16-bit mono PCM at a known rate, streamed as it arrives. */
    static void pcm(InputStream in, int rate, Provider.Sink sink) throws IOException {
        if (!sink.start(rate)) return;
        byte[] buf = new byte[8192];
        int carry = 0, n;
        while ((n = in.read(buf, carry, buf.length - carry)) > 0) {
            int have = carry + n, even = have & ~1;
            if (!sink.pcm16(buf, 0, even)) return;
            carry = have - even;
            if (carry > 0) buf[0] = buf[even];
        }
    }

    /** Sniffs the stream: WAV is parsed and streamed, anything else goes through MediaCodec. */
    static void any(InputStream raw, File cacheDir, Provider.Sink sink) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw, 1 << 16);
        in.mark(64);
        byte[] head = new byte[12];
        int got = readFully(in, head);
        in.reset();
        if (got == 12 && new String(head, 0, 4).equals("RIFF") && new String(head, 8, 4).equals("WAVE")) wav(in, sink);
        else decode(in, cacheDir, sink);
    }

    static void wav(InputStream in, Provider.Sink sink) throws IOException {
        byte[] h = new byte[12];
        readFully(in, h);
        int rate = 24000, channels = 1, bits = 16;
        byte[] ch = new byte[8];
        while (readFully(in, ch) == 8) {
            String id = new String(ch, 0, 4);
            long size = ByteBuffer.wrap(ch, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            if (id.equals("fmt ")) {
                byte[] f = new byte[(int) size];
                readFully(in, f);
                ByteBuffer b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);
                channels = b.getShort(2);
                rate = b.getInt(4);
                bits = b.getShort(14);
            } else if (id.equals("data")) {
                if (bits != 16) throw new IOException("Unsupported WAV: " + bits + "-bit");
                if (channels == 1) { pcm(in, rate, sink); return; }
                if (!sink.start(rate)) return;
                downmix(in, channels, sink);
                return;
            } else {
                long skipped = 0;
                while (skipped < size) { long s = in.skip(size - skipped); if (s <= 0) break; skipped += s; }
            }
        }
        throw new IOException("WAV without data");
    }

    private static void downmix(InputStream in, int channels, Provider.Sink sink) throws IOException {
        int frame = 2 * channels;
        byte[] buf = new byte[frame * 2048];
        byte[] out = new byte[2 * 2048];
        int n;
        while ((n = readFully(in, buf)) >= frame) {
            int frames = n / frame;
            for (int f = 0; f < frames; f++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    int o = f * frame + 2 * c;
                    sum += (short) ((buf[o] & 0xFF) | (buf[o + 1] << 8));
                }
                int v = sum / channels;
                out[2 * f] = (byte) v;
                out[2 * f + 1] = (byte) (v >> 8);
            }
            if (!sink.pcm16(out, 0, frames * 2)) return;
            if (n < buf.length) return;
        }
    }

    /** Compressed audio: spool to a temp file, then MediaExtractor + MediaCodec to PCM. */
    private static void decode(InputStream in, File cacheDir, Provider.Sink sink) throws IOException {
        File tmp = File.createTempFile("tts", ".bin", cacheDir);
        try {
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
            MediaExtractor ex = new MediaExtractor();
            ex.setDataSource(tmp.getPath());
            if (ex.getTrackCount() == 0) throw new IOException("Unrecognized audio from server");
            MediaFormat fmt = ex.getTrackFormat(0);
            ex.selectTrack(0);
            MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean started = false, inDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (true) {
                    if (!inDone) {
                        int i = codec.dequeueInputBuffer(10000);
                        if (i >= 0) {
                            int size = ex.readSampleData(codec.getInputBuffer(i), 0);
                            if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                            else { codec.queueInputBuffer(i, 0, size, ex.getSampleTime(), 0); ex.advance(); }
                        }
                    }
                    int o = codec.dequeueOutputBuffer(info, 10000);
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (!started && !sink.start(of.getInteger(MediaFormat.KEY_SAMPLE_RATE))) return;
                        started = true;
                    } else if (o >= 0) {
                        if (!started) {
                            if (!sink.start(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE))) return;
                            started = true;
                        }
                        ByteBuffer ob = codec.getOutputBuffer(o);
                        byte[] pcm = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(pcm);
                        codec.releaseOutputBuffer(o, false);
                        boolean more = channels == 1 ? sink.pcm16(pcm, 0, pcm.length) : mono(pcm, channels, sink);
                        if (!more) return;
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                    }
                }
            } finally {
                codec.stop();
                codec.release();
                ex.release();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static boolean mono(byte[] pcm, int channels, Provider.Sink sink) {
        int frames = pcm.length / (2 * channels);
        byte[] out = new byte[frames * 2];
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int o = (f * channels + c) * 2;
                sum += (short) ((pcm[o] & 0xFF) | (pcm[o + 1] << 8));
            }
            int v = sum / channels;
            out[2 * f] = (byte) v;
            out[2 * f + 1] = (byte) (v >> 8);
        }
        return sink.pcm16(out, 0, out.length);
    }

    static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0, n;
        while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        return off;
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0 && o.size() < 4096) o.write(b, 0, n);
        return o.toString("UTF-8");
    }
}
