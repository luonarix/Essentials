package dev.local.essentials;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Screen recorder: H.264 video from a VirtualDisplay fed straight into the
 * encoder surface, AAC audio from AudioPlaybackCapture (device sound only,
 * never the microphone), muxed together as one mp4.
 */
final class Recorder {

    interface Listener {
        void onSaved(File file);

        void onError(String message);
    }

    private static final String MIME_V = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String MIME_A = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int SAMPLE_RATE = 44100;
    private static final int AUDIO_BITRATE = 128000;

    private final Context ctx;
    private final MediaProjection projection;
    private final int width;
    private final int height;
    private final int fps;
    private final File outFile;
    private final Listener listener;

    private MediaCodec vEnc;
    private MediaCodec aEnc;
    private Surface inputSurface;
    private MediaMuxer muxer;
    private VirtualDisplay display;
    private AudioRecord audio;

    private final Object lock = new Object();
    private int vTrack = -1;
    private int aTrack = -1;
    private boolean audioWanted;
    private boolean audioFailed;
    private boolean muxStarted;
    private volatile boolean running;
    private Thread videoThread;
    private Thread audioThread;

    Recorder(Context ctx, MediaProjection projection, int width, int height, int fps,
             File outFile, Listener listener) {
        this.ctx = ctx;
        this.projection = projection;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.outFile = outFile;
        this.listener = listener;
    }

    void start() {
        try {
            muxer = new MediaMuxer(outFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            startVideo();
            startAudio();
            running = true;
            videoThread.start();
            if (audioThread != null) audioThread.start();
            startMuxWatchdog();
            display = projection.createVirtualDisplay("essentials-rec",
                    width, height, ctx.getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null);
        } catch (Exception e) {
            try {
                stop(false);
            } catch (Exception ignored) {
            }
            try {
                if (muxer != null) {
                    muxer.release();
                    muxer = null;
                }
            } catch (Exception ignored) {
            }
            listener.onError("Không khởi động được ghi màn hình");
        }
    }

    // ------------------------------------------------------------ video

    private void startVideo() throws Exception {
        MediaFormat f = MediaFormat.createVideoFormat(MIME_V, width, height);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        int bitrate = (int) Math.min(20000000L,
                Math.max(1500000L, (long) width * height * fps / 10L));
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        vEnc = MediaCodec.createEncoderByType(MIME_V);
        vEnc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = vEnc.createInputSurface();
        vEnc.start();

        videoThread = new Thread(new Runnable() {
            public void run() {
                drainVideo();
            }
        }, "rec-video");
    }

    private void drainVideo() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                int idx = vEnc.dequeueOutputBuffer(info, 10000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (lock) {
                        vTrack = muxer.addTrack(vEnc.getOutputFormat());
                        maybeStartMuxer();
                    }
                } else if (idx >= 0) {
                    ByteBuffer buf = vEnc.getOutputBuffer(idx);
                    if (buf != null && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        synchronized (lock) {
                            if (muxStarted && vTrack >= 0) {
                                muxer.writeSampleData(vTrack, buf, info);
                            }
                        }
                    }
                    vEnc.releaseOutputBuffer(idx, false);
                }
            } catch (Exception e) {
                if (running) fail();
                return;
            }
        }
    }

    /**
     * If the AAC track never shows up (some devices refuse playback capture)
     * start the mp4 with video only instead of hanging forever.
     */
    private void startMuxWatchdog() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {
                }
                synchronized (lock) {
                    if (muxStarted || vTrack < 0) return;
                    audioWanted = false;
                    audioFailed = true;
                    muxer.start();
                    muxStarted = true;
                }
            }
        }, "rec-mux-watchdog");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ audio

    private void startAudio() {
        if (Build.VERSION.SDK_INT < 29) {
            audioWanted = false;
            audioFailed = true;
            return;
        }
        audioWanted = true;
        try {
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int bufSize = Math.max(4096, AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2);

            AudioPlaybackCaptureConfiguration capture =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();

            audio = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufSize)
                    .setAudioPlaybackCaptureConfig(capture)
                    .build();
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
                audio.release();
                audio = null;
                audioFailed = true;
                return;
            }

            MediaFormat af = MediaFormat.createAudioFormat(MIME_A, SAMPLE_RATE, 2);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            aEnc = MediaCodec.createEncoderByType(MIME_A);
            aEnc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aEnc.start();

            audioThread = new Thread(new Runnable() {
                public void run() {
                    drainAudio();
                }
            }, "rec-audio");
        } catch (Exception e) {
            audioWanted = false;
            audioFailed = true;
            if (audio != null) {
                try {
                    audio.release();
                } catch (Exception ignored) {
                }
                audio = null;
            }
        }
    }

    private void drainAudio() {
        byte[] pcm = new byte[8192];
        long frames = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        audio.startRecording();
        while (running) {
            int n;
            try {
                n = audio.read(pcm, 0, pcm.length);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) continue;

            try {
                int idx = aEnc.dequeueInputBuffer(10000);
                if (idx >= 0) {
                    ByteBuffer in = aEnc.getInputBuffer(idx);
                    if (in != null) {
                        in.clear();
                        in.put(pcm, 0, n);
                        long pts = frames * 1000000L / SAMPLE_RATE;
                        aEnc.queueInputBuffer(idx, 0, n, pts, 0);
                    }
                }
                frames += n / 4; // stereo, 16 bit
            } catch (Exception e) {
                break;
            }

            drainAudioOutput(info);
        }
        try {
            audio.stop();
        } catch (Exception ignored) {
        }
    }

    private void drainAudioOutput(MediaCodec.BufferInfo info) {
        while (true) {
            int idx;
            try {
                idx = aEnc.dequeueOutputBuffer(info, 0);
            } catch (Exception e) {
                return;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (lock) {
                    // MediaMuxer refuses addTrack() after start()
                    if (!muxStarted) {
                        aTrack = muxer.addTrack(aEnc.getOutputFormat());
                        maybeStartMuxer();
                    }
                }
            } else if (idx >= 0) {
                ByteBuffer buf = aEnc.getOutputBuffer(idx);
                if (buf != null && info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    synchronized (lock) {
                        if (muxStarted && aTrack >= 0) {
                            muxer.writeSampleData(aTrack, buf, info);
                        }
                    }
                }
                aEnc.releaseOutputBuffer(idx, false);
            } else {
                return;
            }
        }
    }

    private void maybeStartMuxer() {
        // caller holds lock
        if (muxStarted || vTrack < 0) return;
        if (audioWanted && !audioFailed && aTrack < 0) return;
        if (!audioWanted && aTrack < 0) audioFailed = true;
        muxer.start();
        muxStarted = true;
    }

    private void fail() {
        stop(false);
        listener.onError("Ghi màn hình bị lỗi");
    }

    // ------------------------------------------------------------ stop

    void stop() {
        stop(true);
    }

    private void stop(boolean save) {
        if (!running && vEnc == null) return;
        running = false;
        try {
            if (display != null) {
                display.release();
                display = null;
            }
        } catch (Exception ignored) {
        }
        join(videoThread);
        join(audioThread);

        try {
            if (vEnc != null) {
                vEnc.stop();
                vEnc.release();
                vEnc = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (aEnc != null) {
                aEnc.stop();
                aEnc.release();
                aEnc = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (inputSurface != null) {
                inputSurface.release();
                inputSurface = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (audio != null) {
                audio.release();
                audio = null;
            }
        } catch (Exception ignored) {
        }

        boolean ok = false;
        try {
            synchronized (lock) {
                if (muxStarted) {
                    muxer.stop();
                    ok = true;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            if (muxer != null) {
                muxer.release();
                muxer = null;
            }
        } catch (Exception ignored) {
        }

        if (!save) return;
        if (ok) listener.onSaved(outFile);
        else {
            outFile.delete();
            listener.onError("Không có dữ liệu để lưu");
        }
    }

    private static void join(Thread t) {
        if (t == null) return;
        try {
            t.join(1200);
        } catch (InterruptedException ignored) {
        }
    }
}
