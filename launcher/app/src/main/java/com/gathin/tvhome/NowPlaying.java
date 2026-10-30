package com.gathin.tvhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * The quick settings panel's Now playing card: the artwork, title and app of whatever has a media session
 * (MediaWatch), with Previous, Play/Pause and Next, and its progress. Like the notifications it needs notification
 * access, which is what lets an app read other apps' media sessions; without it the card stays hidden.
 * It listens only while the panel is open.
 */
final class NowPlaying {

    private final Activity activity;
    private final Runnable close;
    private final View card, open, previous, playPause, next;
    private final ImageView art;
    private final TextView title, subtitle;
    private final ProgressBar progress;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaWatch media;
    private View[] belowCard = new View[0];

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (showProgress()) handler.postDelayed(this, 1000);
        }
    };

    NowPlaying(Activity activity, Runnable close) {
        this.activity = activity;
        this.close = close;
        media = new MediaWatch(activity, handler, this::show);
        card = activity.findViewById(R.id.now_playing);
        open = activity.findViewById(R.id.media_open);
        art = activity.findViewById(R.id.media_art);
        art.setClipToOutline(true);
        title = activity.findViewById(R.id.media_title);
        subtitle = activity.findViewById(R.id.media_subtitle);
        progress = activity.findViewById(R.id.media_progress);
        previous = activity.findViewById(R.id.media_previous);
        playPause = activity.findViewById(R.id.media_play_pause);
        next = activity.findViewById(R.id.media_next);

        open.setOnClickListener(v -> openApp());
        previous.setOnClickListener(v -> {
            MediaController c = media.controller();
            if (c != null) c.getTransportControls().skipToPrevious();
        });
        next.setOnClickListener(v -> {
            MediaController c = media.controller();
            if (c != null) c.getTransportControls().skipToNext();
        });
        playPause.setOnClickListener(v -> {
            MediaController c = media.controller();
            if (c == null) return;
            if (MediaWatch.playing(c.getPlaybackState())) c.getTransportControls().pause();
            else c.getTransportControls().play();
        });
    }

    /** The tiles right under the card: Up from them goes to Play/Pause while the card is up. */
    void above(View... tiles) {
        belowCard = tiles;
    }

    void start() {
        media.start();
    }

    void stop() {
        handler.removeCallbacks(tick);
        media.stop();
    }

    private void show() {
        if (activity.isFinishing()) return;
        handler.removeCallbacks(tick);
        MediaController c = media.controller();
        if (!MediaWatch.hasContent(c)) {
            hide();
            return;
        }
        MediaMetadata m = c.getMetadata();
        CharSequence name = MediaWatch.title(m);
        CharSequence second = MediaWatch.subtitle(m);
        title.setText(name);
        subtitle.setText(second != null ? second : appLabel(c.getPackageName()));

        Bitmap picture = MediaWatch.art(m);
        if (picture != null) {
            art.setPadding(0, 0, 0, 0);
            art.setImageBitmap(picture);
        } else {
            int pad = Math.round(8 * activity.getResources().getDisplayMetrics().density);
            art.setPadding(pad, pad, pad, pad);
            art.setImageDrawable(appIcon(c.getPackageName()));
        }

        PlaybackState state = c.getPlaybackState();
        long actions = state != null ? state.getActions() : 0;
        boolean isPlaying = MediaWatch.playing(state);
        ((ImageView) playPause).setImageResource(isPlaying ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
        playPause.setContentDescription(activity.getString(isPlaying ? R.string.media_pause : R.string.media_play));
        setShown(previous, (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
        setShown(next, (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
        card.setVisibility(View.VISIBLE);
        for (View tile : belowCard) tile.setNextFocusUpId(R.id.media_play_pause);
        if (showProgress() && isPlaying) handler.postDelayed(tick, 1000);
    }

    /** The bar under the title, where the session tells its duration and position. False when there is none. */
    private boolean showProgress() {
        MediaController c = media.controller();
        MediaMetadata m = c != null ? c.getMetadata() : null;
        PlaybackState state = c != null ? c.getPlaybackState() : null;
        long duration = m != null ? m.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        if (duration <= 0 || state == null || state.getPosition() < 0) {
            progress.setVisibility(View.GONE);
            return false;
        }
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }
        progress.setProgress((int) (1000 * Math.max(0, Math.min(position, duration)) / duration));
        progress.setVisibility(View.VISIBLE);
        return true;
    }

    private void hide() {
        if (card.getVisibility() == View.GONE) return;
        for (View tile : belowCard) tile.setNextFocusUpId(View.NO_ID);
        boolean hadFocus = card.hasFocus();
        card.setVisibility(View.GONE);
        if (hadFocus) {
            View tiles = activity.findViewById(R.id.tiles);
            if (!tiles.requestFocus()) activity.findViewById(R.id.gear).requestFocus();
        }
    }

    /** Hides a control, moving the focus to Play/Pause first if it was on it. */
    private void setShown(View control, boolean shown) {
        if (!shown && control.isFocused()) playPause.requestFocus();
        control.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
    }

    /** Opens the app behind the session: the screen it names, else its launcher entry. */
    private void openApp() {
        MediaController c = media.controller();
        if (c == null) return;
        PendingIntent screen = c.getSessionActivity();
        boolean sent = false;
        if (screen != null) {
            try {
                screen.send(activity, 0, null, null, null, null, QuickSettingsActivity.sendOptions());
                sent = true;
            } catch (PendingIntent.CanceledException ignored) {
            }
        }
        if (!sent) {
            PackageManager pm = activity.getPackageManager();
            Intent launch = pm.getLeanbackLaunchIntentForPackage(c.getPackageName());
            if (launch == null) launch = pm.getLaunchIntentForPackage(c.getPackageName());
            if (launch != null) {
                try {
                    activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                }
            }
        }
        close.run();
    }

    private CharSequence appLabel(String pkg) {
        try {
            PackageManager pm = activity.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private Drawable appIcon(String pkg) {
        try {
            return activity.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return activity.getDrawable(R.drawable.ic_notification);
        }
    }
}
