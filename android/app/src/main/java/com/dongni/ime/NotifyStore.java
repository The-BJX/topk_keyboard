package com.dongni.ime;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地聊天记录：主来源是通知监听（对方消息）+ 输入法自身发送记录（我的消息），
 * 截屏 OCR 的结果也并入去重（用户向上滑动后多点几次按钮，等于手动"截长屏补上文"）。
 * 数据只存在本机（app 私有目录），按时间正序全局保存最近 60 条。
 */
public class NotifyStore {

    /** 一条消息：who=发送者名（"我"=用户自己，"微信"=解析不出发送者的兜底），text=内容，ts=毫秒时间戳。 */
    public static class Msg {
        public final String who;
        public final String text;
        public final long ts;

        Msg(String who, String text, long ts) {
            this.who = who;
            this.text = text;
            this.ts = ts;
        }
    }

    private static final int MAX_MSGS = 60;
    private static final long KEEP_MS = 7L * 24 * 3600 * 1000; // 7 天外的清理
    private static final Object LOCK = new Object();
    private static final List<Msg> msgs = new ArrayList<>();
    private static File file = null;

    public static void init(Context ctx) {
        synchronized (LOCK) {
            if (file != null) return;
            file = new File(ctx.getFilesDir(), "chat_log.json");
            try {
                String s = readAll(file);
                if (!s.isEmpty()) {
                    JSONArray arr = new JSONArray(s);
                    long cutoff = System.currentTimeMillis() - KEEP_MS;
                    for (int i = 0; i < arr.length() && msgs.size() < MAX_MSGS; i++) {
                        JSONObject o = arr.getJSONObject(i);
                        long ts = o.optLong("ts", 0);
                        String t = o.optString("text", "");
                        if (ts < cutoff || t.isEmpty()) continue;
                        msgs.add(new Msg(o.optString("who", "微信"), t, ts));
                    }
                }
            } catch (Exception ignored) {
                msgs.clear();
            }
        }
    }

    /** 追加一条消息；与最近一条完全相同（同 who 同 text）且间隔 10 秒内视为重复，忽略。 */
    public static void add(String who, String text, long ts) {
        if (text == null || (text = text.trim()).isEmpty()) return;
        if (who == null || who.trim().isEmpty()) who = "微信";
        synchronized (LOCK) {
            if (!msgs.isEmpty()) {
                Msg last = msgs.get(msgs.size() - 1);
                if (last.who.equals(who) && last.text.equals(text) && Math.abs(last.ts - ts) < 10_000) return;
            }
            msgs.add(new Msg(who, text, ts));
            while (msgs.size() > MAX_MSGS) msgs.remove(0);
            save();
        }
    }

    /** OCR 行并入：同一文本若已存在于最近 40 条中则跳过（向上滑动重读旧消息时不会重复）。 */
    public static void mergeOcr(String who, String text, long ts) {
        if (text == null || (text = text.trim()).isEmpty()) return;
        synchronized (LOCK) {
            int from = Math.max(0, msgs.size() - 40);
            for (int i = from; i < msgs.size(); i++) {
                if (msgs.get(i).text.equals(text)) return;
            }
        }
        add(who, text, ts);
    }

    /** 最近 n 条，时间正序。 */
    public static List<Msg> recent(int n) {
        synchronized (LOCK) {
            int from = Math.max(0, msgs.size() - n);
            return new ArrayList<>(msgs.subList(from, msgs.size()));
        }
    }

    public static int count() {
        synchronized (LOCK) { return msgs.size(); }
    }

    public static void clear() {
        synchronized (LOCK) {
            msgs.clear();
            save();
        }
    }

    /** 拼成给 LLM 的上下文。 */
    public static String buildContext(int n) {
        List<Msg> last = recent(n);
        if (last.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("最近聊天记录（按时间顺序）：\n");
        for (Msg m : last) sb.append(m.who).append("：").append(m.text).append('\n');
        return sb.toString();
    }

    private static void save() {
        if (file == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Msg m : msgs) {
                JSONObject o = new JSONObject();
                o.put("who", m.who);
                o.put("text", m.text);
                o.put("ts", m.ts);
                arr.put(o);
            }
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(file); // 原子替换，崩溃也不会损坏记录
        } catch (Exception ignored) {
        }
    }

    private static String readAll(File f) {
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }
}
