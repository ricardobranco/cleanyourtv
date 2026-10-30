package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService), laid out
 * like Google TV's own: the date and time with a gear for the TV's settings, what is playing (NowPlaying), tiles
 * for Screensaver, Network, Picture, Bluetooth, Audio output and Accessibility, then the notifications
 * (NotificationTray) and Clear all.
 * Translucent and in a task of its own, so the app behind keeps running and Back returns to it. Pressing Settings
 * again closes the panel.
 */
public final class QuickSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    /** Notifications shown at most; the rest wait for Clear all or the apps themselves. */
    private static final int MAX_NOTIFICATIONS = 6;
    private LinearLayout notifications;
    private NowPlaying nowPlaying;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            View panel = findViewById(R.id.panel);
            FrameLayout.LayoutParams position = (FrameLayout.LayoutParams) panel.getLayoutParams();
            position.gravity = Gravity.START;
            panel.setLayoutParams(position);
        }
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        String day = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd");
        TextClock date = findViewById(R.id.date);
        date.setFormat12Hour(day);
        date.setFormat24Hour(day);
        findViewById(R.id.gear).setOnClickListener(v -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));

        LinearLayout tiles = findViewById(R.id.tiles);
        View first = tile(tiles, R.drawable.ic_screensaver, getString(R.string.quick_screensaver), null, () -> {
            RemoteKeys.launch(this, "screensaver");
            finish();
        });
        boolean[] ethernet = new boolean[1];
        String network = network(ethernet);
        View second = tile(tiles, ethernet[0] ? R.drawable.ic_ethernet : R.drawable.ic_wifi, network, null,
                () -> open(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS)));
        tile(tiles, R.drawable.ic_picture, getString(R.string.quick_picture), null,
                () -> open(SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS)));
        boolean bluetooth = Settings.Global.getInt(getContentResolver(), Settings.Global.BLUETOOTH_ON, 0) != 0;
        tile(tiles, R.drawable.ic_bluetooth, getString(R.string.quick_bluetooth),
                getString(bluetooth ? R.string.on : R.string.off),
                () -> open(firstOf("com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES",
                        "android.settings.BLUETOOTH_SETTINGS")));
        tile(tiles, R.drawable.ic_audio_output, getString(R.string.quick_audio_output), audioOutput(),
                () -> open(firstOf("com.android.tv.action.VIEW_SOUND_SETTINGS", Settings.ACTION_SOUND_SETTINGS)));
        tile(tiles, R.drawable.ic_accessibility, getString(R.string.quick_accessibility), null,
                () -> open(SystemSettings.intent(this, Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        notifications = findViewById(R.id.notifications);
        showNotifications();
        NotificationTray.changed = this::showNotifications;
        nowPlaying = new NowPlaying(this, this::finish);
        nowPlaying.above(first, second);
        nowPlaying.start();
        first.requestFocus();
    }

    @Override
    protected void onDestroy() {
        NotificationTray.changed = null;
        nowPlaying.stop();
        super.onDestroy();
    }

    /**
     * Every way out (Back, Settings again, a tile, the scrim) takes the panel's task with it: Android lists the
     * newest task in recents even when it is excluded, so TCL's bar showed the launcher there.
     */
    @Override
    public void finish() {
        finishAndRemoveTask();
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    /** Adds a tile in two columns: a new row for every odd one. */
    private View tile(LinearLayout tiles, int icon, CharSequence title, CharSequence status, Runnable action) {
        LinearLayout row = tiles.getChildCount() == 0 ? null
                : (LinearLayout) tiles.getChildAt(tiles.getChildCount() - 1);
        if (row == null || row.getChildCount() == 2) {
            row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (tiles.getChildCount() > 0) lp.topMargin = dp(8);
            tiles.addView(row, lp);
        }
        View tile = item(row, getDrawable(icon), title, status, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        if (row.getChildCount() == 2) lp.setMarginStart(dp(8));
        tile.setLayoutParams(lp);
        return tile;
    }

    private View item(LinearLayout parent, Drawable icon, CharSequence title, CharSequence status, Runnable action) {
        View item = getLayoutInflater().inflate(R.layout.item_quick_tile, parent, false);
        ((ImageView) item.findViewById(R.id.icon)).setImageDrawable(icon);
        ((TextView) item.findViewById(R.id.title)).setText(title);
        TextView second = item.findViewById(R.id.status);
        if (status != null && status.length() > 0) second.setText(status);
        else second.setVisibility(View.GONE);
        item.setOnClickListener(v -> action.run());
        parent.addView(item);
        return item;
    }

    /** The notifications now, then Clear all when any can be cleared; a line saying why when there are none. */
    private void showNotifications() {
        if (notifications == null || isFinishing()) return;
        View focused = getCurrentFocus();
        boolean focusInList = focused != null && focused.getParent() == notifications;
        notifications.removeAllViews();
        List<StatusBarNotification> active = NotificationTray.active();
        if (active == null || active.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(active == null ? R.string.quick_notifications_off : R.string.quick_no_notifications);
            none.setTextColor(0x73FFFFFF);
            none.setTextSize(12);
            none.setPadding(dp(4), dp(4), dp(4), dp(4));
            notifications.addView(none);
            if (focusInList) findViewById(R.id.gear).requestFocus();
            return;
        }
        boolean clearable = false;
        for (int i = 0; i < Math.min(MAX_NOTIFICATIONS, active.size()); i++) {
            StatusBarNotification sbn = active.get(i);
            Notification n = sbn.getNotification();
            CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (title == null) title = appLabel(sbn.getPackageName());
            View row = item(notifications, notificationIcon(sbn), title, text, () -> openNotification(sbn));
            spaceAbove(row);
        }
        for (StatusBarNotification sbn : active) clearable |= sbn.isClearable();
        if (clearable) {
            View clear = item(notifications, getDrawable(R.drawable.ic_clear_all), getString(R.string.quick_clear_all),
                    null, NotificationTray::clearAll);
            spaceAbove(clear);
        }
        if (focusInList) notifications.getChildAt(0).requestFocus();
    }

    private void spaceAbove(View row) {
        if (notifications.indexOfChild(row) == 0) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
    }

    /** The notification's small icon (white, tinted like the tiles), else its app's icon. */
    private Drawable notificationIcon(StatusBarNotification sbn) {
        Icon small = sbn.getNotification().getSmallIcon();
        Drawable icon = null;
        try {
            if (small != null) icon = small.loadDrawable(this);
        } catch (RuntimeException ignored) {
        }
        if (icon == null) {
            try {
                icon = getPackageManager().getApplicationIcon(sbn.getPackageName());
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return icon != null ? icon : getDrawable(R.drawable.ic_notification);
    }

    private CharSequence appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Opens what the notification points to, and clears it when it asks to be cleared on a tap. */
    private void openNotification(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        PendingIntent target = n.contentIntent;
        if (target != null) {
            try {
                target.send(this, 0, null, null, null, null, sendOptions());
            } catch (PendingIntent.CanceledException ignored) {
            }
        }
        if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0) NotificationTray.dismiss(sbn.getKey());
        finish();
    }

    /** Android 14 lets a foreground app start another app's activity from its PendingIntent only if it says so. */
    static Bundle sendOptions() {
        if (Build.VERSION.SDK_INT < 34) return null;
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
    }

    /** Ethernet, the Wi-Fi network's name, or Offline, as the network pill on Home reads them. */
    private String network(boolean[] ethernet) {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            boolean online = false, wifi = false;
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet[0] = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) return getString(R.string.offline);
            if (ethernet[0]) return getString(R.string.ethernet);
            if (wifi) {
                WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) return ssid;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return getString(R.string.quick_network);
    }

    /** Where the sound goes: a soundbar or receiver over HDMI ARC/eARC, Bluetooth or USB, else the TV's speakers. */
    private String audioOutput() {
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null) return null;
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            boolean external = type == AudioDeviceInfo.TYPE_HDMI_ARC || type == 29 /* TYPE_HDMI_EARC, API 31 */
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_USB_DEVICE
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
            if (!external) continue;
            CharSequence name = device.getProductName();
            return name != null && name.length() > 0 && !name.toString().equals(Build.MODEL)
                    ? name.toString() : getString(R.string.quick_external_device);
        }
        return getString(R.string.quick_tv_speakers);
    }

    /** The first of these settings actions the TV answers, else the TV's settings. */
    private Intent firstOf(String... actions) {
        for (String action : actions) {
            Intent intent = new Intent(action);
            if (getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) return intent;
        }
        return SystemSettings.intent(this, Settings.ACTION_SETTINGS);
    }

    private void open(Intent target) {
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
