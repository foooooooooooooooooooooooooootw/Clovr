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
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;

import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader;
import com.android.volley.toolbox.ImageLoader.ImageContainer;
import com.davemorrissey.labs.subscaleview.ImageSource;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

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

public class MultiImageView extends FrameLayout implements View.OnClickListener, LifecycleObserver {
    public enum Mode {
        UNLOADED, LOWRES, BIGIMAGE, GIF, MOVIE, OTHER
    }

    private static final String TAG = "MultiImageView";
    //for checkstyle to not be dumb about local final vars
    private static final int BACKGROUND_COLOR = Color.argb(255, 211, 217, 241);

    @Inject
    FileCache fileCache;

    @Inject
    ImageLoader imageLoader;

    @Inject
    UserAgentProvider userAgent;

    private ImageView playView;

    private PostImage postImage;
    private Callback callback;
    private Mode mode = Mode.UNLOADED;

    private boolean hasContent = false;
    private ImageContainer thumbnailRequest;
    private FileCacheDownloader bigImageRequest;
    private FileCacheDownloader gifRequest;
    private FileCacheDownloader videoRequest;

    private VideoView videoView;
    private PlayerView exoVideoView;
    private boolean videoError = false;
    private MediaPlayer mediaPlayer;
    private ExoPlayer exoPlayer;

    private boolean backgroundToggle;

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
        addView(playView, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
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
//            Logger.test("Changing mode from " + this.mode + " to " + newMode + " for " + postImage.thumbnailUrl);
            this.mode = newMode;

            AndroidUtils.waitForMeasure(this, new AndroidUtils.OnMeasuredCallback() {
                @Override
                public boolean onMeasured(View view) {
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
                }
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
        CustomScaleImageView bigImage = null;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof CustomScaleImageView) {
                bigImage = (CustomScaleImageView) getChildAt(i);
            }
        }
        return bigImage;
    }

    public GifImageView findGifImageView() {
        GifImageView gif = null;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof GifImageView) {
                gif = (GifImageView) getChildAt(i);
            }
        }
        return gif;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private void pauseVideo() {
        if (exoPlayer != null) {
            exoPlayer.setPlaybackParameters(PlaybackParameters.DEFAULT);
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
            Logger.e(TAG, "getWidth() or getHeight() returned 0, not loading");
            return;
        }

        if (thumbnailRequest != null) {
            return;
        }

        // Also use volley for the thumbnails
        thumbnailRequest = imageLoader.get(thumbnailUrl, new ImageLoader.ImageListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                thumbnailRequest = null;
                if (center) {
                    onError(error);
                }
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

        if (thumbnailRequest.getBitmap() != null) {
            // Request was immediate and thumbnailRequest was first set to null in onResponse, and then set to the container
            // when the method returned
            // Still set it to null here
            thumbnailRequest = null;
        }
    }

    private void setBigImage(String imageUrl) {
        if (getWidth() == 0 || getHeight() == 0) {
            Logger.e(TAG, "getWidth() or getHeight() returned 0, not loading big image");
            return;
        }

        if (bigImageRequest != null) {
            return;
        }

        callback.showProgress(this, true);
        bigImageRequest = fileCache.downloadFile(imageUrl, new FileCacheListener() {
            @Override
            public void onProgress(long downloaded, long total) {
                callback.onProgress(MultiImageView.this, downloaded, total);
            }

            @Override
            public void onSuccess(File file) {
                setBigImageFile(file);
            }

            @Override
            public void onFail(boolean notFound) {
                if (notFound) {
                    onNotFoundError();
                } else {
                    onError(new Exception());
                }
            }

            @Override
            public void onCancel() {
            }

            @Override
            public void onEnd() {
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
            Logger.e(TAG, "getWidth() or getHeight() returned 0, not loading");
            return;
        }

        if (gifRequest != null) {
            return;
        }

        callback.showProgress(this, true);
        gifRequest = fileCache.downloadFile(gifUrl, new FileCacheListener() {
            @Override
            public void onProgress(long downloaded, long total) {
                callback.onProgress(MultiImageView.this, downloaded, total);
            }

            @Override
            public void onSuccess(File file) {
                if (!hasContent || mode == Mode.GIF) {
                    setGifFile(file);
                }
            }

            @Override
            public void onFail(boolean notFound) {
                if (notFound) {
                    onNotFoundError();
                } else {
                    onError(new Exception());
                }
            }

            @Override
            public void onCancel() {
            }

            @Override
            public void onEnd() {
                gifRequest = null;
                callback.showProgress(MultiImageView.this, false);
            }
        });
    }

    private void setGifFile(File file) {
        GifDrawable drawable;
        try {
            drawable = new GifDrawable(file.getAbsolutePath());

            // For single frame gifs, use the scaling image instead
            // The region decoder doesn't work for gifs, so we unfortunately
            // have to use the more memory intensive non tiling mode.
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
        if (videoRequest != null) {
            return;
        }

        callback.showProgress(this, true);
        videoRequest = fileCache.downloadFile(videoUrl, new FileCacheListener() {
            @Override
            public void onProgress(long downloaded, long total) {
                callback.onProgress(MultiImageView.this, downloaded, total);
            }

            @Override
            public void onSuccess(File file) {
                if (!hasContent || mode == Mode.MOVIE) {
                    setVideoFile(file);
                }
            }

            @Override
            public void onFail(boolean notFound) {
                if (notFound) {
                    onNotFoundError();
                } else {
                    onError(new Exception());
                }
            }

            @Override
            public void onCancel() {
            }

            @Override
            public void onEnd() {
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
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(FileCacheProvider.getUriForFile(file), "video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            AndroidUtils.openIntent(intent);

            onModeLoaded(Mode.MOVIE, videoView);
        } else if (ChanSettings.videoUseExoplayer.get()) {
            exoVideoView = new PlayerView(getContext());
            exoVideoView.setUseController(false); // disable built-in overlay controls

            exoPlayer = new ExoPlayer.Builder(getContext())
                    .setRenderersFactory(
                            new DefaultRenderersFactory(getContext())
                                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    )
                    .build();
            exoVideoView.setPlayer(exoPlayer);

            exoPlayer.setRepeatMode(ChanSettings.videoAutoLoop.get() ?
                    Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);

            exoPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                    /* handleAudioFocus= */ false);

            MediaItem mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(file));
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();

            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onTracksChanged(Tracks tracks) {
                    if (tracks.containsType(C.TRACK_TYPE_AUDIO)) {
                        callback.onAudioLoaded(MultiImageView.this);
                    }
                }
            });

            // ---- Control bar: two rows inside a vertical LinearLayout ----
            // Row 1: [skip back] [play/pause] [skip forward]  — centred
            // Row 2: seekbar + time text
            android.widget.LinearLayout controlBar = new android.widget.LinearLayout(getContext());
            controlBar.setOrientation(android.widget.LinearLayout.VERTICAL);
            controlBar.setBackgroundColor(0xCC000000);
            controlBar.setPadding(dp(12), dp(6), dp(12), dp(10));

            // -- Row 1: transport buttons --
            android.widget.LinearLayout btnRow = new android.widget.LinearLayout(getContext());
            btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams btnRowParams =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            btnRow.setLayoutParams(btnRowParams);

            android.widget.ImageButton skipBackBtn = new android.widget.ImageButton(getContext());
            skipBackBtn.setBackground(null);
            skipBackBtn.setImageResource(android.R.drawable.ic_media_rew);
            skipBackBtn.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
            skipBackBtn.setPadding(dp(16), dp(4), dp(16), dp(4));

            android.widget.ImageButton playPauseBtn = new android.widget.ImageButton(getContext());
            playPauseBtn.setBackground(null);
            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
            playPauseBtn.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
            playPauseBtn.setPadding(dp(16), dp(4), dp(16), dp(4));

            android.widget.ImageButton skipFwdBtn = new android.widget.ImageButton(getContext());
            skipFwdBtn.setBackground(null);
            skipFwdBtn.setImageResource(android.R.drawable.ic_media_ff);
            skipFwdBtn.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
            skipFwdBtn.setPadding(dp(16), dp(4), dp(16), dp(4));

            btnRow.addView(skipBackBtn);
            btnRow.addView(playPauseBtn);
            btnRow.addView(skipFwdBtn);

            // -- Row 2: seekbar + time --
            android.widget.LinearLayout seekRow = new android.widget.LinearLayout(getContext());
            seekRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            seekRow.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.LinearLayout.LayoutParams seekRowParams =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            seekRowParams.topMargin = dp(2);
            seekRow.setLayoutParams(seekRowParams);

            android.widget.SeekBar seekBar = new android.widget.SeekBar(getContext());
            seekBar.setMax(1000);
            android.widget.LinearLayout.LayoutParams seekBarParams =
                    new android.widget.LinearLayout.LayoutParams(0,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            seekBarParams.setMargins(0, 0, dp(8), 0);
            seekBar.setLayoutParams(seekBarParams);

            android.widget.TextView timeText = new android.widget.TextView(getContext());
            timeText.setTextColor(0xFFFFFFFF);
            timeText.setTextSize(11f);
            timeText.setText("0:00 / 0:00");
            timeText.setMinWidth(dp(80));
            timeText.setGravity(Gravity.END);

            seekRow.addView(seekBar);
            seekRow.addView(timeText);

            controlBar.addView(btnRow);
            controlBar.addView(seekRow);

            // -- Button logic --
            // Skip amount: 10% of duration, min 5s, max 30s
            java.util.concurrent.atomic.AtomicBoolean userSeeking =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            playPauseBtn.setOnClickListener(v -> {
                if (exoPlayer.isPlaying()) {
                    exoPlayer.pause();
                    playPauseBtn.setImageResource(android.R.drawable.ic_media_play);
                } else {
                    exoPlayer.play();
                    playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
                }
            });

            skipBackBtn.setOnClickListener(v -> {
                long dur = exoPlayer.getDuration();
                long skip = dur > 0 ? Math.min(30000, Math.max(5000, dur / 10)) : 10000;
                exoPlayer.seekTo(Math.max(0, exoPlayer.getCurrentPosition() - skip));
            });

            skipFwdBtn.setOnClickListener(v -> {
                long dur = exoPlayer.getDuration();
                long skip = dur > 0 ? Math.min(30000, Math.max(5000, dur / 10)) : 10000;
                exoPlayer.seekTo(Math.min(dur, exoPlayer.getCurrentPosition() + skip));
            });

            seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && exoPlayer.getDuration() > 0) {
                        exoPlayer.seekTo(progress * exoPlayer.getDuration() / 1000);
                    }
                }
                @Override
                public void onStartTrackingTouch(android.widget.SeekBar sb) {
                    userSeeking.set(true);
                }
                @Override
                public void onStopTrackingTouch(android.widget.SeekBar sb) {
                    userSeeking.set(false);
                }
            });

            // Periodic UI updater
            final android.os.Handler seekHandler =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            final Runnable seekUpdater = new Runnable() {
                @Override
                public void run() {
                    if (exoPlayer != null && !userSeeking.get()) {
                        long dur = exoPlayer.getDuration();
                        long pos = exoPlayer.getCurrentPosition();
                        if (dur > 0) {
                            seekBar.setProgress((int) (pos * 1000 / dur));
                        }
                        timeText.setText(formatTime(pos) + " / " + formatTime(Math.max(0, dur)));
                    }
                    seekHandler.postDelayed(this, 200);
                }
            };
            seekHandler.post(seekUpdater);

            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) {
                        playPauseBtn.setImageResource(android.R.drawable.ic_media_play);
                    }
                    if (state == Player.STATE_READY) {
                        long dur = exoPlayer.getDuration();
                        timeText.setText(formatTime(0) + " / " + formatTime(Math.max(0, dur)));
                    }
                }
                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    playPauseBtn.setImageResource(isPlaying
                            ? android.R.drawable.ic_media_pause
                            : android.R.drawable.ic_media_play);
                    if (!isPlaying) seekHandler.removeCallbacks(seekUpdater);
                    else seekHandler.post(seekUpdater);
                }
            });

            addView(exoVideoView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            exoPlayer.setPlayWhenReady(true);

            // Long-press on video (not buttons) to speed up
            exoVideoView.setOnTouchListener((v, event) -> {
                // Don't trigger speed-up if touch is in the control bar area
                float barTop = getHeight() - controlBar.getHeight();
                if (event.getY() >= barTop) return false;
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    float holdSpeed = ChanSettings.videoHoldSpeed.get().getSpeed();
                    exoPlayer.setPlaybackParameters(new PlaybackParameters(holdSpeed));
                }
                return false;
            });

            // Always reset speed on finger lift, regardless of which child consumed the touch
            setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    if (exoPlayer != null) {
                        exoPlayer.setPlaybackParameters(PlaybackParameters.DEFAULT);
                    }
                }
                return false;
            });

            onModeLoaded(Mode.MOVIE, exoVideoView);

            // Add control bar AFTER onModeLoaded so it isn't removed by view cleanup
            FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            addView(controlBar, barParams);
            callback.onVideoLoaded(this);
        } else {
            Context proxyContext = new NoMusicServiceCommandContext(getContext());

            videoView = new VideoView(proxyContext);
            videoView.setZOrderOnTop(true);
            videoView.setMediaController(new MediaController(getContext()));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                videoView.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
            }

            addView(videoView, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));

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
                onVideoError();

                return true;
            });

            videoView.setVideoPath(file.getAbsolutePath());

            try {
                videoView.start();
            } catch (IllegalStateException e) {
                Logger.e(TAG, "Video view start error", e);
                onVideoError();
            }
        }
    }

    private int dp(float dp) {
        return (int) (dp * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    private String formatTime(long ms) {
        if (ms < 0) return "0:00";
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return minutes + ":" + String.format("%02d", seconds);
    }

    private boolean hasMediaPlayerAudioTracks(MediaPlayer mediaPlayer) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                for (MediaPlayer.TrackInfo trackInfo : mediaPlayer.getTrackInfo()) {
                    if (trackInfo.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                        return true;
                    }
                }

                return false;
            } else {
                // It'll just show the icon without doing anything. Remove when 4.0 is dropped.
                return true;
            }
        } catch (RuntimeException e) {
            // getTrackInfo() raises an IllegalStateException on some devices.
            // Samsung even throws a RuntimeException.
            // Return a default value.
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

    private void cleanupVideo(PlayerView videoView) {
        if (videoView.getPlayer() != null) {
            videoView.getPlayer().release();
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
            @Override
            public void onReady() {
                if (!hasContent || mode == forMode) {
                    callback.showProgress(MultiImageView.this, false);
                    onModeLoaded(Mode.BIGIMAGE, image);
                }
            }

            @Override
            public void onError(boolean wasInitial) {
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

    private void cancelLoad() {
        if (thumbnailRequest != null) {
            thumbnailRequest.cancelRequest();
            thumbnailRequest = null;
        }
        if (bigImageRequest != null) {
            bigImageRequest.cancel();
            bigImageRequest = null;
        }
        if (gifRequest != null) {
            gifRequest.cancel();
            gifRequest = null;
        }
        if (videoRequest != null) {
            videoRequest.cancel();
            videoRequest = null;
        }
        if (exoPlayer != null) {
            // ExoPlayer will keep loading resources if we don't release it here.
            exoPlayer.release();
            exoPlayer = null;
        }
    }

    private void onModeLoaded(Mode mode, View view) {
        if (view != null) {
            // Remove all other views
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
                if (drawable.getFrameByteCount() > 100 * 1024 * 1024) { //max size from RecordingCanvas
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

    public static class NoMusicServiceCommandContext extends ContextWrapper {
        public NoMusicServiceCommandContext(Context base) {
            super(base);
        }

        @Override
        public void sendBroadcast(Intent intent) {
            // Only allow broadcasts when it's not a music service command
            // Prevents pause intents from broadcasting
            if (!"com.android.music.musicservicecommand".equals(intent.getAction())) {
                super.sendBroadcast(intent);
            }
        }
    }
}