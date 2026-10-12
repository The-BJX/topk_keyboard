package com.dongni.ime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DeepSeek 候选生成客户端（零依赖：HttpURLConnection + 平台内置 org.json）。网络调用必须在工作线程。 */
public class DeepSeekClient {

    public static final String DEFAULT_URL = "https://api.deepseek.com/chat/completions";

    private static final String SYSTEM_PROMPT =
            "你是一个为渐冻症、帕金森、失语症等打字困难人士设计的智能输入法候选生成器。用户无法打字，只能点击屏幕上的候选短语来组句，候选质量直接决定沟通效率。\n"
            + "\n任务：根据【场景】和【已输入】前缀，预测用户接下来最可能想输入的内容，给出 {k} 个候选短语。\n"
            + "\n规则：\n"
            + "1. text 是对【已输入】的续写短语，不要重复已输入的内容；长度 1~12 个字，符合日常口语。可以只是一个词、一个片段，也可以直接拼成完整的一句话。\n"
            + "2. 短语要按聊天软件里原本的样子带标点：句中停顿用“，”，问句末尾带“？”，感叹末尾带“！”，陈述收尾可带“。”，例如“好的，没问题”“几点？”“今晚有事，去不了”。候选“！”“？”“。”这类续接标点也可以。\n"
            + "3. k 个候选必须覆盖立场明显不同的回应方向（例如 答应 / 拒绝 / 反问细节 / 改期 / 表达为难）。严禁把同一种态度的几种措辞当成多个候选；即使某个立场可能性较低，也要保留 1~2 个，让用户能表达不同观点。\n"
            + "4. 短语可按聊天习惯自然带 emoji（如 😄👍😂🙏🎉），约三分之一的候选带即可，不要每条都带；emoji 要贴合语气与场景。\n"
            + "5. 候选之间按可能性大致从高到低排序，但以规则 3 的立场多样性优先。\n"
            + "6. 只输出如下格式的 JSON，不要输出任何其他内容：\n"
            + "{\"candidates\":[{\"text\":\"短语\"}]}";

    private static final String[] FALLBACK = {
            "好的，没问题", "嗯嗯，可以", "可能不行", "下次一定", "什么时候？", "在哪儿？",
            "几点？", "我快到了", "稍等一下", "谢谢！", "辛苦了😄", "我想想再说",
    };

    private final String apiUrl;
    private final String apiKey;
    public String lastError = null;

    public DeepSeekClient(String apiUrl, String apiKey) {
        this.apiUrl = (apiUrl == null || apiUrl.trim().isEmpty()) ? DEFAULT_URL : apiUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** 调试窗口显示用：当前 API 地址。 */
    public String apiUrlForDebug() {
        return apiUrl;
    }

    /** 连接测试：返回 null 表示成功（rttOut 会填入耗时与模型回复），否则返回错误描述。须在工作线程调用。 */
    public String ping(StringBuilder rttOut) {
        lastError = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", "deepseek-chat");
            body.put("max_tokens", 8);
            JSONArray msgs = new JSONArray();
            msgs.put(msg("user", "请只回复两个字：正常"));
            body.put("messages", msgs);
            long t0 = System.currentTimeMillis();
            lastKind = "连接测试";
            JSONObject resp = post(body.toString(), 15000);
            String content = resp.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim();
            rttOut.append(System.currentTimeMillis() - t0).append(" ms，模型回复：").append(content);
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    // ---- 调试记录：最近一次实际发出的请求与响应（调试悬浮窗展示） ----
    public static volatile String lastKind = "";
    public static volatile long lastTs = 0;
    public static volatile String lastUrl = null;
    public static volatile String lastRequestJson = null;
    public static volatile String lastResponseJson = null;
    public static volatile String lastFailure = null;

    /** 拼接调试悬浮窗要显示的完整文本。 */
    public static String debugDump() {
        if (lastRequestJson == null) return "尚未发起任何请求。\n打开微信聊天窗口停留几秒，再点「📋 对方」或输入文字触发候选。";
        StringBuilder sb = new StringBuilder();
        sb.append("== 最近请求（").append(lastKind)
                .append(" @ ").append(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                        .format(new java.util.Date(lastTs))).append("）==\n");
        sb.append("URL: ").append(lastUrl).append("\n\n【请求包体】\n").append(pretty(lastRequestJson));
        if (lastFailure != null) sb.append("\n\n【失败】").append(lastFailure);
        if (lastResponseJson != null) sb.append("\n\n【响应包体】\n").append(pretty(lastResponseJson));
        return sb.toString();
    }

    private static String pretty(String json) {
        try { return new JSONObject(json).toString(2); } catch (Exception e) { return json; }
    }

    /** 返回候选短语；失败时返回离线兜底池并记录 lastError。stance 为立场倾向值，prev 为上一轮候选（相对调制基准）。 */
    public String[] getCandidates(String context, String draft, int k, int batch,
                                  int stance, java.util.List<String> prev) {
        lastError = null;
        try {
            String user = "【场景】\n" + (context.isEmpty() ? "（微信聊天）" : context)
                    + "\n\n【已输入】" + (draft.isEmpty() ? "（空，给出开头短语）" : draft)
                    + "\n\n请给出 " + k + " 个候选短语。";

            // 立场/相对调制：把立场倾向值与上一轮候选交给模型，语气相对上一轮调整
            boolean hasPrev = prev != null && !prev.isEmpty();
            if (stance != 0 || hasPrev) {
                user += "\n【立场倾向】" + (stance > 0 ? "+" + stance : String.valueOf(stance))
                        + "（整数：0 中性，正数更积极，负数更消极）";
            }
            if (hasPrev) {
                StringBuilder sb = new StringBuilder("\n【上一轮候选】");
                for (int i = 0; i < prev.size(); i++) {
                    if (i > 0) sb.append(" / ");
                    sb.append(prev.get(i));
                }
                sb.append("\n注意：本批候选相对上一轮做调制：立场须与【立场倾向】一致，");
                if (stance > 0) {
                    sb.append("整体比上一轮更积极/友善——可以带安抚、鼓励、幽默，但必须贴合场景合理性，不要无脑乐观");
                } else if (stance < 0) {
                    sb.append("整体比上一轮更消极/谨慎/强硬——可以直接表达拒绝、不满、为难、担忧等负面态度，"
                            + "不要强行乐观、和稀泥或软化语气（这是用户表达权的组成部分）");
                } else {
                    sb.append("整体立场保持中性平衡");
                }
                sb.append("，且不要重复上一轮的表达方向。");
                user += sb.toString();
            } else if (stance > 0) {
                user += "\n注意：本批候选整体语气更积极/友善/乐观——可以带安抚、鼓励、幽默，"
                        + "但必须仍然贴合场景合理性，不要无脑乐观。";
            } else if (stance < 0) {
                user += "\n注意：本批候选整体立场更消极/谨慎/强硬——可以直接表达拒绝、不满、为难、担忧等负面态度，"
                        + "要诚实覆盖这些立场，不要强行乐观、和稀泥或软化语气。这是用户表达权的组成部分。";
            }

            JSONObject body = new JSONObject();
            body.put("model", "deepseek-chat");
            body.put("temperature", 0.9);
            body.put("max_tokens", 400);
            body.put("response_format", new JSONObject("{\"type\":\"json_object\"}"));
            JSONArray msgs = new JSONArray();
            msgs.put(msg("system", SYSTEM_PROMPT.replace("{k}", String.valueOf(k))));
            msgs.put(msg("user", user));
            body.put("messages", msgs);
            lastKind = "候选" + (batch > 0 ? "（第 " + (batch + 1) + " 批）" : "")
                    + (stance > 0 ? "（更积极）" : stance < 0 ? "（更消极）" : "");

            JSONObject resp = post(body.toString(), 30000);
            String content = resp.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");

            // 兼容被包裹的 ```json ... ``` 与剥离控制符
            content = content.replace("```json", "").replace("```", "").trim();
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) throw new Exception("响应不是 JSON");
            JSONArray arr = new JSONObject(content.substring(start, end + 1)).getJSONArray("candidates");

            List<String> out = new ArrayList<>();
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (int i = 0; i < arr.length() && out.size() < k; i++) {
                String t = arr.getJSONObject(i).optString("text", "").trim();
                if (t.isEmpty() || seen.containsKey(t)) continue;
                seen.put(t, Boolean.TRUE);
                out.add(t);
            }
            if (out.isEmpty()) throw new Exception("响应中没有有效候选");
            return out.toArray(new String[0]);
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            return fallback(k, batch);
        }
    }

    /** 离线兜底：网络或 API 异常时保证输入法始终可用。供外部在 provider 异常时调用。 */
    public static String[] fallbackPublic(int k, int batch) {
        return fallback(k, batch);
    }

    /** 离线兜底：网络或 API 异常时保证输入法始终可用。 */
    private static String[] fallback(int k, int batch) {
        List<String> pool = new ArrayList<>();
        for (String s : FALLBACK) pool.add(s);
        int offset = (batch * 3) % pool.size();
        List<String> rotated = new ArrayList<>(pool.subList(offset, pool.size()));
        rotated.addAll(pool.subList(0, offset));
        return rotated.subList(0, Math.min(k, rotated.size())).toArray(new String[0]);
    }

    private static JSONObject msg(String role, String content) throws Exception {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private JSONObject post(String json, int timeoutMs) throws Exception {
        lastTs = System.currentTimeMillis();
        lastUrl = apiUrl;
        lastRequestJson = json;
        lastResponseJson = null;
        lastFailure = null;
        try {
            JSONObject resp = doPost(json, timeoutMs);
            lastResponseJson = resp.toString();
            return resp;
        } catch (Exception e) {
            lastFailure = e.getMessage() == null ? e.toString() : e.getMessage();
            throw e;
        }
    }

    private JSONObject doPost(String json, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        OutputStream os = conn.getOutputStream();
        os.write(json.getBytes(StandardCharsets.UTF_8));
        os.close();

        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while (is != null && (n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
        if (is != null) is.close();
        String text = buf.toString("UTF-8");
        if (code >= 400) {
            String msg;
            try {
                msg = new JSONObject(text).getJSONObject("error").optString("message", text);
            } catch (Exception e) {
                msg = text.isEmpty() ? ("HTTP " + code) : text;
            }
            throw new Exception("HTTP " + code + ": " + msg);
        }
        return new JSONObject(text);
    }
}
