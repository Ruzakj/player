package com.ric.player;

import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends ComponentActivity {
    private static final String KEY_MEDIA = "media";
    private static final String KEY_POS = "pos";
    private static final String KEY_PLAY = "play";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_SCALE = "scale";
    private static final String KEY_ORIENTATION = "orientation";
    private static final String KEY_SUBTITLE = "subtitle";

    private ExoPlayer player;
    private PlayerView playerView;
    private Uri currentMediaUri;
    private Uri externalSubtitleUri;

    private View controlsOverlay;
    private View topBar;
    private LinearLayout centerControls;
    private TextView mediaTitle;
    private TextView currentTime;
    private TextView duration;
    private TextView gestureIndicator;
    private TextView errorState;
    private SeekBar seekBar;
    private Button playPause;
    private Button speedButton;
    private Button scaleButton;
    private Button orientationButton;
    private Button unlockButton;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean controlsVisible = true;
    private boolean locked = false;
    private boolean seeking = false;
    private boolean inPip = false;
    private int scaleMode = 0;
    private int orientationMode = 0;
    private float playbackSpeed = 1.0f;
    private float appBrightness = 0.5f;
    private AudioManager audioManager;
    private GestureDetector gestureDetector;

    private final Runnable progressUpdater = new Runnable() {
        @Override public void run() {
            updateProgress();
            uiHandler.postDelayed(this, 500);
        }
    };

    private final Runnable hideControlsRunnable = () -> {
        if (!locked && !inPip) setControlsVisible(false);
    };

    private final Runnable hideGestureRunnable = () -> gestureIndicator.setVisibility(View.GONE);

    private final ActivityResultLauncher<String[]> mediaPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    persistReadPermission(uri);
                    playNewMedia(uri);
                }
            });

    private final ActivityResultLauncher<String[]> subtitlePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    persistReadPermission(uri);
                    attachExternalSubtitle(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        bindViews();
        initPlayer();
        initControls();
        initGestures();
        styleSubtitleView();

        if (savedInstanceState != null) {
            scaleMode = savedInstanceState.getInt(KEY_SCALE, 0);
            orientationMode = savedInstanceState.getInt(KEY_ORIENTATION, 0);
            playbackSpeed = savedInstanceState.getFloat(KEY_SPEED, 1.0f);
            String media = savedInstanceState.getString(KEY_MEDIA);
            String subtitle = savedInstanceState.getString(KEY_SUBTITLE);
            if (subtitle != null) externalSubtitleUri = Uri.parse(subtitle);
            applyScaleMode(scaleMode);
            applyOrientationMode(orientationMode, false);
            if (media != null) {
                currentMediaUri = Uri.parse(media);
                restoreMedia(currentMediaUri,
                        savedInstanceState.getLong(KEY_POS, 0L),
                        savedInstanceState.getBoolean(KEY_PLAY, true));
            }
        } else {
            handleIntent(getIntent());
        }

        updateResponsiveLayout(getResources().getConfiguration());
        uiHandler.post(progressUpdater);
    }

    private void bindViews() {
        playerView = findViewById(R.id.playerView);
        controlsOverlay = findViewById(R.id.controlsOverlay);
        topBar = findViewById(R.id.topBar);
        centerControls = findViewById(R.id.centerControls);
        mediaTitle = findViewById(R.id.mediaTitle);
        currentTime = findViewById(R.id.currentTime);
        duration = findViewById(R.id.duration);
        gestureIndicator = findViewById(R.id.gestureIndicator);
        errorState = findViewById(R.id.errorState);
        seekBar = findViewById(R.id.seekBar);
        playPause = findViewById(R.id.playPause);
        speedButton = findViewById(R.id.speed);
        scaleButton = findViewById(R.id.scaling);
        orientationButton = findViewById(R.id.orientation);
        unlockButton = findViewById(R.id.unlock);
    }

    private void initPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setUseController(false);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                playPause.setText(isPlaying ? "Pause" : "Play");
            }

            @Override public void onPlaybackStateChanged(int playbackState) {
                updateProgress();
            }

            @Override public void onPlayerError(@NonNull PlaybackException error) {
                showError("Media gagal diputar: " + error.getErrorCodeName());
            }
        });
    }

    private void initControls() {
        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.openFile).setOnClickListener(v ->
                mediaPicker.launch(new String[]{"video/*", "audio/*"}));
        findViewById(R.id.openUrl).setOnClickListener(v -> showUrlDialog());
        findViewById(R.id.pip).setOnClickListener(v -> enterPip());
        findViewById(R.id.subtitleTop).setOnClickListener(v -> showSubtitleMenu());
        findViewById(R.id.subtitle).setOnClickListener(v -> showSubtitleMenu());
        findViewById(R.id.audioTrack).setOnClickListener(v -> showAudioTrackMenu());
        findViewById(R.id.orientation).setOnClickListener(v -> showOrientationMenu());
        findViewById(R.id.scaling).setOnClickListener(v -> showScalingMenu());
        findViewById(R.id.lock).setOnClickListener(v -> setLocked(true));
        unlockButton.setOnClickListener(v -> setLocked(false));

        findViewById(R.id.rewind).setOnClickListener(v -> seekBy(-10_000));
        findViewById(R.id.forward).setOnClickListener(v -> seekBy(10_000));
        findViewById(R.id.previous).setOnClickListener(v -> {
            if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem();
            else player.seekTo(0);
            scheduleAutoHide();
        });
        findViewById(R.id.next).setOnClickListener(v -> {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem();
            scheduleAutoHide();
        });
        playPause.setOnClickListener(v -> {
            if (player.isPlaying()) player.pause(); else player.play();
            scheduleAutoHide();
        });
        speedButton.setOnClickListener(v -> showSpeedMenu());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long d = safeDuration();
                    if (d > 0) currentTime.setText(formatTime((d * progress) / 1000L));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                seeking = true;
                uiHandler.removeCallbacks(hideControlsRunnable);
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                long d = safeDuration();
                if (d > 0) player.seekTo((d * seekBar.getProgress()) / 1000L);
                seeking = false;
                scheduleAutoHide();
            }
        });

        scheduleAutoHide();
    }

    private void initGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(@NonNull MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (locked) {
                    unlockButton.setVisibility(View.VISIBLE);
                } else {
                    setControlsVisible(!controlsVisible);
                    if (controlsVisible) scheduleAutoHide();
                }
                return true;
            }

            @Override public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (locked) return true;
                if (e.getX() < playerView.getWidth() / 2f) seekBy(-10_000);
                else seekBy(10_000);
                return true;
            }

            @Override public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2,
                                              float distanceX, float distanceY) {
                if (locked || e1 == null) return false;
                float totalDx = e2.getX() - e1.getX();
                float totalDy = e2.getY() - e1.getY();
                if (Math.abs(totalDy) < Math.abs(totalDx) * 1.25f || Math.abs(totalDy) < 20f) return false;

                float delta = distanceY / Math.max(1f, playerView.getHeight());
                if (e1.getX() < playerView.getWidth() / 2f) {
                    adjustBrightness(delta * 1.8f);
                } else {
                    adjustVolume(delta * 1.8f);
                }
                return true;
            }
        });
        playerView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    private void showUrlDialog() {
        EditText input = new EditText(this);
        input.setHint("https://.../video.mp4 atau stream.m3u8");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(32, 20, 32, 20);
        new AlertDialog.Builder(this)
                .setTitle("Buka URL media")
                .setView(input)
                .setPositiveButton("Play", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        playNewMedia(Uri.parse(url));
                    } else if (!url.isEmpty()) {
                        showError("URL tidak valid");
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void playNewMedia(Uri uri) {
        currentMediaUri = uri;
        externalSubtitleUri = null;
        errorState.setVisibility(View.GONE);
        mediaTitle.setText(resolveDisplayName(uri));
        player.setMediaItem(buildMediaItem(uri));
        player.prepare();
        player.setPlaybackSpeed(playbackSpeed);
        player.play();
        setControlsVisible(true);
        scheduleAutoHide();
    }

    private void restoreMedia(Uri uri, long position, boolean playWhenReady) {
        mediaTitle.setText(resolveDisplayName(uri));
        player.setMediaItem(buildMediaItem(uri), position);
        player.prepare();
        player.setPlaybackSpeed(playbackSpeed);
        player.setPlayWhenReady(playWhenReady);
    }

    private MediaItem buildMediaItem(Uri mediaUri) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(mediaUri);
        String lower = mediaUri.toString().toLowerCase(Locale.US);
        if (lower.contains(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (lower.contains(".mpd")) builder.setMimeType(MimeTypes.APPLICATION_MPD);

        if (externalSubtitleUri != null) {
            MediaItem.SubtitleConfiguration subtitle =
                    new MediaItem.SubtitleConfiguration.Builder(externalSubtitleUri)
                            .setMimeType(detectSubtitleMime(externalSubtitleUri))
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build();
            builder.setSubtitleConfigurations(Collections.singletonList(subtitle));
        }
        return builder.build();
    }

    private void attachExternalSubtitle(Uri subtitleUri) {
        if (currentMediaUri == null) {
            showError("Buka video terlebih dahulu");
            return;
        }
        externalSubtitleUri = subtitleUri;
        long position = player.getCurrentPosition();
        boolean playWhenReady = player.getPlayWhenReady();
        float speed = player.getPlaybackParameters().speed;
        player.setMediaItem(buildMediaItem(currentMediaUri), position);
        player.prepare();
        player.setPlaybackSpeed(speed);
        player.setPlayWhenReady(playWhenReady);
        Toast.makeText(this, "Subtitle eksternal aktif", Toast.LENGTH_SHORT).show();
    }

    private String detectSubtitleMime(Uri uri) {
        String name = resolveDisplayName(uri).toLowerCase(Locale.US);
        if (name.endsWith(".vtt")) return MimeTypes.TEXT_VTT;
        if (name.endsWith(".ass") || name.endsWith(".ssa")) return MimeTypes.TEXT_SSA;
        return MimeTypes.APPLICATION_SUBRIP;
    }

    private void showSubtitleMenu() {
        List<String> labels = new ArrayList<>();
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> trackIndices = new ArrayList<>();
        labels.add("Off");
        labels.add("Auto / Embedded");
        labels.add("Select External Subtitle");

        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                Format f = group.getTrackFormat(i);
                String label = f.label != null ? f.label : (f.language != null ? f.language : "Subtitle " + (labels.size() - 2));
                labels.add(label);
                groups.add(group);
                trackIndices.add(i);
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Subtitle")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build());
                    } else if (which == 1) {
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .build());
                    } else if (which == 2) {
                        subtitlePicker.launch(new String[]{"text/*", "application/x-subrip", "application/octet-stream"});
                    } else {
                        int n = which - 3;
                        Tracks.Group group = groups.get(n);
                        int index = trackIndices.get(n);
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), index))
                                .build());
                    }
                }).show();
    }

    private void showAudioTrackMenu() {
        List<String> labels = new ArrayList<>();
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        labels.add("Auto");

        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                Format f = group.getTrackFormat(i);
                String label = f.label != null ? f.label : (f.language != null ? f.language : "Audio " + labels.size());
                labels.add(label);
                groups.add(group);
                indices.add(i);
            }
        }
        if (labels.size() == 1) labels.add("Tidak ada track tambahan");

        new AlertDialog.Builder(this)
                .setTitle("Audio Track")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build());
                    } else if (!groups.isEmpty()) {
                        int n = which - 1;
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .setOverrideForType(new TrackSelectionOverride(groups.get(n).getMediaTrackGroup(), indices.get(n)))
                                .build());
                    }
                }).show();
    }

    private void showSpeedMenu() {
        final float[] values = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        String[] labels = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "1.75x", "2.0x"};
        new AlertDialog.Builder(this).setTitle("Playback Speed")
                .setItems(labels, (d, which) -> {
                    playbackSpeed = values[which];
                    player.setPlaybackSpeed(playbackSpeed);
                    speedButton.setText(labels[which]);
                    scheduleAutoHide();
                }).show();
    }

    private void showScalingMenu() {
        String[] items = {"FIT", "FILL", "ZOOM", "BEST FIT / ORIGINAL ASPECT"};
        new AlertDialog.Builder(this).setTitle("Video Scaling")
                .setSingleChoiceItems(items, scaleMode, (d, which) -> {
                    applyScaleMode(which);
                    d.dismiss();
                }).show();
    }

    private void applyScaleMode(int mode) {
        scaleMode = mode;
        if (mode == 1) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
            scaleButton.setText("Fill");
        } else if (mode == 2) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            scaleButton.setText("Zoom");
        } else if (mode == 3) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            scaleButton.setText("Best Fit");
        } else {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            scaleButton.setText("Fit");
        }
    }

    private void showOrientationMenu() {
        String[] items = {"AUTO", "PORTRAIT", "LANDSCAPE", "LANDSCAPE REVERSE"};
        new AlertDialog.Builder(this).setTitle("Orientation")
                .setSingleChoiceItems(items, orientationMode, (d, which) -> {
                    applyOrientationMode(which, true);
                    d.dismiss();
                }).show();
    }

    private void applyOrientationMode(int mode, boolean userAction) {
        orientationMode = mode;
        if (mode == 1) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            orientationButton.setText("Portrait");
        } else if (mode == 2) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            orientationButton.setText("Landscape");
        } else if (mode == 3) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
            orientationButton.setText("Reverse");
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            orientationButton.setText("Auto");
        }
        if (userAction) scheduleAutoHide();
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26 ||
                !getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            showError("Picture-in-Picture tidak didukung perangkat ini");
            return;
        }
        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9));
            if (Build.VERSION.SDK_INT >= 31) builder.setSeamlessResizeEnabled(true);
            setControlsVisible(false);
            enterPictureInPictureMode(builder.build());
        } catch (Exception e) {
            showError("Gagal membuka PiP: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
                                              @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        inPip = isInPictureInPictureMode;
        if (inPip) {
            controlsOverlay.setVisibility(View.GONE);
            unlockButton.setVisibility(View.GONE);
        } else if (!locked) {
            setControlsVisible(true);
            scheduleAutoHide();
        }
    }

    private void setLocked(boolean value) {
        locked = value;
        if (locked) {
            uiHandler.removeCallbacks(hideControlsRunnable);
            controlsOverlay.setVisibility(View.GONE);
            unlockButton.setVisibility(View.VISIBLE);
        } else {
            unlockButton.setVisibility(View.GONE);
            setControlsVisible(true);
            scheduleAutoHide();
        }
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        if (locked || inPip) {
            controlsOverlay.setVisibility(View.GONE);
            return;
        }
        controlsOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void scheduleAutoHide() {
        uiHandler.removeCallbacks(hideControlsRunnable);
        if (!locked && !inPip) uiHandler.postDelayed(hideControlsRunnable, 3200);
    }

    private void seekBy(long delta) {
        if (locked) return;
        long target = Math.max(0, player.getCurrentPosition() + delta);
        long d = safeDuration();
        if (d > 0) target = Math.min(target, d);
        player.seekTo(target);
        showGesture(delta < 0 ? "Rewind 10s" : "Forward 10s");
        scheduleAutoHide();
    }

    private void adjustBrightness(float delta) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.screenBrightness >= 0f) appBrightness = lp.screenBrightness;
        appBrightness = clamp(appBrightness + delta, 0.01f, 1f);
        lp.screenBrightness = appBrightness;
        getWindow().setAttributes(lp);
        showGesture("Brightness\n" + Math.round(appBrightness * 100f) + "%");
    }

    private void adjustVolume(float delta) {
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int change = Math.round(delta * max);
        if (change == 0) change = delta > 0 ? 1 : -1;
        int target = Math.max(0, Math.min(max, current + change));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        int pct = Math.round((target * 100f) / Math.max(1, max));
        showGesture((target == 0 ? "Mute" : "Volume") + "\n" + pct + "%");
    }

    private void showGesture(String text) {
        gestureIndicator.setText(text);
        gestureIndicator.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hideGestureRunnable);
        uiHandler.postDelayed(hideGestureRunnable, 800);
    }

    private void updateProgress() {
        if (player == null) return;
        long d = safeDuration();
        long p = Math.max(0, player.getCurrentPosition());
        if (!seeking) {
            currentTime.setText(formatTime(p));
            duration.setText(formatTime(d));
            seekBar.setProgress(d > 0 ? (int) Math.min(1000, (p * 1000L) / d) : 0);
        }
        playPause.setText(player.isPlaying() ? "Pause" : "Play");
        playbackSpeed = player.getPlaybackParameters().speed;
        speedButton.setText(trimSpeed(playbackSpeed));
    }

    private long safeDuration() {
        long d = player.getDuration();
        return d == C.TIME_UNSET || d < 0 ? 0 : d;
    }

    private String formatTime(long ms) {
        long seconds = Math.max(0, ms / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs);
        return String.format(Locale.US, "%02d:%02d", minutes, secs);
    }

    private String trimSpeed(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.01f) return String.format(Locale.US, "%.1fx", speed);
        return String.format(Locale.US, "%.2fx", speed).replace("0x", "x");
    }

    private void styleSubtitleView() {
        SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView == null) return;
        subtitleView.setApplyEmbeddedStyles(true);
        subtitleView.setBottomPaddingFraction(0.12f);
        subtitleView.setStyle(new CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                null));
    }

    private void updateResponsiveLayout(Configuration config) {
        boolean landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE;
        centerControls.setScaleX(landscape ? 1.0f : 0.92f);
        centerControls.setScaleY(landscape ? 1.0f : 0.92f);
        topBar.setAlpha(landscape ? 0.92f : 1.0f);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResponsiveLayout(newConfig);
        styleSubtitleView();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            playNewMedia(intent.getData());
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentMediaUri != null) outState.putString(KEY_MEDIA, currentMediaUri.toString());
        if (externalSubtitleUri != null) outState.putString(KEY_SUBTITLE, externalSubtitleUri.toString());
        if (player != null) {
            outState.putLong(KEY_POS, player.getCurrentPosition());
            outState.putBoolean(KEY_PLAY, player.getPlayWhenReady());
            outState.putFloat(KEY_SPEED, player.getPlaybackParameters().speed);
        }
        outState.putInt(KEY_SCALE, scaleMode);
        outState.putInt(KEY_ORIENTATION, orientationMode);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private void persistReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
    }

    private String resolveDisplayName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) return c.getString(idx);
                }
            } catch (Exception ignored) { }
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "RIC Player" : last;
    }

    private void showError(String message) {
        errorState.setText(message);
        errorState.setVisibility(View.VISIBLE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        uiHandler.postDelayed(() -> errorState.setVisibility(View.GONE), 5000);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
