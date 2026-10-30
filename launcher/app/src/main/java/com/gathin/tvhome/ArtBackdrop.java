package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.ImageView;

/**
 * A full-screen layer with the playing track's artwork (MediaWatch), darkened so white text stays readable on it:
 * at least minDim, and more for a bright cover. It crossfades to each new picture and away to whatever is under
 * it when there is none. Behind Home's tiles and the clock screensaver.
 */
final class ArtBackdrop {

    /** The darkened picture's average brightness aimed for (0 to 1): white and light grey text read on it. */
    private static final float TARGET_LUMA = 0.16f;
    private static final float MAX_DIM = 0.88f;

    private final ImageView view;
    private final float minDim;
    private Bitmap shown;

    ArtBackdrop(ImageView view, float minDim) {
        this.view = view;
        this.minDim = minDim;
    }

    /** The artwork to show, or null to fade back to what is underneath. */
    void show(Bitmap art) {
        if (art == shown) return;
        // Every metadata update brings a new copy of the same picture; swap it without a fade.
        if (art != null && shown != null && art.getWidth() == shown.getWidth()
                && art.getHeight() == shown.getHeight() && sameAs(art, shown)) {
            shown = art;
            view.setImageBitmap(art);
            return;
        }
        shown = art;
        view.animate().cancel();
        if (art != null) {
            float dim = Math.max(minDim, Math.min(MAX_DIM, 1f - TARGET_LUMA / Math.max(luma(art), 0.01f)));
            view.setForeground(new ColorDrawable(Math.round(dim * 255) << 24));
        }
        if (art == null) {
            view.animate().alpha(0f).setDuration(900).withEndAction(() -> {
                view.setImageDrawable(null);
                view.setVisibility(View.GONE);
            }).start();
            return;
        }
        view.setVisibility(View.VISIBLE);
        if (view.getAlpha() == 0f) {
            view.setImageBitmap(art);
            view.animate().alpha(1f).setDuration(1200).start();
            return;
        }
        view.animate().alpha(0f).setDuration(500).withEndAction(() -> {
            view.setImageBitmap(art);
            view.animate().alpha(1f).setDuration(1000).start();
        }).start();
    }

    void cancel() {
        view.animate().cancel();
    }

    /** The picture's average brightness, 0 to 1, from a 12x12 copy; 1 (darkest shade) when it cannot be read. */
    static float luma(Bitmap art) {
        Bitmap small = smallCopy(art, 12);
        if (small == null) return 1f;
        float sum = 0;
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int p = small.getPixel(x, y);
                sum += (0.2126f * Color.red(p) + 0.7152f * Color.green(p) + 0.0722f * Color.blue(p)) / 255f;
            }
        }
        float avg = sum / (small.getWidth() * small.getHeight());
        if (small != art) small.recycle();
        return avg;
    }

    /** A size x size software copy to read pixels from (a hardware bitmap cannot be read); null on failure. */
    static Bitmap smallCopy(Bitmap art, int size) {
        try {
            Bitmap soft = art.getConfig() == Bitmap.Config.HARDWARE ? art.copy(Bitmap.Config.ARGB_8888, false) : art;
            Bitmap small = Bitmap.createScaledBitmap(soft, size, size, true);
            if (soft != art && soft != small) soft.recycle();
            return small;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static boolean sameAs(Bitmap a, Bitmap b) {
        try {
            return a.sameAs(b);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
