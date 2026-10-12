package com.dongni.ime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** 设置与启用引导：填 DeepSeek API Key、跳转系统设置启用输入法并切换。 */
public class SettingsActivity extends Activity {

    private EditText keyInput, urlInput;
    private TextView stateView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        SharedPreferences prefs = getSharedPreferences(ImeService.PREFS, Context.MODE_PRIVATE);
        keyInput = findViewById(R.id.input_key);
        urlInput = findViewById(R.id.input_url);
        stateView = findViewById(R.id.state_view);

        keyInput.setText(prefs.getString("api_key", ""));
        urlInput.setText(prefs.getString("api_url", ""));

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            prefs.edit()
                    .putString("api_key", keyInput.getText().toString().trim())
                    .putString("api_url", urlInput.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            refreshState();
        });

        findViewById(R.id.btn_test).setOnClickListener(v -> {
            prefs.edit()
                    .putString("api_key", keyInput.getText().toString().trim())
                    .putString("api_url", urlInput.getText().toString().trim())
                    .apply();
            TextView result = findViewById(R.id.test_result);
            Button testBtn = (Button) v;
            result.setVisibility(TextView.VISIBLE);
            result.setText("测试中…");
            testBtn.setEnabled(false);
            new Thread(() -> {
                DeepSeekClient probe = new DeepSeekClient(
                        urlInput.getText().toString(), keyInput.getText().toString());
                StringBuilder info = new StringBuilder();
                String err = probe.ping(info);
                runOnUiThread(() -> {
                    testBtn.setEnabled(true);
                    result.setVisibility(TextView.VISIBLE);
                    if (err == null) {
                        result.setTextColor(0xFF0A7C3F);
                        result.setText("✅ 连接正常：" + info);
                    } else {
                        result.setTextColor(0xFFB45309);
                        result.setText("❌ 失败：" + err
                                + "\n若提示网络类错误，请在 系统设置→应用管理→懂你意思输入法→联网控制 里允许 WLAN 与数据流量。");
                    }
                });
            }).start();
        });

        findViewById(R.id.btn_enable).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));

        findViewById(R.id.btn_switch).setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });

        findViewById(R.id.btn_a11y).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // 通知使用权：微信消息自动捕获的主开关
        findViewById(R.id.btn_notify).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        // 清空本地聊天记录
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> {
            NotifyStore.clear();
            Toast.makeText(this, "已清空本地聊天记录", Toast.LENGTH_SHORT).show();
            refreshState();
        });

        findViewById(R.id.btn_debug).setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予“显示在其他应用上层”权限", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
                return;
            }
            boolean running = DebugOverlayService.running;
            Intent it = new Intent(this, DebugOverlayService.class);
            if (running) {
                DebugOverlayService.running = false; // stopService 异步，先同步标志避免状态显示颠倒
                stopService(it);
            } else {
                DebugOverlayService.running = true;
                startService(it);
            }
            refreshState();
        });

        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private void refreshState() {
        String imeId = getPackageName() + "/" + ImeService.class.getName();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean enabled = false;
        if (imm != null) {
            for (android.view.inputmethod.InputMethodInfo info : imm.getEnabledInputMethodList()) {
                if (imeId.equals(info.getId())) {
                    enabled = true;
                    break;
                }
            }
        }
        String state = enabled
                ? "✅ 已在系统中启用。到任意输入框长按键盘切换，或点下方按钮选择本输入法。"
                : "尚未启用：请点“第 1 步”进入系统设置，找到“懂你意思输入法”并打开开关，然后回来点“第 2 步”。";

        // 微信消息采集（无障碍截屏 OCR 兜底）状态
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        boolean a11y = false;
        String readerId = getPackageName() + "/" + WeChatReaderService.class.getName();
        if (am != null) {
            for (android.accessibilityservice.AccessibilityServiceInfo info
                    : am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (readerId.equals(info.getId())) {
                    a11y = true;
                    break;
                }
            }
        }

        // 通知使用权（主上下文来源）状态
        String nlId = android.service.notification.NotificationListenerService.class.getName();
        boolean notifyOn = false;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            for (String s : android.provider.Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners").split(":")) {
                if (s.equals(getPackageName() + "/" + nlId)) {
                    notifyOn = true;
                    break;
                }
            }
        }

        state += notifyOn
                ? "\n✅ 微信通知自动记录已开启：收到微信消息会自动存入本地聊天记录（点「📋 对方」即可使用）。"
                : "\n（强烈建议）点「第 4 步」授予通知使用权：收到微信消息自动记录，是上下文的主要来源。";

        state += a11y
                ? "\n✅ 屏幕识别兜底已开启（截屏+本地OCR）。"
                : "\n（可选）第 3 步的屏幕识别兜底未开启。";
        if (a11y) {
            long now = System.currentTimeMillis();
            state += "\n【屏幕识别诊断】\n· 最近微信事件：" + describeTime(WeChatReaderService.lastWechatTs, now)
                    + "，已采集 " + WeChatReaderService.collectCalls + " 次"
                    + "\n· 最近识别：" + WeChatReaderService.lastCollectPreview;
        }

        // 本地聊天记录统计
        java.util.List<NotifyStore.Msg> log = NotifyStore.recent(3);
        state += "\n【本地聊天记录】" + NotifyStore.count() + " 条（仅存本机，最多 60 条，7 天自动清理）";
        for (NotifyStore.Msg m : log) {
            String flat = m.text.replace('\n', ' ');
            if (flat.length() > 24) flat = flat.substring(0, 24) + "…";
            state += "\n· " + m.who + "：" + flat;
        }
        state += "\n🐞 调试悬浮窗：" + (DebugOverlayService.running
                ? "已开启，屏幕上会显示发给 LLM 的完整请求/响应包体（可拖动，点 🐞 展开/收起）。"
                : "未开启。点下方按钮开启后可看到每次请求的完整包体，用于排查候选与上下文不符的问题。");
        stateView.setText(state);
    }

    private static String describeTime(long ts, long now) {
        if (ts == 0) return "从未";
        long sec = (now - ts) / 1000;
        if (sec < 5) return "刚刚";
        if (sec < 60) return sec + " 秒前";
        if (sec < 3600) return (sec / 60) + " 分钟前";
        return (sec / 3600) + " 小时前";
    }
}
