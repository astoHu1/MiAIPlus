package io.github.miaiplus;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

/** Conservative local image anchor for controls that accessibility cannot describe completely. */
final class VisualRegion {
    private VisualRegion() { }

    static boolean stable(ScreenService.Frame before, ScreenService.Frame current,
        double left, double top, double right, double bottom) {
        String oldImage = before.image(), newImage = current.image();
        if (oldImage == null || oldImage.isBlank() || newImage == null || newImage.isBlank()) return false;
        if (oldImage.equals(newImage)) return true;
        Bitmap old = null, now = null;
        try {
            old = decode(oldImage);
            now = decode(newImage);
            if (old == null || now == null || old.getWidth() != now.getWidth() || old.getHeight() != now.getHeight()) return false;
            int width = old.getWidth(), height = old.getHeight();
            // Use decoded JPEG dimensions, not Frame.imageWidth: legacy physical frames carry screen size.
            // At least 32 JPEG pixels of context on each side prevents a single unchanged pixel approving a tap.
            int margin = Math.max(32, (int) Math.ceil(Math.min(width, height) * .06));
            int x0 = Math.max(0, (int) Math.floor(left * width / before.width()) - margin);
            int y0 = Math.max(0, (int) Math.floor(top * height / before.height()) - margin);
            int x1 = Math.min(width, (int) Math.ceil(right * width / before.width()) + margin);
            int y1 = Math.min(height, (int) Math.ceil(bottom * height / before.height()) + margin);
            if (x1 - x0 < 16 || y1 - y0 < 16) return false;
            int[] a = new int[64], b = new int[64];
            for (int y = y0; y < y1; y += 8) {
                for (int x = x0; x < x1; x += 8) {
                    int w = Math.min(8, x1 - x), h = Math.min(8, y1 - y);
                    old.getPixels(a, 0, w, x, y, w, h);
                    now.getPixels(b, 0, w, x, y, w, h);
                    int total = 0;
                    for (int index = 0; index < w * h; index++) {
                        for (int shift = 0; shift <= 16; shift += 8) {
                            int delta = Math.abs(((a[index] >> shift) & 255) - ((b[index] >> shift) & 255));
                            if (delta > 16) return false;
                            total += delta;
                        }
                    }
                    // Tolerate small JPEG rounding, but do not average a changed icon across the whole screen.
                    if (total > w * h * 3 * 3) return false;
                }
            }
            return true;
        } catch (RuntimeException invalidImage) {
            return false;
        } finally {
            if (old != null) old.recycle();
            if (now != null) now.recycle();
        }
    }

    private static Bitmap decode(String image) {
        byte[] bytes = Base64.decode(image, Base64.DEFAULT);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long) bounds.outWidth * bounds.outHeight > 4_194_304) return null;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }
}
