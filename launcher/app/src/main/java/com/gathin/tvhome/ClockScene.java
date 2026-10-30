package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow. Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in. While an app
 * plays something (MediaWatch, with notification access), its artwork fills the screen behind the clock, darkened,
 * and its title and artist drift with the clock; without artwork the glow takes over again.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The token of the glow's queued fade steps. */
    private static final Object GLOW = new Object();
    private final Random random = new Random();
    private final View content;
    private final ImageView ambient;
    private final GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    /** clock_wallpaper or clock_glow false: something else (a picture, or plain black) replaced the coloured glow. */
    private boolean wallpaperShown;
    /** clock_wallpaper: the picture start() decodes, its darkening, and the resources to draw it with. */
    private File wallpaperFile;
    private int wallpaperShade;
    private Resources wallpaperRes;
    /** One number per decode request: a result whose number is no longer the current one is dropped. Main thread only. */
    private int wallpaperGeneration;
    /** clock_wallpaper with a collection: the pictures, how long each lasts, and how the next one is picked. */
    private List<File> rotation;
    private long rotationMs;
    private long shownMs;
    private String order;
    private Map<String, Set<String>> moods;
    private String latitude, longitude;
    /** What is playing, under the date; the track shown ("" for none) and the glow colour from its art (0: none). */
    private final MediaWatch media;
    private final View music;
    private final ArtBackdrop backdrop;
    private final TextView musicTitle, musicArtist;
    private String track = "";
    private int musicColor;
    private boolean started;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            advanceWallpaper();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        ambient = root.findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
        music = root.findViewById(R.id.dreamMusic);
        backdrop = new ArtBackdrop(root.findViewById(R.id.dreamBackdrop), 0.55f);
        musicTitle = root.findViewById(R.id.dreamTitle);
        musicArtist = root.findViewById(R.id.dreamArtist);
        media = new MediaWatch(root.getContext(), handler, this::showMusic);
        // A longer title, or the track block appearing, must not push the clock past the screen's edge.
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) keepOnScreen();
        });
    }

    void start() {
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        if (wallpaperFile != null && !wallpaperShown) loadWallpaper();
        started = true;
        media.start();   // before the first layout, so the clock is placed with the track already under it
    }

    void stop() {
        started = false;
        media.stop();
        track = "";
        handler.removeCallbacksAndMessages(null);   // the tick and any glow steps still queued
        content.animate().cancel();
        backdrop.cancel();
        wallpaperGeneration++;   // a decode still running must not attach its picture to a stopped scene
    }

    /**
     * clock_wallpaper: put the picture Home is showing behind the clock, darkened by clock_wallpaper_dim, in
     * place of the glow, and carry a collection's rotation on while the screensaver is up. Decoded off the main
     * thread at screen size in RGB_565 (4 MB at 1080p), like Home's own; until then, or if it cannot be read,
     * the glow stays.
     * Call before start(), which starts the decode (again after a stop() that dropped an unfinished one).
     */
    void useHomeWallpaper(Context ctx) {
        Config cfg;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException e) {
            return;
        }
        if (!cfg.clockWallpaper) {
            // clock_glow false: plain black behind the clock (OLED); the clock itself still moves every minute.
            if (!cfg.clockGlow) {
                wallpaperShown = true;   // also stops the glow's colour cycle
                ambient.setBackground(null);
            }
            return;
        }
        // What Home plays, and Config puts the single wallpaper in it when a theme has no collection.
        // Reading cfg.wallpaper first held the screensaver on wallpaper.jpg while Home rotated.
        if (cfg.wallpapers.isEmpty()) {
            wallpaperFile = null;
        } else {
            // Start on the picture Home is showing. WallpaperPlayer records it under the same
            // preferences and the screensaver runs in the same process, so this is just a read.
            rotation = new ArrayList<>(cfg.wallpapers);
            String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE)
                    .getString("image", "");
            File current = WallpaperRotation.find(rotation, shown);
            wallpaperFile = current != null ? current : rotation.get(0);
            // Home only rotates while it is visible, so the screensaver carries the rotation on
            // itself; without this it holds one picture for as long as it is up.
            rotationMs = cfg.wallpaperRotationMinutes * 60_000L;
            order = cfg.wallpaperOrder;
            moods = cfg.wallpaperMoods;
            latitude = moods.isEmpty() ? "" : ctx.getString(R.string.latitude);
            longitude = moods.isEmpty() ? "" : ctx.getString(R.string.longitude);
        }
        wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
        wallpaperRes = ctx.getResources();
    }

    private void loadWallpaper() {
        File file = wallpaperFile;
        int shade = wallpaperShade;
        Resources res = wallpaperRes;
        DisplayMetrics dm = res.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int generation = ++wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = decode(file, w, h);
            if (bmp == null) return;
            handler.post(() -> {
                if (generation != wallpaperGeneration || wallpaperShown) {
                    bmp.recycle();   // stopped meanwhile (or already shown): this picture was never attached
                    return;
                }
                wallpaperShown = true;
                // As the image, not the background: a background is stretched to the view, so a picture of
                // another shape than the screen would be distorted. centerCrop scales and crops it like Home.
                ambient.setBackground(null);
                ambient.setImageDrawable(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(res, bmp), new ColorDrawable(shade)}));
            });
        }, "clock-wallpaper").start();
    }

    /** WallpaperPlayer's decode: sampled and scaled down to cover the screen, never bigger than 4K. */
    private static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** Step to the next picture once its turn is up; the tick already runs every minute. */
    private void advanceWallpaper() {
        if (rotation == null || rotationMs <= 0 || rotation.size() < 2) return;
        shownMs += MOVE_MS;
        if (shownMs < rotationMs) return;
        shownMs = 0;
        // The same pick as Home's, so a shuffled collection stays shuffled and wallpaper_moods still
        // decides what may show at this hour.
        File next = WallpaperRotation.pick(playable(), wallpaperFile.getName(), order, random);
        if (next.equals(wallpaperFile)) return;   // a mood group of one
        wallpaperFile = next;
        wallpaperShown = false;
        loadWallpaper();
    }

    /** WallpaperPlayer's filter: the pictures wallpaper_moods allows at this hour, or all of them. */
    private List<File> playable() {
        if (moods.isEmpty()) return rotation;
        LocalDateTime now = LocalDateTime.now();
        int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
        return WallpaperMood.select(rotation, moods,
                WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    /**
     * The track under the clock while something plays, else none. A new track, or music starting or stopping,
     * moves the clock as the minute tick does, so the bigger or smaller block never runs off the screen.
     */
    private void showMusic() {
        if (!started) return;
        MediaController c = media.controller();
        boolean playing = c != null && MediaWatch.playing(c.getPlaybackState()) && MediaWatch.hasContent(c);
        MediaMetadata m = playing ? c.getMetadata() : null;
        CharSequence title = playing ? MediaWatch.title(m) : null;
        CharSequence artist = playing ? MediaWatch.subtitle(m) : null;
        Bitmap art = playing ? MediaWatch.art(m) : null;
        String now = playing ? title + "\n" + artist + "\n" + (art != null) : "";   // art often comes a moment after the title
        if (now.equals(track)) return;
        boolean placed = !track.isEmpty() || content.getAlpha() > 0f;
        boolean artOnly = playing && track.startsWith(title + "\n" + artist + "\n");
        track = now;
        Runnable apply = () -> {
            if (!playing) {
                music.setVisibility(View.GONE);
            } else {
                musicTitle.setText(title);
                musicArtist.setText(artist);
                musicArtist.setVisibility(artist != null ? View.VISIBLE : View.GONE);
                music.setVisibility(View.VISIBLE);
            }
        };
        backdrop.show(art);
        if (placed && !artOnly) moveClock(apply);
        else apply.run();
        int color = art != null ? vivid(art) : 0;
        if (color != musicColor) {
            musicColor = color;
            fadeGlow(color != 0 ? color : PALETTE[colorIndex]);
        }
    }

    /**
     * The artwork's strongest colour, brightened for the glow: the hue that carries the most saturated, bright
     * pixels of a 16x16 copy. 0 for grey or black-and-white art, which leaves the glow's own colours.
     */
    static int vivid(Bitmap art) {
        Bitmap small = ArtBackdrop.smallCopy(art, 16);
        if (small == null) return 0;
        float[] hsv = new float[3];
        float[] weight = new float[12];
        float[][] rgb = new float[12][3];
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int p = small.getPixel(x, y);
                Color.colorToHSV(p, hsv);
                float w = hsv[1] * hsv[2];
                if (hsv[1] < 0.2f || hsv[2] < 0.2f) continue;
                int bin = (int) (hsv[0] / 30f) % 12;
                weight[bin] += w;
                rgb[bin][0] += Color.red(p) * w;
                rgb[bin][1] += Color.green(p) * w;
                rgb[bin][2] += Color.blue(p) * w;
            }
        }
        if (small != art) small.recycle();
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        if (weight[best] < 4f) return 0;   // a few faint pixels out of 256
        float w = weight[best];
        Color.RGBToHSV(Math.round(rgb[best][0] / w), Math.round(rgb[best][1] / w), Math.round(rgb[best][2] / w), hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }

    private void moveClock() {
        moveClock(null);
    }

    /** Fades the clock out, runs whileHidden, and fades it back in at a new spot. */
    private void moveClock(Runnable whileHidden) {
        content.animate().cancel();
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            if (whileHidden != null) whileHidden.run();
            placeClock();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void keepOnScreen() {
        View parent = (View) content.getParent();
        float maxX = Math.max(0, parent.getWidth() - content.getWidth());
        float maxY = Math.max(0, parent.getHeight() - content.getHeight());
        if (content.getX() > maxX) content.setX(maxX);
        if (content.getY() > maxY) content.setY(maxY);
    }

    private void shiftGlow() {
        if (wallpaperShown || musicColor != 0) return;   // the artwork's colour holds while it plays
        colorIndex = (colorIndex + 1) % PALETTE.length;
        fadeGlow(PALETTE[colorIndex]);
    }

    private void fadeGlow(int to) {
        if (wallpaperShown) return;
        handler.removeCallbacksAndMessages(GLOW);   // a fade still running would fight this one
        int from = glowColor;
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postAtTime(() -> setGlow((int) mix.evaluate(f, from, to)), GLOW,
                    SystemClock.uptimeMillis() + i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
