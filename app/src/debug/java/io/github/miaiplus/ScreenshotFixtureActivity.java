package io.github.miaiplus;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.widget.ImageView;

/** An owned image viewer: calculator pixels are deliberately not a live calculator. */
public final class ScreenshotFixtureActivity extends Activity {
    static volatile ScreenshotFixtureActivity current;
    private Bitmap image;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;
        if (getIntent().getBooleanExtra("reference_video", false)) {
            image = BitmapFactory.decodeFile(new java.io.File(getCacheDir(), "native-foreground-reference.jpg").getAbsolutePath());
            if (image == null) throw new IllegalStateException("Owned video reference image unavailable");
        } else image = calculatorImage();
        ImageView viewer = new ImageView(this);
        viewer.setContentDescription("图片 1/1");
        viewer.setScaleType(ImageView.ScaleType.FIT_XY);
        viewer.setImageBitmap(image);
        setContentView(viewer);
    }
    private static Bitmap calculatorImage() {
        Bitmap image = Bitmap.createBitmap(1080, 2200, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(Color.rgb(250, 250, 250));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(68);
        canvas.drawText("137×29", 1000, 570, paint);
        paint.setTextSize(110);
        canvas.drawText("3,973", 1000, 740, paint);
        String[][] keys = {{"AC", "+/−", "%", "÷"}, {"7", "8", "9", "×"},
            {"4", "5", "6", "−"}, {"1", "2", "3", "+"}, {"0", ".", "⌫", "="}};
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(64);
        for (int row = 0; row < keys.length; row++) {
            for (int column = 0; column < keys[row].length; column++) {
                paint.setColor(column == 3 ? Color.rgb(255, 110, 0) : Color.BLACK);
                canvas.drawText(keys[row][column], 140 + column * 265, 1050 + row * 220, paint);
            }
        }
        return image;
    }
    @Override public void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
        if (image != null) { image.recycle(); image = null; }
    }
}
