package com.transx.screen.ocr;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.util.Log;
import java.nio.ByteBuffer;

public class ScreenCapturer {
    private static final String TAG = "ScreenCapturer";

    private final MediaProjection projection;
    private final int width;
    private final int height;
    private final int dpi;

    private ImageReader reader;
    private VirtualDisplay display;

    public ScreenCapturer(MediaProjection projection, int width, int height, int dpi) {
        this.projection = projection;
        this.width = Math.max(width, 100);
        this.height = Math.max(height, 100);
        this.dpi = Math.max(dpi, 160);
    }

    public synchronized void start() {
        stop();
        if (projection == null) {
            Log.e(TAG, "Cannot start ScreenCapturer: MediaProjection is null");
            return;
        }

        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            display = projection.createVirtualDisplay(
                    "TransX_ScreenCapture",
                    width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(),
                    null, null
            );
            Log.i(TAG, "ScreenCapturer started: " + width + "x" + height + " dpi=" + dpi);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to create VirtualDisplay", e);
        }
    }

    public synchronized void stop() {
        if (display != null) {
            try { display.release(); } catch (Exception ignored) {}
            display = null;
        }
        if (reader != null) {
            try { reader.close(); } catch (Exception ignored) {}
            reader = null;
        }
    }

    public Bitmap captureScreen() {
        return captureRegion(new Rect(0, 0, width, height));
    }

    public Bitmap captureRegion(Rect region) {
        if (reader == null) return null;

        Image img = null;
        long startWait = System.currentTimeMillis();
        // Chờ tối đa 600ms để lấy frame mới nhất
        while (img == null && System.currentTimeMillis() - startWait < 600) {
            try {
                img = reader.acquireLatestImage();
                if (img == null) {
                    Thread.sleep(40);
                }
            } catch (Exception ignored) {}
        }

        if (img == null) {
            Log.w(TAG, "No image acquired from ImageReader");
            return null;
        }

        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowWidth = rowStride / pixelStride;

            Bitmap full = Bitmap.createBitmap(rowWidth, height, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buffer);

            // Cắt phần ảnh thực tế theo chiều rộng (loại bỏ padding của rowStride)
            int actualW = Math.min(width, full.getWidth());
            int actualH = Math.min(height, full.getHeight());

            int l = Math.max(0, Math.min(region.left, actualW - 10));
            int t = Math.max(0, Math.min(region.top, actualH - 10));
            int r = Math.min(actualW, Math.max(l + 10, region.right));
            int b = Math.min(actualH, Math.max(t + 10, region.bottom));

            if (r <= l || b <= t) {
                full.recycle();
                return null;
            }

            Bitmap cropped = Bitmap.createBitmap(full, l, t, r - l, b - t);
            full.recycle();
            return cropped;
        } catch (Throwable e) {
            Log.e(TAG, "Error processing captured frame: " + e.getMessage());
            return null;
        } finally {
            img.close();
        }
    }
}
