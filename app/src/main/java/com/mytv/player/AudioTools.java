package com.mytv.player;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Audio tools: video → audio, cutter, splitter, merger, karaoke.
 * MP3 stays MP3 for cut / split / merge (lossless, {@link Mp3Frames}). AAC (most MP4 / M4A) is copied without
 * re-encoding when possible. Everything else is decoded and saved as AAC .m4a (Android has no MP3 encoder).
 * Results go to Music / ZH Play. Run on a background thread.
 */
public final class AudioTools {
    private AudioTools() {
    }

    public interface Progress {
        void on(float f, String what);
    }

    public static volatile boolean cancel;

    // ------------------------------------------------------------------ input helpers
    static byte[] readAll(Context c, Uri u) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("Can't open the file");
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    static MediaExtractor open(Context c, Uri u) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(c, u, null);
        return ex;
    }

    /** Index of the first audio track, or -1. */
    static int audioTrack(MediaExtractor ex) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) return i;
        }
        return -1;
    }

    public static boolean isMp3(Context c, Uri u, String name) {
        if (name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3")) return true;
        try {
            MediaExtractor ex = open(c, u);
            int t = audioTrack(ex);
            boolean r = t >= 0 && "audio/mpeg".equals(ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME));
            ex.release();
            return r;
        } catch (Exception e) {
            return false;
        }
    }

    public static long durationUs(Context c, Uri u) {
        try {
            MediaExtractor ex = open(c, u);
            int t = audioTrack(ex);
            long d = t >= 0 && ex.getTrackFormat(t).containsKey(MediaFormat.KEY_DURATION) ? ex.getTrackFormat(t).getLong(MediaFormat.KEY_DURATION) : 0;
            ex.release();
            return d;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ decoding to 16-bit PCM
    public interface PcmSink {
        /** format known (called again if it changes) */
        void format(int rate, int ch) throws Exception;

        void pcm(short[] data, int frames, long ptsUs) throws Exception;
    }

    /** Decodes the audio of `u` from startUs to endUs (endUs ≤ 0 = to the end) into the sink. */
    public static void decode(Context c, Uri u, long startUs, long endUs, PcmSink sink, Progress p) throws Exception {
        MediaExtractor ex = open(c, u);
        int t = audioTrack(ex);
        if (t < 0) {
            ex.release();
            throw new Exception(T.t("This file has no sound track"));
        }
        ex.selectTrack(t);
        MediaFormat fmt = ex.getTrackFormat(t);
        long dur = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
        if (endUs <= 0 || (dur > 0 && endUs > dur)) endUs = dur > 0 ? dur : Long.MAX_VALUE;
        if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        MediaCodec dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        dec.configure(fmt, null, null, 0);
        dec.start();
        int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        sink.format(rate, ch);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        boolean inEos = false, outEos = false;
        long span = Math.max(1, endUs - startUs);
        try {
            while (!outEos) {
                if (cancel) throw new Exception(T.t("Cancelled"));
                if (!inEos) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer buf = dec.getInputBuffer(ii);
                        int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0 || ex.getSampleTime() > endUs + 200_000) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(bi, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    sink.format(rate, ch);
                } else if (oi >= 0) {
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    if (bi.size > 0 && bi.presentationTimeUs <= endUs) {
                        ByteBuffer ob = dec.getOutputBuffer(oi);
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        short[] s = new short[bi.size / 2];
                        ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
                        int frames = s.length / ch;
                        long pts = bi.presentationTimeUs;
                        // drop what lies before the start (decoders begin at the previous key frame)
                        int skip = 0;
                        if (pts < startUs) skip = (int) Math.min(frames, (startUs - pts) * rate / 1_000_000L);
                        // and what lies after the end
                        long endFrame = (endUs - pts) * rate / 1_000_000L;
                        int keep = (int) Math.max(0, Math.min(frames, endFrame) - skip);
                        if (keep > 0) {
                            short[] part = new short[keep * ch];
                            System.arraycopy(s, skip * ch, part, 0, keep * ch);
                            sink.pcm(part, keep, pts + skip * 1_000_000L / rate);
                        }
                        if (pts > endUs) outEos = true;
                        if (p != null) p.on(Math.min(1f, Math.max(0, (pts - startUs) / (float) span)), "");
                    }
                    dec.releaseOutputBuffer(oi, false);
                }
            }
        } finally {
            try {
                dec.stop();
            } catch (Exception ignored) {
            }
            dec.release();
            ex.release();
        }
    }

    // ------------------------------------------------------------------ AAC encoder + MP4 writer
    public static final class AacWriter {
        private final MediaCodec enc;
        private final MediaMuxer mux;
        private final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        private final int rate, ch;
        private int track = -1;
        private long frames;
        private boolean started;

        public AacWriter(File out, int rate, int ch, int bitrate) throws Exception {
            this.rate = rate;
            this.ch = Math.max(1, Math.min(2, ch));
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, this.ch);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384 * this.ch);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        public int channels() {
            return ch;
        }

        public void write(short[] pcm, int nFrames) throws Exception {
            int off = 0;
            while (off < nFrames) {
                if (cancel) throw new Exception(T.t("Cancelled"));
                int ii = enc.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    ByteBuffer buf = enc.getInputBuffer(ii);
                    buf.clear();
                    int room = buf.remaining() / (2 * ch);
                    int n = Math.min(room, nFrames - off);
                    ByteBuffer bb = ByteBuffer.allocate(n * ch * 2).order(ByteOrder.LITTLE_ENDIAN);
                    bb.asShortBuffer().put(pcm, off * ch, n * ch);
                    buf.put(bb);
                    enc.queueInputBuffer(ii, 0, n * ch * 2, frames * 1_000_000L / rate, 0);
                    frames += n;
                    off += n;
                }
                drain(false);
            }
        }

        private void drain(boolean eos) throws Exception {
            while (true) {
                int oi = enc.dequeueOutputBuffer(bi, eos ? 10000 : 0);
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!eos) return;
                    continue;
                }
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    started = true;
                } else if (oi >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oi);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                    if (bi.size > 0 && started) {
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        mux.writeSampleData(track, ob, bi);
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                }
            }
        }

        public void finish() throws Exception {
            int ii;
            do {
                ii = enc.dequeueInputBuffer(10000);
            } while (ii < 0);
            enc.queueInputBuffer(ii, 0, 0, frames * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true);
            close();
        }

        public void close() {
            try {
                enc.stop();
            } catch (Exception ignored) {
            }
            enc.release();
            try {
                if (started) mux.stop();
            } catch (Exception ignored) {
            }
            mux.release();
        }
    }

    // ------------------------------------------------------------------ lossless AAC copy (no re-encoding)
    /** Copies the AAC sound of an MP4 / M4A between start and end into a new .m4a. Returns false if not AAC. */
    static boolean remuxAac(Context c, Uri u, long startUs, long endUs, File out, Progress p) throws Exception {
        MediaExtractor ex = open(c, u);
        int t = audioTrack(ex);
        if (t < 0 || !MediaFormat.MIMETYPE_AUDIO_AAC.equals(ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME))) {
            ex.release();
            return false;
        }
        MediaFormat fmt = ex.getTrackFormat(t);
        long dur = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
        if (endUs <= 0 || (dur > 0 && endUs > dur)) endUs = dur > 0 ? dur : Long.MAX_VALUE;
        ex.selectTrack(t);
        if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int mt = mux.addTrack(fmt);
        mux.start();
        ByteBuffer buf = ByteBuffer.allocate(fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 256 * 1024);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        long first = -1, span = Math.max(1, endUs - startUs);
        try {
            while (true) {
                if (cancel) throw new Exception(T.t("Cancelled"));
                int n = ex.readSampleData(buf, 0);
                long ts = ex.getSampleTime();
                if (n < 0 || ts > endUs) break;
                if (ts >= startUs - 30_000) {
                    if (first < 0) first = ts;
                    bi.set(0, n, ts - first, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                    mux.writeSampleData(mt, buf, bi);
                }
                if (p != null && ts > 0) p.on(Math.min(1f, (ts - startUs) / (float) span), "");
                ex.advance();
            }
        } finally {
            try {
                mux.stop();
            } catch (Exception ignored) {
            }
            mux.release();
            ex.release();
        }
        return true;
    }

    // ------------------------------------------------------------------ waveform
    /** Peak levels (0…1) across the whole file, `points` columns — for the cutter / splitter. */
    public static float[] waveform(Context c, Uri u, final int points, Progress p) throws Exception {
        final long dur = Math.max(1, durationUs(c, u));
        final float[] peaks = new float[points];
        decode(c, u, 0, 0, new PcmSink() {
            int ch = 2, rate = 44100;

            public void format(int r, int cc) {
                rate = r;
                ch = cc;
            }

            public void pcm(short[] d, int frames, long pts) {
                for (int i = 0; i < frames; i++) {
                    long t = pts + i * 1_000_000L / rate;
                    int col = (int) Math.min(points - 1, t * points / dur);
                    if (col < 0) continue;
                    float v = Math.abs(d[i * ch]) / 32768f;
                    if (v > peaks[col]) peaks[col] = v;
                }
            }
        }, p);
        return peaks;
    }

    // ------------------------------------------------------------------ saving into Music / ZH Play
    static File temp(Context c, String ext) {
        File d = new File(c.getCacheDir(), "tools");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, "out_" + System.nanoTime() + ext);
    }

    public static String safe(String name) {
        String s = name.replaceAll("\\.[A-Za-z0-9]{2,4}$", "").replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "audio" : (s.length() > 80 ? s.substring(0, 80) : s);
    }

    /** Moves a finished file into Music/ZH Play so it appears in the phone's music. Returns the saved name. */
    public static String publish(Context c, File tmp, String name) throws Exception {
        String mime = name.endsWith(".mp3") ? "audio/mpeg" : "audio/mp4";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Audio.Media.MIME_TYPE, mime);
                v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/ZH Play");
                v.put(MediaStore.Audio.Media.IS_PENDING, 1);
                Uri dest = c.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                if (dest == null) throw new Exception("Can't save");
                try (OutputStream o = c.getContentResolver().openOutputStream(dest); InputStream in = new FileInputStream(tmp)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                }
                v.clear();
                v.put(MediaStore.Audio.Media.IS_PENDING, 0);
                c.getContentResolver().update(dest, v, null, null);
            } else {
                File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "ZH Play");
                //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
                File f = new File(d, name);
                try (OutputStream o = new FileOutputStream(f); InputStream in = new FileInputStream(tmp)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                }
                MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return "Music/ZH Play/" + name;
    }

    // ------------------------------------------------------------------ the tools
    /** Re-encodes a range to AAC with optional processing (karaoke / fades). */
    private static File transcode(Context c, Uri u, long startUs, long endUs, final PcmFx.Karaoke[] karaoke,
                                  final float karaokeStrength, final boolean keepBass, final long fadeInUs, final long fadeOutUs, Progress p) throws Exception {
        final File out = temp(c, ".m4a");
        final AacWriter[] w = new AacWriter[1];
        final long total = (endUs > 0 ? endUs : durationUs(c, u)) - startUs;
        try {
            decode(c, u, startUs, endUs, new PcmSink() {
                int rate, ch;
                long pos;

                public void format(int r, int cc) throws Exception {
                    if (w[0] != null) return;
                    rate = r;
                    ch = cc;
                    if (karaoke != null && cc < 2) throw new Exception(T.t("Karaoke needs a stereo song (this one is mono)"));
                    w[0] = new AacWriter(out, r, Math.min(2, cc), 192_000);
                    if (karaoke != null) karaoke[0] = new PcmFx.Karaoke(r, karaokeStrength, keepBass);
                }

                public void pcm(short[] d, int frames, long pts) throws Exception {
                    short[] s = d;
                    int oc = w[0].channels();
                    if (ch != oc) {   // >2 channels → first two
                        s = new short[frames * oc];
                        for (int i = 0; i < frames; i++) for (int k = 0; k < oc; k++) s[i * oc + k] = d[i * ch + Math.min(k, ch - 1)];
                    }
                    if (karaoke != null) karaoke[0].process(s, frames);
                    long tf = total * rate / 1_000_000L;
                    PcmFx.fades(s, frames, oc, pos, tf, fadeInUs * rate / 1_000_000L, fadeOutUs * rate / 1_000_000L);
                    pos += frames;
                    w[0].write(s, frames);
                }
            }, p);
            if (w[0] == null) throw new Exception(T.t("No sound found"));
            w[0].finish();
            return out;
        } catch (Exception e) {
            if (w[0] != null) w[0].close();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
    }

    /** Video → audio (AAC copied without loss when possible). */
    public static String toAudio(Context c, Uri u, String name, Progress p) throws Exception {
        cancel = false;
        File out = temp(c, ".m4a");
        if (!remuxAac(c, u, 0, 0, out, p)) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            out = transcode(c, u, 0, 0, null, 0, false, 0, 0, p);
        }
        return publish(c, out, safe(name) + ".m4a");
    }

    /** Cut one part. MP3 → MP3 (lossless), AAC → M4A (lossless) unless fades are wanted. */
    public static String cut(Context c, Uri u, String name, long startUs, long endUs, long fadeInUs, long fadeOutUs, String suffix,
                             Progress p) throws Exception {
        cancel = false;
        boolean fades = fadeInUs > 0 || fadeOutUs > 0;
        if (!fades && isMp3(c, u, name)) {
            byte[] b = readAll(c, u);
            byte[] r = Mp3Frames.cut(b, startUs, endUs);
            if (r.length > 1000) {
                File out = temp(c, ".mp3");
                try (FileOutputStream o = new FileOutputStream(out)) {
                    o.write(r);
                }
                return publish(c, out, safe(name) + suffix + ".mp3");
            }
        }
        File out = temp(c, ".m4a");
        if (fades || !remuxAac(c, u, startUs, endUs, out, p)) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            out = transcode(c, u, startUs, endUs, null, 0, false, fadeInUs, fadeOutUs, p);
        }
        return publish(c, out, safe(name) + suffix + ".m4a");
    }

    /** Split at the given points (µs). Returns the saved names. */
    public static List<String> split(Context c, Uri u, String name, long[] points, final Progress p) throws Exception {
        long dur = durationUs(c, u);
        List<Long> cuts = new ArrayList<>();
        cuts.add(0L);
        for (long x : points) if (x > 500_000 && x < dur - 500_000) cuts.add(x);
        cuts.add(dur);
        java.util.Collections.sort(cuts);
        List<String> saved = new ArrayList<>();
        final int parts = cuts.size() - 1;
        for (int i = 0; i < parts; i++) {
            final int k = i;
            saved.add(cut(c, u, name, cuts.get(i), cuts.get(i + 1), 0, 0, " (part " + (i + 1) + ")",
                    (f, w) -> {
                        if (p != null) p.on((k + f) / parts, T.t("Part") + " " + (k + 1) + " / " + parts);
                    }));
            if (cancel) break;
        }
        return saved;
    }

    /** Join songs in order. All MP3 → MP3 (lossless); otherwise one AAC file (44.1 kHz stereo). */
    public static String merge(Context c, List<Uri> uris, List<String> names, String outName, final Progress p) throws Exception {
        cancel = false;
        boolean allMp3 = true;
        for (int i = 0; i < uris.size(); i++) if (!isMp3(c, uris.get(i), names.get(i))) allMp3 = false;
        if (allMp3) {
            List<byte[]> all = new ArrayList<>();
            for (int i = 0; i < uris.size(); i++) {
                all.add(readAll(c, uris.get(i)));
                if (p != null) p.on((i + 1f) / uris.size(), names.get(i));
            }
            File out = temp(c, ".mp3");
            try (FileOutputStream o = new FileOutputStream(out)) {
                o.write(Mp3Frames.merge(all));
            }
            return publish(c, out, safe(outName) + ".mp3");
        }
        final File out = temp(c, ".m4a");
        final AacWriter w = new AacWriter(out, 44100, 2, 192_000);
        try {
            for (int i = 0; i < uris.size(); i++) {
                final int k = i;
                final PcmFx.Resampler[] rs = new PcmFx.Resampler[1];
                decode(c, uris.get(i), 0, 0, new PcmSink() {
                    public void format(int r, int ch) {
                        rs[0] = new PcmFx.Resampler(r, ch, 44100, 2);
                    }

                    public void pcm(short[] d, int frames, long pts) throws Exception {
                        short[] o = rs[0].process(d, frames);
                        w.write(o, o.length / 2);
                    }
                }, (f, x) -> {
                    if (p != null) p.on((k + f) / uris.size(), names.get(k));
                });
            }
            w.finish();
        } catch (Exception e) {
            w.close();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
        return publish(c, out, safe(outName) + ".m4a");
    }

    /** Karaoke version: centre vocals removed, instruments (and bass) kept. */
    public static String karaoke(Context c, Uri u, String name, float strength, boolean keepBass, long previewUs, Progress p) throws Exception {
        cancel = false;
        File out = transcode(c, u, 0, previewUs, new PcmFx.Karaoke[1], strength, keepBass, 0, previewUs > 0 ? 1_500_000 : 0, p);
        return publish(c, out, safe(name) + (previewUs > 0 ? " (karaoke preview)" : " (karaoke)") + ".m4a");
    }
}
