package com.docreader.app.pdf;

import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * يفكّ ترميز صوت الجملة (MP3 من الصوت العصبي) إلى "غلاف علو الصوت": قيمة 0..1 لكل نافذة
 * زمنها {@link #WINDOW_MS}. تُستخدم لتحريك موجة مشغّل القراءة بحسب الصوت الفعلي المسموع
 * بدون أي صلاحية (لا تحتاج RECORD_AUDIO كما يحتاجها Visualizer).
 *
 * يُستدعى من خيط خلفي فقط.
 */
final class AudioEnvelope {

    /** زمن النافذة الواحدة بالمللي ثانية (25ms = 40 قراءة في الثانية: أدق من إطار الشاشة تقريبًا). */
    static final int WINDOW_MS = 25;

    private static final long TIMEOUT_NS = 20_000_000_000L; // سقف أمان للفك

    private AudioEnvelope() {
    }

    /** يرجّع الغلاف أو null لو تعذّر الفك (يُستعمل حينها تحريك تقديري للموجة). */
    static float[] fromEncoded(final byte[] data) {
        if (data == null || data.length < 200) return null;
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(new MediaDataSource() {
                @Override
                public int readAt(long position, byte[] buffer, int offset, int size) {
                    if (position >= data.length) return -1;
                    int n = (int) Math.min(size, data.length - position);
                    System.arraycopy(data, (int) position, buffer, offset, n);
                    return n;
                }

                @Override
                public long getSize() {
                    return data.length;
                }

                @Override
                public void close() {
                }
            });

            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) return null;
            ex.selectTrack(track);

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int sampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 24000;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            long windowFrames = Math.max(1, (long) sampleRate * WINDOW_MS / 1000);

            float[] rms = new float[256];
            int count = 0;
            double sumSq = 0;
            long frames = 0;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false;
            boolean outDone = false;
            final long deadline = System.nanoTime() + TIMEOUT_NS;

            while (!outDone && System.nanoTime() < deadline) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(5000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int sz = ib != null ? ex.readSampleData(ib, 0) : -1;
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                int oi = codec.dequeueOutputBuffer(info, 5000);
                if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                        while (sb.remaining() >= channels) {
                            float s = 0f;
                            for (int c = 0; c < channels; c++) s += sb.get();
                            s /= channels;
                            sumSq += (double) s * s;
                            frames++;
                            if (frames >= windowFrames) {
                                if (count == rms.length) rms = Arrays.copyOf(rms, count * 2);
                                rms[count++] = (float) (Math.sqrt(sumSq / frames) / 32768.0);
                                sumSq = 0;
                                frames = 0;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channels = Math.max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    windowFrames = Math.max(1, (long) sampleRate * WINDOW_MS / 1000);
                }
            }
            if (count < 2) return null;
            return normalize(Arrays.copyOf(rms, count));
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * يطبّع القيم بحيث يكون الكلام العالي قرب 1 والصمت 0، مع ضغط خفيف (جذر) كي تظهر
     * الأجزاء الهادئة، وتنعيم بسيط كي لا ترتجف الموجة.
     */
    private static float[] normalize(float[] v) {
        float[] sorted = v.clone();
        Arrays.sort(sorted);
        float ref = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95f))];
        if (ref < 1e-4f) ref = Math.max(1e-4f, sorted[sorted.length - 1]);

        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            float x = v[i] / ref;
            if (x < 0.06f) x = 0f;              // أرضية ضجيج: صمت حقيقي بين الكلمات
            x = Math.min(1f, x);
            out[i] = (float) Math.pow(x, 0.65);
        }
        float[] sm = new float[out.length];
        for (int i = 0; i < out.length; i++) {
            float a = out[Math.max(0, i - 1)];
            float b = out[i];
            float c = out[Math.min(out.length - 1, i + 1)];
            sm[i] = a * 0.25f + b * 0.5f + c * 0.25f;
        }
        return sm;
    }
}
