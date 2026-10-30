package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The notifications the quick settings panel lists (settings_key_panel), which Google TV's own home showed in its
 * panel. Android binds it only once notification access is granted, over adb:
 *   cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.NotificationTray
 * It keeps nothing itself: the panel reads the active notifications when it opens and on each change.
 */
public final class NotificationTray extends NotificationListenerService {

    private static NotificationTray connected;
    /** Set by the open panel, run on the main thread whenever a notification comes or goes. */
    static Runnable changed;

    @Override
    public void onListenerConnected() {
        connected = this;
        ping();
    }

    @Override
    public void onListenerDisconnected() {
        if (connected == this) connected = null;
        ping();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        ping();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        ping();
    }

    private static void ping() {
        Runnable r = changed;
        if (r != null) r.run();
    }

    /**
     * The ones Google TV's panel shows too, newest first: not silent (channel importance below default, such as
     * "Serial console enabled" or Play's setup reminder), not an app's "is running" foreground-service notice,
     * not a player's media controls (NowPlaying shows those), and not a group summary or empty. Null when notification access is off.
     */
    static List<StatusBarNotification> active() {
        NotificationTray tray = connected;
        if (tray == null) return null;
        StatusBarNotification[] all;
        try {
            all = tray.getActiveNotifications();
        } catch (RuntimeException e) {
            return null;
        }
        List<StatusBarNotification> shown = new ArrayList<>();
        if (all == null) return shown;
        RankingMap ranking = tray.getCurrentRanking();
        Ranking rank = new Ranking();
        for (StatusBarNotification sbn : all) {
            Notification n = sbn.getNotification();
            if ((n.flags & (Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_FOREGROUND_SERVICE)) != 0) continue;
            if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) continue;   // the Now playing card has it
            if (ranking != null && ranking.getRanking(sbn.getKey(), rank)
                    && rank.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) continue;
            if (n.extras.getCharSequence(Notification.EXTRA_TITLE) == null
                    && n.extras.getCharSequence(Notification.EXTRA_TEXT) == null) continue;
            shown.add(sbn);
        }
        Collections.sort(shown, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        return shown;
    }

    /** Clears every notification that can be cleared, as the panel's Clear all. */
    static void clearAll() {
        NotificationTray tray = connected;
        if (tray != null) tray.cancelAllNotifications();
    }

    static void dismiss(String key) {
        NotificationTray tray = connected;
        if (tray != null) tray.cancelNotification(key);
    }
}
