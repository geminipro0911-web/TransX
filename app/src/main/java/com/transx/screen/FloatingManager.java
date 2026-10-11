package com.transx.screen;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;

public class FloatingManager {
    private final Context context;
    private final WindowManager wm;

    private FloatingBubbleView bubbleView;
    private WindowManager.LayoutParams bubbleParams;

    private FloatingSubtitleView subtitleView;
    private WindowManager.LayoutParams subtitleParams;

    public interface FloatingListener {
        void onToggleListening();
        void onStopService();
        void onToggleTts();
    }

    private final FloatingListener listener;

    public FloatingManager(Context context, FloatingListener listener) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.listener = listener;
    }

    public void showAll() {
        showBubble();
        showSubtitle();
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
        bubbleParams.x = 40;
        bubbleParams.y = 300;

        bubbleView = new FloatingBubbleView(context, wm, bubbleParams, new FloatingBubbleView.OnBubbleClickListener() {
            @Override
            public void onBubbleClick() {
                toggleSubtitleVisibility();
            }

            @Override
            public void onBubbleLongClick() {
                if (listener != null) listener.onToggleListening();
            }
        });

        try { wm.addView(bubbleView, bubbleParams); } catch (Exception ignored) {}
    }

    public void showSubtitle() {
        if (subtitleView != null) return;

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        subtitleParams = new WindowManager.LayoutParams(
                dp(320), dp(220),
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        subtitleParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        subtitleParams.y = dp(80);

        subtitleView = new FloatingSubtitleView(context, wm, subtitleParams, new FloatingSubtitleView.SubtitleActionListener() {
            @Override
            public void onClose() {
                hideSubtitle();
            }

            @Override
            public void onToggleTts() {
                if (listener != null) listener.onToggleTts();
            }
        });

        try { wm.addView(subtitleView, subtitleParams); } catch (Exception ignored) {}
    }

    public void toggleSubtitleVisibility() {
        if (subtitleView != null) {
            hideSubtitle();
        } else {
            showSubtitle();
        }
    }

    public void hideSubtitle() {
        if (subtitleView != null) {
            try { wm.removeView(subtitleView); } catch (Exception ignored) {}
            subtitleView = null;
        }
    }

    public void updateStatus(String status, int dotColor) {
        if (subtitleView != null) subtitleView.setStatus(status);
        if (bubbleView != null) bubbleView.setStatusColor(dotColor);
    }

    public void addTranslation(String orig, String trans) {
        if (subtitleView == null) showSubtitle();
        if (subtitleView != null) subtitleView.addTranslation(orig, trans);
    }

    public void removeAll() {
        hideSubtitle();
        if (bubbleView != null) {
            try { wm.removeView(bubbleView); } catch (Exception ignored) {}
            bubbleView = null;
        }
    }

    private int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density);
    }
}
