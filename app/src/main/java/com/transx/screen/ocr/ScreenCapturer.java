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

    /**
     * Chụp lấy frame mới nhất cho live OCR (không block quá lâu).
     */
    public Bitmap captureLatestFrame(Rect region) {
        if (reader == null) return null;
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return null;

            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowWidth = rowStride / pixelStride;

            Bitmap full = Bitmap.createBitmap(rowWidth, height, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buffer);

            int actualW = Math.min(width, full.getWidth());
            int actualH = Math.min(height, full.getHeight());

            int l = (region != null) ? Math.max(0, Math.min(region.left, actualW - 10)) : 0;
            int t = (region != null) ? Math.max(0, Math.min(region.top, actualH - 10)) : 0;
            int r = (region != null) ? Math.min(actualW, Math.max(l + 10, region.right)) : actualW;
            int b = (region != null) ? Math.min(actualH, Math.max(t + 10, region.bottom)) : actualH;

            if (r <= l || b <= t) {
                full.recycle();
                return null;
            }

            Bitmap cropped = Bitmap.createBitmap(full, l, t, r - l, b - t);
            full.recycle();
            return cropped;
        } catch (Throwable e) {
            return null;
        } finally {
            if (img != null) {
                try { img.close(); } catch (Exception ignored) {}
            }
        }
    }

    public Bitmap captureRegion(Rect region) {
        if (reader == null) return null;

        Image img = null;
        long startWait = System.currentTimeMillis();
        // Chờ tối đa 600ms để lấy frame mới nhất (dùng cho chụp 1 lần thủ công)
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

    /**
     * Tính mã băm trung bình 8x8 (64-bit) để phát hiện sự thay đổi nội dung màn hình cực nhanh (<0.5ms).
     */
    public static long averageHash(Bitmap bmp) {
        if (bmp == null || bmp.getWidth() < 8 || bmp.getHeight() < 8) return 0;
        int W = 8, H = 8;
        Bitmap small = Bitmap.createScaledBitmap(bmp, W, H, true);
        int[] px = new int[W * H];
        small.getPixels(px, 0, W, 0, 0, W, H);
        if (small != bmp) small.recycle();

        long sum = 0;
        int[] gray = new int[W * H];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int g = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
            gray[i] = g;
            sum += g;
        }
        int avg = (int) (sum / px.length);
        long hash = 0;
        for (int g : gray) {
            hash = (hash << 1) | (g >= avg ? 1 : 0);
        }
        return hash;
    }

    public static long subtitleRegionHash(Bitmap bmp) {
        if (bmp == null || bmp.getWidth() < 8 || bmp.getHeight() < 8) return 0;
        int top = (int) (bmp.getHeight() * 0.65f);
        int h = bmp.getHeight() - top;
        if (h < 8) return averageHash(bmp);
        Bitmap bottomPortion = Bitmap.createBitmap(bmp, 0, top, bmp.getWidth(), h);
        long hash = averageHash(bottomPortion);
        if (bottomPortion != bmp) bottomPortion.recycle();
        return hash;
    }

    public static int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }
}
