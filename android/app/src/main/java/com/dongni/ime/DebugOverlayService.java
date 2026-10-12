package com.dongni.ime;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 调试悬浮窗：实时显示最近一次发给 LLM 的完整请求/响应包体。
 * 可拖动，默认收起为一个小按钮，点击展开滚动文本，再点收起。
 * 需要悬浮窗权限（设置页开关会引导授权）。
 */
public class DebugOverlayService extends Service {

    /** 服务是否在运行（设置页开关状态用）。 */
    public static volatile boolean running = false;

    private WindowManager wm;
    private View root;
    private TextView body;
    private TextView handle;
    private final Handler tick = new Handler(Looper.getMainLooper());
    private boolean expanded = false;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (body != null) {
                CharSequence d = DeepSeekClient.debugDump();
                if (!TextUtils.equals(body.getText(), d)) body.setText(d);
            }
            tick.postDelayed(this, 1000);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        handle = new TextView(this);
        handle.setText("🐞");
        handle.setTextSize(20);
        handle.setPadding(18, 10, 18, 10);
        handle.setBackgroundColor(0xCC222222);

        body = new TextView(this);
        body.setTextColor(Color.WHITE);
        body.setTextSize(11);
        body.setPadding(16, 12, 16, 16);
        body.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xE6111111);
        box.addView(handle);
        box.addView(scroll);

        root = box;
        setExpanded(false);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 24;
        p.y = 220;
        wm.addView(root, p);

        // 拖动 + 点击：按住拖动；松开时若几乎没移动则视为点击（展开/收起）
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;

            @Override public boolean onTouch(View v, MotionEvent e) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + (int) (e.getRawX() - downX);
                        lp.y = startY + (int) (e.getRawY() - downY);
                        wm.updateViewLayout(root, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (dx * dx + dy * dy < 100) setExpanded(!expanded); // 位移 <10px 算点击
                        return true;
                    default:
                        return false;
                }
            }
        });

        tick.post(refresh);
    }

    private void setExpanded(boolean exp) {
        expanded = exp;
        body.setVisibility(exp ? View.VISIBLE : View.GONE);
        handle.setText(exp ? "🐞 调试（点此收起）" : "🐞");
        if (exp) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            wm.updateViewLayout(root, lp);
            body.setText(DeepSeekClient.debugDump());
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        tick.removeCallbacks(refresh);
        if (root != null && wm != null) wm.removeView(root);
        super.onDestroy();
    }
}
