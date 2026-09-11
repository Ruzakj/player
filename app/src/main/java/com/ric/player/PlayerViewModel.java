package com.ric.player;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Owns exactly one ExoPlayer instance for the Activity lifecycle.
 * ViewModel retention prevents player recreation during Activity recreation,
 * while onCleared() still guarantees the decoder/surfaces are released when
 * the Activity is actually finished.
 */
public final class PlayerViewModel extends AndroidViewModel {
    private final ExoPlayer player;

    public PlayerViewModel(@NonNull Application application) {
        super(application);
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(application)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(application, renderersFactory).build();
    }

    public ExoPlayer getPlayer() {
        return player;
    }

    @Override
    protected void onCleared() {
        player.release();
        super.onCleared();
    }
}
