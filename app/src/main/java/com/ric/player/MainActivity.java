package com.ric.player;

import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Rational;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public class MainActivity extends ComponentActivity {
    private ExoPlayer player;
    private PlayerView playerView;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (Exception ignored) {}
                    play(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.playerView);
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                Toast.makeText(MainActivity.this,
                        "Tidak dapat memutar media ini: " + error.getErrorCodeName(),
                        Toast.LENGTH_LONG).show();
            }
        });

        Button openFile = findViewById(R.id.openFile);
        Button openUrl = findViewById(R.id.openUrl);
        Button pip = findViewById(R.id.pip);

        openFile.setOnClickListener(v -> picker.launch(new String[]{"video/*", "audio/*"}));
        openUrl.setOnClickListener(v -> showUrlDialog());
        pip.setOnClickListener(v -> enterPip());

        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            play(intent.getData());
        }
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
                    if (!url.isEmpty()) play(Uri.parse(url));
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void play(Uri uri) {
        MediaItem item = new MediaItem.Builder().setUri(uri).build();
        player.setMediaItem(item);
        player.prepare();
        player.play();
    }

    private void enterPip() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9)).build();
            enterPictureInPictureMode(params);
        }
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
