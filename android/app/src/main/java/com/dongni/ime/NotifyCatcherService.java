package com.dongni.ime;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 通知监听：微信消息到达时，从通知横幅提取"发送者 + 内容"，写入本地聊天记录。
 *
 * 这是上下文的主来源——无需截屏、无障碍读屏，观感干净；用户在任何界面收到微信
 * 消息都会被记录（不是"对方"自己的通知不处理）。自己发出的消息由输入法在
 * 发送动作里记录，合成完整对话流。
 */
public class NotifyCatcherService extends NotificationListenerService {

    public static volatile long lastCatchTs = 0;
    public static volatile int caught = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        NotifyStore.init(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        if (!"com.tencent.mm".equals(sbn.getPackageName())) return;

        android.os.Bundle ex = sbn.getNotification().extras;
        if (ex == null) return;
        String title = ex.getString(android.app.Notification.EXTRA_TITLE, "");
        CharSequence bigTextCs = ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);
        CharSequence textCs = ex.getCharSequence(android.app.Notification.EXTRA_TEXT);
        String content = bigTextCs != null && bigTextCs.length() > 0
                ? bigTextCs.toString() : (textCs == null ? "" : textCs.toString());
        if (title.isEmpty() || content.isEmpty()) return;
        // 系统占位/持续类通知（如"微信""正在运行"）不是消息
        if (title.equals("微信")) return;
        if (content.contains("条新消息") && content.length() < 20) return;

        // 群聊通知 content 形如 "张三: 你好"——把发送者并进 who
        String who = title;
        for (String sep : new String[]{"：", ": "}) {
            int idx = content.indexOf(sep);
            if (idx > 0 && idx <= 24 && title.length() >= 2 && !content.startsWith(title)) {
                // 只有当 content 的前缀不像完整消息而像"人名: 内容"时才拆分（保守：长度限制）
                who = content.substring(0, idx);
                content = content.substring(idx + sep.length());
                break;
            }
        }

        NotifyStore.add(who, content, sbn.getPostTime());
        lastCatchTs = System.currentTimeMillis();
        caught++;
        android.util.Log.d("TopKIME", "notify: +" + who + "：" + content);
    }
}
