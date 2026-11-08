package com.hyperosmod.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Smoke test for the custom status bar icon: it must measure and paint something. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DualSignalViewTest {

    @Test
    public void drawsTypeAndTwoSignalRows() {
        Context context = RuntimeEnvironment.getApplication();
        DualSignalView view = new DualSignalView(context);
        view.setState("5G", 4, 3, Color.WHITE);

        int height = Math.round(24f * context.getResources().getDisplayMetrics().density);
        view.measure(
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());

        // The view must reserve space for the type text and both bar rows.
        assertTrue("width=" + view.getMeasuredWidth(), view.getMeasuredWidth() > 20);
        assertEquals(height, view.getMeasuredHeight());

        Bitmap bitmap = Bitmap.createBitmap(
                view.getMeasuredWidth(), view.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        assertTrue(countOpaque(bitmap) > 20);
    }

    @Test
    public void scaleAndThicknessChangeWidth() {
        Context context = RuntimeEnvironment.getApplication();
        DualSignalView view = new DualSignalView(context);
        view.setState("4G", 4, 3, Color.WHITE);
        measure(view);
        int baseWidth = view.getMeasuredWidth();

        view.setScale(175, 150, 125, 125);
        measure(view);
        assertTrue(view.getMeasuredWidth() > baseWidth);
    }

    @Test
    public void emptyTypeIsNarrower() {
        Context context = RuntimeEnvironment.getApplication();
        DualSignalView withType = new DualSignalView(context);
        withType.setState("5G", 2, 1, Color.WHITE);
        measure(withType);
        DualSignalView withoutType = new DualSignalView(context);
        withoutType.setState("", 2, 1, Color.WHITE);
        measure(withoutType);
        assertTrue(withType.getMeasuredWidth() > withoutType.getMeasuredWidth());
    }

    private static void measure(DualSignalView view) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(24, View.MeasureSpec.EXACTLY));
    }

    private static int countOpaque(Bitmap bitmap) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0,
                bitmap.getWidth(), bitmap.getHeight());
        int count = 0;
        for (int pixel : pixels) {
            if (Color.alpha(pixel) != 0) {
                count++;
            }
        }
        return count;
    }
}
