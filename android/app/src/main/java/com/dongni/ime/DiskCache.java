package com.dongni.ime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 磁盘缓存：同一 (场景, 前缀, k, 批次, 立场倾向, 上一轮候选) 只让 LLM 算一次。线程安全，原子落盘。 */
public class DiskCache {

    private final File file;
    private final Object lock = new Object();
    private JSONObject data;

    public DiskCache(File file) {
        this.file = file;
        try {
            byte[] raw = readAll(file);
            data = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            data = new JSONObject();
        }
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return buf;
        } finally {
            in.close();
        }
    }

    private static String key(String context, String draft, int k, int batch, int stance, List<String> prev) {
        int prevHash = prev == null ? 0 : prev.hashCode();
        return Integer.toHexString((context + " " + draft + " " + k + " " + batch + " " + stance + " " + prevHash).hashCode())
                + "_" + Long.toHexString(context.hashCode() * 31L + draft.hashCode());
    }

    /** 命中返回候选文本数组，未命中返回 null。 */
    public String[] get(String context, String draft, int k, int batch, int stance, List<String> prev) {
        synchronized (lock) {
            JSONArray arr = data.optJSONArray(key(context, draft, k, batch, stance, prev));
            if (arr == null) return null;
            String[] out = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) out[i] = arr.optString(i);
            return out.length > 0 ? out : null;
        }
    }

    public void put(String context, String draft, int k, int batch, int stance, List<String> prev, String[] candidates) {
        synchronized (lock) {
            try {
                JSONArray arr = new JSONArray();
                for (String c : candidates) arr.put(c);
                data.put(key(context, draft, k, batch, stance, prev), arr);
                File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                out.write(data.toString().getBytes(StandardCharsets.UTF_8));
                out.close();
                if (!tmp.renameTo(file)) tmp.delete(); // 原子替换失败则丢弃，不影响主流程
            } catch (Exception ignored) {
            }
        }
    }
}
