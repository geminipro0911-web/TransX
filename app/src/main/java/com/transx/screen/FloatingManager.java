package com.transx.screen;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import com.transx.screen.ocr.InPlaceOverlayView;
import com.transx.screen.ocr.OfflineOcrEngine;
import com.transx.screen.ocr.RegionSelectorView;
import java.util.List;

public class FloatingManager {
    private final Context context;
    private final WindowManager wm;
    private String mode;

    private FloatingBubbleView bubbleView;
    private WindowManager.LayoutParams bubbleParams;

    private FloatingSubtitleView subtitleView;
    private WindowManager.LayoutParams subtitleParams;

    private RegionSelectorView regionSelectorView;

    private InPlaceOverlayView inPlaceOverlayView;
    private WindowManager.LayoutParams inPlaceParams;

    public interface FloatingListener {
        void onToggleListening();
        void onToggleLiveOcr();
        void onStopService();
        void onToggleTts();
        void onCaptureScreenRequested();
        void onRegionCropRequested(Rect region);
        void onClearHistory();
        void onCloseSubtitle();
        void onToggleInPlace(boolean enabled);
    }

    private final FloatingListener listener;

    public FloatingManager(Context context, String mode, FloatingListener listener) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.mode = mode;
        this.listener = listener;
    }

    public void setMode(String mode) {
        this.mode = mode;
        if (bubbleView != null) {
            bubbleView.setLabel("mode_ocr".equals(mode) ? "OCR" : ("mode_stt".equals(mode) ? "STT" : "TX"));
        }
        if (subtitleView != null) {
            subtitleView.applyMode(mode);
        }
    }

    public void setLiveOcrState(boolean isRunning) {
        if (subtitleView != null) {
            subtitleView.setLiveOcrState(isRunning);
        }
    }

    public void showAll() {
        showBubble();
        showSubtitle();
        if (new com.transx.screen.util.Prefs(context).isInPlaceMode()) {
            showInPlaceOverlay();
            if (subtitleView != null) {
                subtitleView.setVisibility(View.GONE);
            }
        }
    }

    public void showBubble() {
        if (bubbleView != null) return;

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        bubbleParams = new WindowManager.LayoutParams(
                dp(56), dp(56),
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = dp(16);
        bubbleParams.y = dp(240);

        bubbleView = new FloatingBubbleView(context, wm, bubbleParams, new FloatingBubbleView.OnBubbleClickListener() {
            @Override
            public void onBubbleClick() {
                toggleSubtitleVisibility();
            }

            @Override
            public void onBubbleLongClick() {
                if ("mode_ocr".equals(mode)) {
                    if (listener != null) listener.onToggleLiveOcr();
                } else {
                    if (listener != null) listener.onToggleListening();
                }
            }
        });

        bubbleView.setLabel("mode_ocr".equals(mode) ? "OCR" : ("mode_stt".equals(mode) ? "STT" : "TX"));

        try { wm.addView(bubbleView, bubbleParams); } catch (Exception ignored) {}
    }

    public void showSubtitle() {
        if (subtitleView != null) return;

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        int initW = Math.min(dp(350), screenW - dp(24));
        int initH = dp(240);

        subtitleParams = new WindowManager.LayoutParams(
                initW, initH,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        subtitleParams.gravity = Gravity.TOP | Gravity.START;
        subtitleParams.x = Math.max(0, (screenW - initW) / 2);
        subtitleParams.y = Math.max(0, screenH - initH - dp(80));

        subtitleView = new FloatingSubtitleView(context, wm, subtitleParams, mode, new FloatingSubtitleView.SubtitleActionListener() {
            @Override
            public void onClose() {
                hideSubtitle();
                if (listener != null) listener.onCloseSubtitle();
            }

            @Override
            public void onToggleTts() {
                if (listener != null) listener.onToggleTts();
            }

            @Override
            public void onToggleListening() {
                if (listener != null) listener.onToggleListening();
            }

            @Override
            public void onToggleLiveOcr() {
                if (listener != null) listener.onToggleLiveOcr();
            }

            @Override
            public void onCaptureScreen() {
                if (listener != null) listener.onCaptureScreenRequested();
            }

            @Override
            public void onSelectRegion() {
                showRegionSelector();
            }

            @Override
            public void onClearHistory() {
                if (listener != null) listener.onClearHistory();
            }

            @Override
            public void onToggleInPlace(boolean enabled) {
                if (enabled) {
                    showInPlaceOverlay();
                    if (subtitleView != null) {
                        subtitleView.setVisibility(View.GONE);
                    }
                } else {
                    hideInPlaceOverlay();
                    if (subtitleView != null) {
                        subtitleView.setVisibility(View.VISIBLE);
                    }
                }
                if (listener != null) listener.onToggleInPlace(enabled);
            }
        });

        try { wm.addView(subtitleView, subtitleParams); } catch (Exception ignored) {}
    }

    public void showRegionSelector() {
        if (regionSelectorView != null) return;

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams rParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        regionSelectorView = new RegionSelectorView(context);
        regionSelectorView.setListener(new RegionSelectorView.Listener() {
            @Override
            public void onRegionSelected(Rect region) {
                hideRegionSelector();
                if (listener != null) listener.onRegionCropRequested(region);
            }

            @Override
            public void onCancel() {
                hideRegionSelector();
            }
        });

        try { wm.addView(regionSelectorView, rParams); } catch (Exception ignored) {}
    }

    public void hideRegionSelector() {
        if (regionSelectorView != null) {
            try { wm.removeView(regionSelectorView); } catch (Exception ignored) {}
            regionSelectorView = null;
        }
    }

    public void showInPlaceOverlay() {
        if (inPlaceOverlayView != null) return;

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        inPlaceParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );

        inPlaceOverlayView = new InPlaceOverlayView(context);
        try { wm.addView(inPlaceOverlayView, inPlaceParams); } catch (Exception ignored) {}
    }

    public void hideInPlaceOverlay() {
        if (inPlaceOverlayView != null) {
            inPlaceOverlayView.clearBlocks();
            try { wm.removeView(inPlaceOverlayView); } catch (Exception ignored) {}
            inPlaceOverlayView = null;
        }
    }

    public void updateInPlaceBlocks(List<OfflineOcrEngine.OcrBlock> blocks) {
        if (inPlaceOverlayView != null) {
            inPlaceOverlayView.setBlocks(blocks);
        }
    }

    public void hideTemporarily(Runnable action) {
        final int prevSubtitleVis = (subtitleView != null) ? subtitleView.getVisibility() : View.GONE;
        if (bubbleView != null) bubbleView.setVisibility(View.INVISIBLE);
        if (subtitleView != null) subtitleView.setVisibility(View.INVISIBLE);
        if (inPlaceOverlayView != null) inPlaceOverlayView.setVisibility(View.INVISIBLE);

        if (subtitleView != null) {
            subtitleView.postDelayed(() -> {
                try {
                    action.run();
                } finally {
                    if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
                    if (subtitleView != null) subtitleView.setVisibility(prevSubtitleVis);
                    if (inPlaceOverlayView != null) inPlaceOverlayView.setVisibility(View.VISIBLE);
                }
            }, 50);
        } else {
            action.run();
        }
    }

    public void hideSubtitle() {
        if (subtitleView != null) {
            try { wm.removeView(subtitleView); } catch (Exception ignored) {}
            subtitleView = null;
        }
    }

    public void toggleSubtitleVisibility() {
        if (subtitleView != null) {
            if (subtitleView.getVisibility() == View.VISIBLE) {
                subtitleView.setVisibility(View.GONE);
            } else {
                subtitleView.setVisibility(View.VISIBLE);
            }
        } else {
            showSubtitle();
        }
    }

    public void updateStatus(String text, int dotColor) {
        if (subtitleView != null) subtitleView.setStatus(text);
        if (bubbleView != null) bubbleView.setStatusColor(dotColor);
    }

    public void addTranslation(String orig, String trans) {
        if (subtitleView != null) {
            subtitleView.addTranslation(orig, trans);
        }
    }

    public void removeAll() {
        hideRegionSelector();
        hideInPlaceOverlay();
        if (bubbleView != null) {
            try { wm.removeView(bubbleView); } catch (Exception ignored) {}
            bubbleView = null;
        }
        if (subtitleView != null) {
            try { wm.removeView(subtitleView); } catch (Exception ignored) {}
            subtitleView = null;
        }
    }

    private int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density);
    }
}
