/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.floens.chan.ui.view;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;
import android.view.MotionEvent;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.TimeBar;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader;
import com.android.volley.toolbox.ImageLoader.ImageContainer;
import com.davemorrissey.labs.subscaleview.ImageSource;

import org.floens.chan.R;
import org.floens.chan.core.cache.FileCache;
import org.floens.chan.core.cache.FileCacheDownloader;
import org.floens.chan.core.cache.FileCacheListener;
import org.floens.chan.core.cache.FileCacheProvider;
import org.floens.chan.core.di.UserAgentProvider;
import org.floens.chan.core.model.PostImage;
import org.floens.chan.core.settings.ChanSettings;
import org.floens.chan.ui.activity.StartActivity;
import org.floens.chan.utils.AndroidUtils;
import org.floens.chan.utils.Logger;

import java.io.File;
import java.io.IOException;

import javax.inject.Inject;

import pl.droidsonroids.gif.GifDrawable;
import pl.droidsonroids.gif.GifImageView;

import static org.floens.chan.Chan.inject;

@UnstableApi
public class MultiImageView extends FrameLayout implements View.OnClickListener, LifecycleObserver {
    public enum Mode {
        UNLOADED, LOWRES, BIGIMAGE, GIF, MOVIE, OTHER
    }

    private static final String TAG = "MultiImageView";
    private static final int BACKGROUND_COLOR = Color.argb(255, 211, 217, 241);

    @Inject FileCache fileCache;
    @Inject ImageLoader imageLoader;
    @Inject UserAgentProvider userAgent;

    private ImageView playView;

    private PostImage postImage;
    private Callback callback;
    private Mode mode = Mode.UNLOADED;

    private boolean hasContent = false;
    private ImageContainer thumbnailRequest;
    private FileCacheDownloader bigImageRequest;
    private FileCacheDownloader gifRequest;
    private FileCacheDownloader videoRequest;

    // Legacy VideoView path (non-ExoPlayer)
    private VideoView videoView;
    private boolean videoError = false;
    private MediaPlayer mediaPlayer;

    // Media3 ExoPlayer path
    private PlayerView exoVideoView;
    private ExoPlayer exoPlayer;

    private boolean backgroundToggle;

    // Hold-to-speed: gesture detector for long-press on the ExoPlayer view
    private boolean holdSpeedActive = false;

    // Custom video control strip (lives below the PlayerView surface)
    private LinearLayout exoControlStrip;
    private ImageButton exoPlayPause;
    private DefaultTimeBar exoTimeBar;
    private TextView exoPosition;
    private ImageButton exoRewind;
    private ImageButton exoForward;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable positionUpdater = new Runnable() {
        @Override public void run() {
            if (exoPlayer != null && exoTimeBar != null) {
                long pos = exoPlayer.getCurrentPosition();
                long dur = exoPlayer.getDuration();
                long buf = exoPlayer.getBufferedPosition();
                exoTimeBar.setPosition(pos);
                exoTimeBar.setDuration(dur > 0 ? dur : 0);
                exoTimeBar.setBufferedPosition(buf);
                exoPosition.setText(formatMs(pos) + " / " + formatMs(dur > 0 ? dur : 0));
            }
            uiHandler.postDelayed(this, 250);
        }
    };

    private static String formatMs(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    public MultiImageView(Context context) {
        this(context, null);
    }

    public MultiImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MultiImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        inject(this);
        setOnClickListener(this);

        playView = new ImageView(getContext());
        playView.setVisibility(View.GONE);
        playView.setImageResource(R.drawable.ic_play_circle_outline_white_48dp);
        addView(playView, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    public void bindPostImage(PostImage postImage, Callback callback) {
        this.postImage = postImage;
        this.callback = callback;
        playView.setVisibility(postImage.type == PostImage.Type.MOVIE ? View.VISIBLE : View.GONE);
    }

    public PostImage getPostImage() {
        return postImage;
    }

    public void setMode(final Mode newMode, boolean center) {
        if (this.mode != newMode) {
            this.mode = newMode;
            AndroidUtils.waitForMeasure(this, view -> {
                switch (newMode) {
                    case LOWRES:
                        setThumbnail(postImage.getThumbnailUrl().toString(), center);
                        break;
                    case BIGIMAGE:
                        setBigImage(postImage.imageUrl.toString());
                        break;
                    case GIF:
                        setGif(postImage.imageUrl.toString());
                        break;
                    case MOVIE:
                        setVideo(postImage.imageUrl.toString());
                        break;
                    case OTHER:
                        setOther(postImage.imageUrl.toString());
                        break;
                }
                return true;
            });
        }
    }

    public Mode getMode() {
        return mode;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public CustomScaleImageView findScaleImageView() {
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof CustomScaleImageView) {
                return (CustomScaleImageView) getChildAt(i);
            }
        }
        return null;
    }

    public GifImageView findGifImageView() {
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof GifImageView) {
                return (GifImageView) getChildAt(i);
            }
        }
        return null;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private void onActivityPause() {
        pauseVideoPlayback();
    }

    /** Stop playback without releasing the player — called when swiping away. */
    public void pauseVideoPlayback() {
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(false);
        } else if (videoView != null) {
            videoView.pause();
        }
    }

    public void setVolume(boolean muted) {
        final float volume = muted ? 0f : 1f;
        if (exoPlayer != null) {
            exoPlayer.setVolume(volume);
        } else if (mediaPlayer != null) {
            mediaPlayer.setVolume(volume, volume);
        }
    }

    @Override
    public void onClick(View v) {
        callback.onTap(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ((StartActivity) getContext()).getLifecycle().addObserver(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ((StartActivity) getContext()).getLifecycle().removeObserver(this);
        cancelLoad();
    }

    private void setThumbnail(String thumbnailUrl, boolean center) {
        if (getWidth() == 0 || getHeight() == 0) {
            Logger.e(TAG, "getWidth() or getHeight() returned 0, not loading thumbnail");
            return;
        }
        if (thumbnailRequest != null) return;

        thumbnailRequest = imageLoader.get(thumbnailUrl, new ImageLoader.ImageListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                thumbnailRequest = null;
                if (center) onError(error);
            }

            @Override
            public void onResponse(ImageContainer response, boolean isImmediate) {
                thumbnailRequest = null;
                if (response.getBitmap() != null && (!hasContent || mode == Mode.LOWRES)) {
                    ImageView thumbnail = new ImageView(getContext());
                    thumbnail.setImageBitmap(response.getBitmap());
                    onModeLoaded(Mode.LOWRES, thumbnail);
                }
            }
        }, getWidth(), getHeight());

        if (thumbnailRequest != null && thumbnailRequest.getBitmap() != null) {
            thumbnailRequest = null;
        }
    }

    private void setBigImage(String imageUrl) {
        if (getWidth() == 0 || getHeight() == 0) {
            Logger.e(TAG, "getWidth() or getHeight() returned 0, not loading big image");
            return;
        }
        if (bigImageRequest != null) return;

        callback.showProgress(this, true);
        bigImageRequest = fileCache.downloadFile(imageUrl, new FileCacheListener() {
            @Override public void onProgress(long downloaded, long total) {
                callback.onProgress(MultiImageView.this, downloaded, total);
            }
            @Override public void onSuccess(File file) { setBigImageFile(file); }
            @Override public void onFail(boolean notFound) {
                if (notFound) onNotFoundError(); else onError(new Exception());
            }
            @Override public void onCancel() {}
            @Override public void onEnd() {
                bigImageRequest = null;
                callback.showProgress(MultiImageView.this, false);
            }
        });
    }

    private void setBigImageFile(File file) {
        setBitImageFileInternal(file, true, Mode.BIGIMAGE);
    }

    private void setGif(String gifUrl) {
        if (getWidth() == 0 || getHeight() == 0) {
            Logger.e(TAG, "getWidth() or getHeight() returned 0, not loading gif");
            return;
        }
        if (gifRequest != null) return;

        callback.showProgress(this, true);
        gifRequest = fileCache.downloadFile(gifUrl, new FileCacheListener() {
            @Override public void onProgress(long downloaded, long total) {
                callback.onProgress(MultiImageView.this, downloaded, total);
            }
            @Override public void onSuccess(File file) {
                if (!hasContent || mode == Mode.GIF) setGifFile(file);
            }
            @Override public void onFail(boolean notFound) {
                if (notFound) onNotFoundError(); else onError(new Exception());
            }
            @Override public void onCancel() {}
            @Override public void onEnd() {
                gifRequest = null;
                callback.showProgress(MultiImageView.this, false);
            }
        });
    }

    private void setGifFile(File file) {
        GifDrawable drawable;
        try {
            drawable = new GifDrawable(file.getAbsolutePath());
            if (drawable.getNumberOfFrames() == 1) {
                drawable.recycle();
                setBitImageFileInternal(file, false, Mode.GIF);
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
            onError(new Exception());
            return;
        } catch (OutOfMemoryError e) {
            Runtime.getRuntime().gc();
            e.printStackTrace();
            onOutOfMemoryError();
            return;
        }

        GifImageView view = new GifImageView(getContext());
        view.setImageDrawable(drawable);
        onModeLoaded(Mode.GIF, view);
    }

    private void setVideo(String videoUrl) {
        if (videoRequest != null) return;

        callback.showProgress(this, true);
        videoRequest = fileCache.downloadFile(videoUrl, new FileCacheListener() {
            @Override public void onProgress(long downloaded, long total) {
                callback.onProgress(MultiImageView.this, downloaded, total);
            }
            @Override public void onSuccess(File file) {
                if (!hasContent || mode == Mode.MOVIE) setVideoFile(file);
            }
            @Override public void onFail(boolean notFound) {
                if (notFound) onNotFoundError(); else onError(new Exception());
            }
            @Override public void onCancel() {}
            @Override public void onEnd() {
                videoRequest = null;
                callback.showProgress(MultiImageView.this, false);
            }
        });
    }

    private void setOther(String fileUrl) {
        Toast.makeText(getContext(), R.string.file_not_viewable, Toast.LENGTH_LONG).show();
    }

    private void setVideoFile(final File file) {
        if (ChanSettings.videoOpenExternal.get()) {
            // Open in system video player
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(FileCacheProvider.getUriForFile(file), "video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            AndroidUtils.openIntent(intent);
            onModeLoaded(Mode.MOVIE, null);

        } else if (ChanSettings.videoUseExoplayer.get()) {
            // ── Build player ────────────────────────────────────────────────
            exoPlayer = new ExoPlayer.Builder(getContext())
                    .setSeekForwardIncrementMs(5_000)
                    .setSeekBackIncrementMs(5_000)
                    .build();
            exoPlayer.setRepeatMode(ChanSettings.videoAutoLoop.get()
                    ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);

            // ── Video surface: PlayerView with controller completely disabled ──
            // The built-in controller overlays the video and cannot be reliably
            // positioned below the frame. We build our own strip instead.
            exoVideoView = new PlayerView(getContext());
            exoVideoView.setUseController(false);
            exoVideoView.setPlayer(exoPlayer);

            // ── Custom control strip ─────────────────────────────────────────
            // Lives in a separate LinearLayout anchored below the video surface.
            // Nothing overlaps the video content.
            int dp48 = (int) (48 * getResources().getDisplayMetrics().density);
            int dp4  = (int) ( 4 * getResources().getDisplayMetrics().density);

            // ── Buttons row: [left spacer] [⏮ ⏯ ⏭ centered] [right: timestamp] ──
            // Three-column trick: weight-1 left pad | wrap_content centre | weight-1 right
            // guarantees the three buttons sit exactly in the middle regardless of
            // how wide the timestamp is.
            LinearLayout buttonsRow = new LinearLayout(getContext());
            buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
            buttonsRow.setBackgroundColor(0xCC000000);
            buttonsRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            buttonsRow.setPadding(dp4, 0, dp4, 0);

            // Left weight spacer
            android.widget.Space leftSpacer = new android.widget.Space(getContext());
            buttonsRow.addView(leftSpacer,
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

            // Centre: three buttons grouped
            LinearLayout btnGroup = new LinearLayout(getContext());
            btnGroup.setOrientation(LinearLayout.HORIZONTAL);
            btnGroup.setGravity(android.view.Gravity.CENTER);

            exoRewind = new ImageButton(getContext());
            exoRewind.setImageResource(android.R.drawable.ic_media_rew);
            exoRewind.setBackgroundColor(0x00000000);
            exoRewind.setOnClickListener(vv -> {
                if (exoPlayer != null)
                    exoPlayer.seekTo(Math.max(0, exoPlayer.getCurrentPosition() - 5_000));
            });

            exoPlayPause = new ImageButton(getContext());
            exoPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            exoPlayPause.setBackgroundColor(0x00000000);
            exoPlayPause.setOnClickListener(vv -> {
                if (exoPlayer != null)
                    exoPlayer.setPlayWhenReady(!exoPlayer.getPlayWhenReady());
            });

            exoForward = new ImageButton(getContext());
            exoForward.setImageResource(android.R.drawable.ic_media_ff);
            exoForward.setBackgroundColor(0x00000000);
            exoForward.setOnClickListener(vv -> {
                if (exoPlayer != null) {
                    long dur = exoPlayer.getDuration();
                    long target = exoPlayer.getCurrentPosition() + 5_000;
                    exoPlayer.seekTo(dur > 0 ? Math.min(target, dur) : target);
                }
            });

            btnGroup.addView(exoRewind);
            btnGroup.addView(exoPlayPause);
            btnGroup.addView(exoForward);
            buttonsRow.addView(btnGroup,
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT));

            // Right weight spacer + timestamp right-aligned
            LinearLayout rightSide = new LinearLayout(getContext());
            rightSide.setOrientation(LinearLayout.HORIZONTAL);
            rightSide.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);

            exoPosition = new TextView(getContext());
            exoPosition.setTextColor(0xFFFFFFFF);
            exoPosition.setTextSize(11f);
            exoPosition.setPadding(dp4, 0, dp4, 0);
            exoPosition.setText("0:00 / 0:00");
            rightSide.addView(exoPosition);

            buttonsRow.addView(rightSide,
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

            // ── Seekbar row ──────────────────────────────────────────────────
            LinearLayout seekRow = new LinearLayout(getContext());
            seekRow.setOrientation(LinearLayout.HORIZONTAL);
            seekRow.setBackgroundColor(0xCC000000);
            seekRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            seekRow.setPadding(dp4, 0, dp4, dp4);

            exoTimeBar = new DefaultTimeBar(getContext(), null);
            LinearLayout.LayoutParams timeBarParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            exoTimeBar.setLayoutParams(timeBarParams);
            exoTimeBar.addListener(new TimeBar.OnScrubListener() {
                @Override public void onScrubStart(TimeBar bar, long position) {
                    uiHandler.removeCallbacks(positionUpdater);
                }
                @Override public void onScrubMove(TimeBar bar, long position) {
                    if (exoPosition != null) exoPosition.setText(formatMs(position) + " / "
                            + formatMs(exoPlayer != null && exoPlayer.getDuration() > 0
                            ? exoPlayer.getDuration() : 0));
                }
                @Override public void onScrubStop(TimeBar bar, long position, boolean cancelled) {
                    if (!cancelled && exoPlayer != null) exoPlayer.seekTo(position);
                    uiHandler.post(positionUpdater);
                }
            });
            seekRow.addView(exoTimeBar);

            // exoControlStrip is kept as a reference for cleanup; points to buttonsRow
            exoControlStrip = buttonsRow;

            // ── Touch overlay: transparent view over the video surface ────────
            // Instant speed-up on finger-down, instant restore on finger-up.
            // No delay — the overlay owns the full touch sequence.
            View touchOverlay = new View(getContext());
            touchOverlay.setBackgroundColor(0x00000000);
            touchOverlay.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    if (exoPlayer != null && exoPlayer.isPlaying()) {
                        float speed = ChanSettings.videoHoldSpeed.get() / 10f;
                        exoPlayer.setPlaybackParameters(new PlaybackParameters(speed, 1.0f));
                        holdSpeedActive = true;
                    }
                    return true; // claim the sequence so UP is guaranteed
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    if (holdSpeedActive) {
                        if (exoPlayer != null) {
                            exoPlayer.setPlaybackParameters(PlaybackParameters.DEFAULT);
                        }
                        holdSpeedActive = false;
                    }
                }
                return true;
            });

            // ── Outer container: [video+overlay] → buttons → seekbar ────────────
            FrameLayout videoFrame = new FrameLayout(getContext());
            videoFrame.addView(exoVideoView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            videoFrame.addView(touchOverlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            LinearLayout videoContainer = new LinearLayout(getContext());
            videoContainer.setOrientation(LinearLayout.VERTICAL);

            LinearLayout.LayoutParams surfaceParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            videoContainer.addView(videoFrame, surfaceParams);
            videoContainer.addView(buttonsRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp48));
            videoContainer.addView(seekRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            // ── Player listener ──────────────────────────────────────────────
            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        callback.onVideoLoaded(MultiImageView.this);
                        if (exoPlayer.getAudioFormat() != null) {
                            callback.onAudioLoaded(MultiImageView.this);
                        }
                        // Show/hide skip buttons for very short clips
                        long dur = exoPlayer.getDuration();
                        boolean showSkip = dur < 0 || dur > 10_000;
                        exoRewind.setVisibility(showSkip ? VISIBLE : GONE);
                        exoForward.setVisibility(showSkip ? VISIBLE : GONE);
                        exoTimeBar.setDuration(dur > 0 ? dur : 0);
                        uiHandler.post(positionUpdater);
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (exoPlayPause != null) {
                        exoPlayPause.setImageResource(isPlaying
                                ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play);
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Logger.e(TAG, "ExoPlayer error: " + error.getErrorCodeName()
                            + " (" + error.errorCode + ")", error);
                    onVideoError();
                }
            });





            MediaItem mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(file));
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);

            addView(videoContainer);
            onModeLoaded(Mode.MOVIE, videoContainer);

        } else {
            // --- Legacy VideoView path (system MediaPlayer) ---
            Context proxyContext = new NoMusicServiceCommandContext(getContext());
            videoView = new VideoView(proxyContext);
            videoView.setZOrderOnTop(true);
            videoView.setMediaController(new MediaController(getContext()));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                videoView.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
            }

            addView(videoView, 0, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));

            videoView.setOnPreparedListener(mp -> {
                mediaPlayer = mp;
                mp.setLooping(ChanSettings.videoAutoLoop.get());
                mp.setVolume(0f, 0f);
                onModeLoaded(Mode.MOVIE, videoView);
                callback.onVideoLoaded(this);
                if (hasMediaPlayerAudioTracks(mp)) {
                    callback.onAudioLoaded(this);
                }
            });

            videoView.setOnErrorListener((mp, what, extra) -> {
                Logger.e(TAG, "VideoView error: what=" + what + " extra=" + extra);
                onVideoError();
                return true;
            });

            videoView.setVideoPath(file.getAbsolutePath());
            try {
                videoView.start();
            } catch (IllegalStateException e) {
                Logger.e(TAG, "VideoView start error", e);
                onVideoError();
            }
        }
    }

    private boolean hasMediaPlayerAudioTracks(MediaPlayer mp) {
        try {
            for (MediaPlayer.TrackInfo track : mp.getTrackInfo()) {
                if (track.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            // Some Samsung devices throw RuntimeException from getTrackInfo()
            return true;
        }
    }

    private void onVideoError() {
        if (!videoError) {
            videoError = true;
            callback.onVideoError(this);
        }
    }

    private void cleanupVideo(VideoView videoView) {
        videoView.stopPlayback();
        mediaPlayer = null;
    }

    private void cleanupVideo(PlayerView playerView) {
        uiHandler.removeCallbacks(positionUpdater);
        if (playerView.getPlayer() != null) {
            playerView.getPlayer().release();
        }
    }

    public void toggleTransparency() {
        CustomScaleImageView imageView = findScaleImageView();
        GifImageView gifView = findGifImageView();
        if (imageView == null && gifView == null) return;
        boolean isImage = imageView != null && gifView == null;
        int backgroundColor = backgroundToggle ? Color.TRANSPARENT : BACKGROUND_COLOR;
        if (isImage) {
            imageView.setTileBackgroundColor(backgroundColor);
        } else {
            gifView.getDrawable().setColorFilter(backgroundColor, PorterDuff.Mode.DST_OVER);
        }
        backgroundToggle = !backgroundToggle;
    }

    private void setBitImageFileInternal(File file, boolean tiling, final Mode forMode) {
        final CustomScaleImageView image = new CustomScaleImageView(getContext());
        image.setImage(ImageSource.uri(file.getAbsolutePath()).tiling(tiling));
        image.setOnClickListener(MultiImageView.this);
        addView(image, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        image.setCallback(new CustomScaleImageView.Callback() {
            @Override public void onReady() {
                if (!hasContent || mode == forMode) {
                    callback.showProgress(MultiImageView.this, false);
                    onModeLoaded(Mode.BIGIMAGE, image);
                }
            }
            @Override public void onError(boolean wasInitial) {
                onBigImageError(wasInitial);
            }
        });
    }

    private void onError(Exception e) {
        String message = getContext().getString(R.string.image_preview_failed);
        String extra = e.getMessage() == null ? "" : ": " + e.getMessage();
        Toast.makeText(getContext(), message + extra, Toast.LENGTH_SHORT).show();
        callback.showProgress(this, false);
    }

    private void onNotFoundError() {
        callback.showProgress(this, false);
        Toast.makeText(getContext(), R.string.image_not_found, Toast.LENGTH_SHORT).show();
    }

    private void onOutOfMemoryError() {
        Toast.makeText(getContext(), R.string.image_preview_failed_oom, Toast.LENGTH_SHORT).show();
        callback.showProgress(this, false);
    }

    private void onBigImageError(boolean wasInitial) {
        if (wasInitial) {
            Toast.makeText(getContext(), R.string.image_failed_big_image, Toast.LENGTH_SHORT).show();
            callback.showProgress(this, false);
        }
    }

    public void cancelLoad() {
        if (thumbnailRequest != null) { thumbnailRequest.cancelRequest(); thumbnailRequest = null; }
        if (bigImageRequest != null) { bigImageRequest.cancel(); bigImageRequest = null; }
        if (gifRequest != null) { gifRequest.cancel(); gifRequest = null; }
        if (videoRequest != null) { videoRequest.cancel(); videoRequest = null; }
        uiHandler.removeCallbacks(positionUpdater);
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        exoControlStrip = null;
        exoPlayPause = null;
        exoTimeBar = null;
        exoPosition = null;
    }

    private void onModeLoaded(Mode mode, View view) {
        if (view != null) {
            boolean alreadyAttached = false;
            for (int i = getChildCount() - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (child != playView) {
                    if (child != view) {
                        if (child instanceof VideoView) {
                            cleanupVideo((VideoView) child);
                        } else if (child instanceof PlayerView) {
                            cleanupVideo((PlayerView) child);
                        }
                        removeViewAt(i);
                    } else {
                        alreadyAttached = true;
                    }
                }
            }
            if (!alreadyAttached) {
                addView(view, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
        }

        hasContent = true;
        callback.onModeLoaded(this, mode);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child instanceof GifImageView) {
            GifImageView gif = (GifImageView) child;
            if (gif.getDrawable() instanceof GifDrawable) {
                GifDrawable drawable = (GifDrawable) gif.getDrawable();
                if (drawable.getFrameByteCount() > 100 * 1024 * 1024) {
                    onError(new Exception("Uncompressed GIF too large (>100MB)"));
                    return false;
                }
            }
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    public interface Callback {
        void onTap(MultiImageView multiImageView);
        void showProgress(MultiImageView multiImageView, boolean progress);
        void onProgress(MultiImageView multiImageView, long current, long total);
        void onVideoError(MultiImageView multiImageView);
        void onVideoLoaded(MultiImageView multiImageView);
        void onModeLoaded(MultiImageView multiImageView, Mode mode);
        void onAudioLoaded(MultiImageView multiImageView);
    }

    /**
     * Wraps the context to suppress music-service pause broadcasts that would
     * interrupt other apps' audio when Clover starts a video.
     */
    public static class NoMusicServiceCommandContext extends ContextWrapper {
        public NoMusicServiceCommandContext(Context base) { super(base); }

        @Override
        public void sendBroadcast(Intent intent) {
            if (!"com.android.music.musicservicecommand".equals(intent.getAction())) {
                super.sendBroadcast(intent);
            }
        }
    }
}
