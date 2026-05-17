/*
 * VideoTranscodeHelper.java
 *
 * Detects videos with odd-numbered width or height (common with VP8/WebM files from
 * imageboards like 4chan) and transcodes them to an even-dimension H.264 MP4 using
 * FFmpegKit. FFmpegKit runs FFmpeg natively and never touches Android's MediaCodec
 * stack, so it works even when the system VP8 decoder refuses to open the file.
 *
 * Results are cached on disk — repeated views of the same file skip the transcode.
 *
 * Usage — play-first, transcode only on error:
 *
 *   // 1. Play the original immediately via ExoPlayer.
 *   // 2. In ExoPlayer's onPlayerError, check dimensions:
 *   int[] dims = VideoTranscodeHelper.getDimensionsSync(originalFile);  // off main thread
 *   if (dims != null && (dims[0] % 2 != 0 || dims[1] % 2 != 0)) {
 *       // Odd dimensions confirmed — transcode and retry.
 *       VideoTranscodeHelper.prepareVideo(context, originalFile, new VideoTranscodeHelper.Callback() {
 *           @Override public void onReady(File videoFile) { retryWithExoPlayer(videoFile); }
 *           @Override public void onError(Exception e)   { showErrorToUser(); }
 *       });
 *   } else {
 *       showErrorToUser(); // not a dimension problem
 *   }
 */

package org.floens.chan.ui.view;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VideoTranscodeHelper {

    public interface Callback {
        /** Called on the main thread when the video is ready to play. */
        void onReady(File videoFile);
        /** Called on the main thread if probing or transcoding fails. */
        void onError(Exception e);
    }

    private static final String TAG = "VideoTranscodeHelper";
    private static final String CACHE_SUBDIR = "transcode_cache";

    // Serialise transcode jobs — no point running multiple FFmpeg instances at once.
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private VideoTranscodeHelper() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Transcodes {@code source} to an even-dimension H.264/MP4 and delivers the result
     * via {@link Callback#onReady} on the main thread.
     *
     * <p>The caller is responsible for confirming that a transcode is actually needed
     * (e.g. odd pixel dimensions detected via {@link #getDimensionsSync}) before calling
     * this method. {@code prepareVideo} itself no longer performs that guard — it always
     * transcodes, but it does check the on-disk cache first so repeated calls for the
     * same file are cheap.
     */
    public static void prepareVideo(@NonNull Context context,
                                    @NonNull File source,
                                    @NonNull Callback callback) {
        sExecutor.execute(() -> {
            int[] dims = getDimensions(source);
            if (dims == null) {
                Log.w(TAG, "Could not read dimensions from " + source.getName()
                        + " — cannot transcode, firing onError.");
                sMainHandler.post(() -> callback.onError(
                        new RuntimeException("Could not read dimensions from " + source.getName())));
                return;
            }

            int targetW = makeEven(dims[0]);
            int targetH = makeEven(dims[1]);
            Log.d(TAG, "Transcoding " + source.getName()
                    + " from " + dims[0] + "x" + dims[1]
                    + " → " + targetW + "x" + targetH);

            File cached = cachedFile(context, source);
            if (cached.exists() && cached.length() > 0) {
                Log.d(TAG, "Cache hit: " + cached.getName());
                sMainHandler.post(() -> callback.onReady(cached));
                return;
            }

            // FFmpegKit.execute() is synchronous — fine on this executor thread.
            transcodeWithFfmpeg(source, cached, targetW, targetH, callback);
        });
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static int makeEven(int n) {
        return (n % 2 == 0) ? n : n - 1;
    }

    private static boolean isEven(int n) {
        return (n % 2) == 0;
    }

    private static File cachedFile(@NonNull Context context, @NonNull File source) {
        File cacheDir = new File(context.getCacheDir(), CACHE_SUBDIR);
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        // Include file size in the name so a re-downloaded file busts the old entry.
        String safeName = source.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
        String cacheName = safeName + "_" + source.length() + "_fixed.mp4";
        return new File(cacheDir, cacheName);
    }

    /**
     * Reads width/height via {@link MediaMetadataRetriever} (reads container headers
     * only — never decodes a frame, so it works fine on files the VP8 codec rejects).
     *
     * @return int[]{width, height} or {@code null} on failure.
     */
    /**
     * Public wrapper so callers (e.g. MultiImageView) can probe dimensions without
     * triggering a full transcode. Runs synchronously — call off the main thread.
     */
    public static int[] getDimensionsSync(@NonNull File file) {
        return getDimensions(file);
    }

    private static int[] getDimensions(@NonNull File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String ws = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (ws == null || hs == null) return null;
            return new int[]{Integer.parseInt(ws), Integer.parseInt(hs)};
        } catch (Exception e) {
            Log.e(TAG, "getDimensions failed", e);
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * Runs FFmpeg synchronously on the executor thread.
     *
     * Command breakdown:
     *   -y                      overwrite output without prompting
     *   -i <input>              source file (VP8/WebM or anything FFmpeg can demux)
     *   -vf crop=W:H:0:0        crop to even dimensions from top-left (trims at most 1px)
     *   -c:v libx264            encode with software x264 — bypasses MediaCodec entirely
     *   -preset ultrafast       fastest x264 preset; quality is fine for a preview copy
     *   -crf 18                 near-lossless (0=lossless, 51=worst); tweak if file size matters
     *   -c:a copy               stream-copy audio unchanged
     *   -movflags +faststart    shift MP4 index to front for instant playback start
     *   <output>                destination .mp4 path
     */
    private static void transcodeWithFfmpeg(@NonNull File source,
                                            @NonNull File output,
                                            int targetW,
                                            int targetH,
                                            @NonNull Callback callback) {
        if (output.exists()) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
        }

        // This FFmpeg Kit build has --enable-mediacodec but no external codec libs
        // (no libx264, no libvpx). FFmpeg decodes VP8 natively; we re-encode via
        // the device's hardware H264 encoder through MediaCodec.
        //
        // Fallback chain:
        //   1. h264_mediacodec — hardware H264, no quality flags, fastest
        //   2. mpeg4           — built-in software, always present, no -crf/-q:v needed
        FFmpegSession session = runCmd(buildCmd(source, output, targetW, targetH, "h264_mediacodec"));

        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            Log.w(TAG, "h264_mediacodec failed, retrying with mpeg4");
            if (output.exists()) //noinspection ResultOfMethodCallIgnored
                output.delete();
            session = runCmd(buildCmd(source, output, targetW, targetH, "mpeg4"));
        }

        if (ReturnCode.isSuccess(session.getReturnCode())) {
            Log.d(TAG, "Transcode complete → " + output.getName()
                    + " (" + output.length() + " bytes)");
            sMainHandler.post(() -> callback.onReady(output));
        } else {
            if (output.exists()) //noinspection ResultOfMethodCallIgnored
                output.delete();
            String logs = session.getLogsAsString();
            Log.e(TAG, "FFmpeg failed (rc=" + session.getReturnCode() + ")\n" + logs);
            FFmpegSession finalSession = session;
            sMainHandler.post(() -> callback.onError(
                    new RuntimeException("FFmpeg transcode failed (rc=" + finalSession.getReturnCode() + ")")));
        }
    }

    /** Builds the FFmpeg crop+encode command for the given encoder string (e.g. "libx264 -crf 18"). */
    private static String buildCmd(@NonNull File src, @NonNull File out,
                                   int w, int h, @NonNull String encoderArgs) {
        String cmd = String.format(
                "-y -i %s -vf crop=%d:%d:0:0 -c:v %s -c:a copy -movflags +faststart %s",
                escapeShellArg(src.getAbsolutePath()),
                w, h,
                encoderArgs,
                escapeShellArg(out.getAbsolutePath()));
        Log.d(TAG, "FFmpeg command: " + cmd);
        return cmd;
    }

    private static FFmpegSession runCmd(@NonNull String cmd) {
        return FFmpegKit.execute(cmd);
    }

    /**
     * Wraps a filesystem path in single quotes and escapes embedded single quotes,
     * making it safe to pass to FFmpegKit even if the path contains spaces.
     */
    private static String escapeShellArg(@NonNull String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    // -------------------------------------------------------------------------
    // Cache housekeeping
    // -------------------------------------------------------------------------

    /**
     * Trims the transcode cache to at most {@code maxBytes}, deleting the oldest
     * files first. Call from Application.onCreate() or a low-storage broadcast.
     */
    public static void trimCache(@NonNull Context context, long maxBytes) {
        sExecutor.execute(() -> {
            File cacheDir = new File(context.getCacheDir(), CACHE_SUBDIR);
            File[] files = cacheDir.listFiles();
            if (files == null || files.length == 0) return;

            long total = 0;
            for (File f : files) total += f.length();
            if (total <= maxBytes) return;

            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

            for (File f : files) {
                if (total <= maxBytes) break;
                long size = f.length();
                if (f.delete()) {
                    total -= size;
                    Log.d(TAG, "Cache trim: deleted " + f.getName());
                }
            }
        });
    }
}