package com.transx.screen;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

public class FloatingBubbleView extends FrameLayout {
    public interface OnBubbleClickListener {
        void onBubbleClick();
        void onBubbleLongClick();
    }

    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private final OnBubbleClickListener listener;
    private final View statusIndicator;
    private float initialTouchX, initialTouchY;
    private int initialX, initialY;
    private boolean isDragging = false;

    public FloatingBubbleView(Context context, WindowManager wm, WindowManager.LayoutParams params, OnBubbleClickListener listener) {
        super(context);
        this.wm = wm;
        this.params = params;
        this.listener = listener;

        int size = dp(56);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#2563EB"));
        bg.setStroke(dp(2), Color.parseColor("#38BDF8"));
        setBackground(bg);

        TextView icon = new TextView(context);
        icon.setText("TX");
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        icon.setTypeface(icon.getTypeface(), android.graphics.Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        LayoutParams iconLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(icon, iconLp);

        statusIndicator = new View(context);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Color.parseColor("#10B981"));
        statusIndicator.setBackground(dot);
        LayoutParams dotLp = new LayoutParams(dp(12), dp(12));
        dotLp.gravity = Gravity.BOTTOM | Gravity.END;
        dotLp.rightMargin = dp(4);
        dotLp.bottomMargin = dp(4);
        addView(statusIndicator, dotLp);

        setOnTouchListener(this::handleTouch);
    }

    public void setStatusColor(int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        statusIndicator.setBackground(dot);
    }

    private boolean handleTouch(View v, MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                initialX = params.x;
                initialY = params.y;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                    isDragging = true;
                    params.x = (int) (initialX + dx);
                    params.y = (int) (initialY + dy);
                    try { wm.updateViewLayout(this, params); } catch (Exception ignored) {}
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!isDragging) {
                    if (listener != null) listener.onBubbleClick();
                }
                return true;
        }
        return false;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
