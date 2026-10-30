package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;

import java.util.List;

/**
 * What is playing: the app media session worth showing, followed while start()ed, with a callback on every change.
 * Reading other apps' sessions needs notification access (NotificationTray); without it controller() stays null.
 * Used by the quick panel's Now playing card and by the clock screensaver.
 */
final class MediaWatch {

    private final Context context;
    private final Handler handler;
    private final Runnable changed;
    private final ComponentName listener;
    private MediaSessionManager sessions;
    private MediaController controller;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            changed.run();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            changed.run();
        }

        @Override
        public void onSessionDestroyed() {
            pick(activeSessions());
        }
    };

    /** changed runs on handler's thread. */
    MediaWatch(Context context, Handler handler, Runnable changed) {
        this.context = context;
        this.handler = handler;
        this.changed = changed;
        listener = new ComponentName(context, NotificationTray.class);
    }

    void start() {
        sessions = context.getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler);
        } catch (SecurityException e) {   // notification access is off
            sessions = null;
            return;
        }
        pick(activeSessions());
    }

    void stop() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
        sessions = null;
    }

    /** The session playing now, else the first one paused with something in it; null when there is none. */
    MediaController controller() {
        return controller;
    }

    private List<MediaController> activeSessions() {
        if (sessions == null) return null;
        try {
            return sessions.getActiveSessions(listener);
        } catch (SecurityException e) {
            return null;
        }
    }

    private void pick(List<MediaController> active) {
        MediaController best = null;
        if (active != null) {
            for (MediaController c : active) {
                if (playing(c.getPlaybackState())) {
                    best = c;
                    break;
                }
                if (best == null && hasContent(c)) best = c;
            }
        }
        MediaSession.Token now = controller != null ? controller.getSessionToken() : null;
        if (best == null || now == null || !now.equals(best.getSessionToken())) {
            if (controller != null) controller.unregisterCallback(callback);
            controller = best;
            if (controller != null) controller.registerCallback(callback, handler);
        }
        changed.run();
    }

    static boolean playing(PlaybackState state) {
        if (state == null) return false;
        int s = state.getState();
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
                || s == PlaybackState.STATE_FAST_FORWARDING || s == PlaybackState.STATE_REWINDING;
    }

    /** A session worth showing: a title, and not stopped, failed or never started. */
    static boolean hasContent(MediaController c) {
        if (c == null || title(c.getMetadata()) == null) return false;
        PlaybackState state = c.getPlaybackState();
        if (state == null) return false;
        int s = state.getState();
        return s != PlaybackState.STATE_NONE && s != PlaybackState.STATE_STOPPED && s != PlaybackState.STATE_ERROR;
    }

    static CharSequence title(MediaMetadata m) {
        return text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
    }

    /** The artist, else a show's subtitle or the album. */
    static CharSequence subtitle(MediaMetadata m) {
        return text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
    }

    static Bitmap art(MediaMetadata m) {
        if (m == null) return null;
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap picture = m.getBitmap(key);
            if (picture != null) return picture;
        }
        return null;
    }

    private static CharSequence text(MediaMetadata m, String... keys) {
        if (m == null) return null;
        for (String key : keys) {
            CharSequence value = m.getText(key);
            if (value != null && value.toString().trim().length() > 0) return value;
        }
        return null;
    }
}
