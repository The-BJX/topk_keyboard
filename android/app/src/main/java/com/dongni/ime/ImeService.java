package com.dongni.ime;

import android.content.ClipData;
import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 懂你意思输入法 · Android 输入法服务。
 *
 * 交互与网页 demo 一致：点候选大按钮把短语拼进句子，随时撤销/换一批，
 * 没有合适的词可切普通打字（并入后继续智能预测），组好后“发送”上屏。
 * 首版上下文来源：📋 对方按钮读取剪贴板里复制的对方消息。
 */
public class ImeService extends InputMethodService {

    static final String PREFS = "cfg";
    private static final int K = 6;

    private InputSession session;
    private DeepSeekClient client;
    private DiskCache cache;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation = 0;          // 请求代数：新动作使过期的网络回调作废

    private View root;
    private TextView draftBar, statusBar;
    private GridLayout grid;
    private LinearLayout actionRow, kbRow;
    private EditText kbInput;
    private Button undoBtn, clipBtn, moreBtn, sendBtn;

    @Override
    public void onCreate() {
        super.onCreate();
        NotifyStore.init(this);
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        client = new DeepSeekClient(prefs.getString("api_url", DeepSeekClient.DEFAULT_URL),
                prefs.getString("api_key", ""));
        File cacheFile = new File(getFilesDir(), "topk_cache.json");
        cache = new DiskCache(cacheFile);
        session = new InputSession((ctx, draft, k, batch, stance, prev) -> {
            String[] hit = cache.get(ctx, draft, k, batch, stance, prev);
            if (hit != null) {
                // 缓存命中也写入调试窗口：说明这次没有发网络请求、用的上下文是什么
                DeepSeekClient.lastKind = "候选（缓存命中）";
                DeepSeekClient.lastTs = System.currentTimeMillis();
                DeepSeekClient.lastUrl = client.apiUrlForDebug();
                DeepSeekClient.lastRequestJson = "（本次命中本地缓存，未发起网络请求）\n"
                        + "【场景】" + ctx + "\n【已输入】" + draft
                        + "\n（k=" + k + ", batch=" + batch + ", stance=" + stance + "）";
                DeepSeekClient.lastResponseJson = null;
                DeepSeekClient.lastFailure = null;
                android.util.Log.d("TopKIME", "cache hit: ctx=" + shortLog(ctx) + " draft=" + draft);
                return hit;
            }
            android.util.Log.d("TopKIME", "request: ctx=" + shortLog(ctx) + " draft=" + draft);
            String[] result = client.getCandidates(ctx, draft, k, batch, stance, prev);
            cache.put(ctx, draft, k, batch, stance, prev, result);
            return result;
        });
    }

    @Override
    public View onCreateInputView() {
        root = getLayoutInflater().inflate(R.layout.ime_view, null);
        draftBar = root.findViewById(R.id.draft_bar);
        statusBar = root.findViewById(R.id.status_bar);
        grid = root.findViewById(R.id.candidates_grid);
        actionRow = root.findViewById(R.id.action_row);
        kbRow = root.findViewById(R.id.kb_row);
        kbInput = root.findViewById(R.id.kb_input);
        undoBtn = root.findViewById(R.id.btn_undo);
        clipBtn = root.findViewById(R.id.btn_clip);
        moreBtn = root.findViewById(R.id.btn_more);
        sendBtn = root.findViewById(R.id.btn_send);

        undoBtn.setOnClickListener(v -> actUndo());
        clipBtn.setOnClickListener(v -> actClipboard());
        clipBtn.setOnLongClickListener(v -> {
            actClipboardManual();
            return true;
        });
        moreBtn.setOnClickListener(v -> actMore());
        root.findViewById(R.id.btn_tone_pos).setOnClickListener(v -> actTone(1));
        root.findViewById(R.id.btn_tone_neg).setOnClickListener(v -> actTone(-1));
        sendBtn.setOnClickListener(v -> actSend());
        root.findViewById(R.id.btn_kb_back).setOnClickListener(v -> setKbMode(false));
        root.findViewById(R.id.btn_kb_commit).setOnClickListener(v -> actKbCommit());
        render();
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (session.cachedCandidates == null && session.draft.isEmpty()) {
            fetch(); // 首屏自动预取
        } else {
            render();
        }
    }

    // ---- 动作 ----
    private void actSelect(String text) {
        session.select(text);
        fetch();
    }

    private void actUndo() {
        if (session.undo()) fetch();
    }

    private void actMore() {
        session.more();
        fetch();
    }

    /** 情绪/立场调制：+1 更积极，-1 更消极（相对上一轮调整立场倾向值）。 */
    private void actTone(int dir) {
        session.moreTone(dir);
        int s = session.stance;
        toast("立场倾向 " + (s > 0 ? "+" + s : s) + "：已相对上一轮更" + (dir > 0 ? "积极" : "消极"));
        fetch();
    }

    private void actSend() {
        if (session.draft.isEmpty()) return;
        String msg = session.commit();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && !msg.isEmpty()) {
            ic.commitText(msg, 1);
            NotifyStore.add("我", msg, System.currentTimeMillis()); // 发送记录并入本地聊天流
        }
        fetch();
    }

    /**
     * 注入聊天上下文。点按优先级：
     * ① 本地聊天记录（通知监听自动捕获 + 输入法发送记录 + OCR 并入，向上滑动后再点可补更早上文）
     * ② 记录为空时强制截屏 OCR 一次并注入
     * 长按：读剪贴板（手动路径）。
     */
    private void actClipboard() {
        String ctx = NotifyStore.buildContext(10);
        if (ctx != null) {
            android.util.Log.d("TopKIME", "clip: use notify-store ctx=" + shortLog(ctx));
            session.setContext("你在微信聊天中，根据【最近聊天记录】回复。\n" + ctx);
            toast("已用本地记录 " + NotifyStore.count() + " 条中的最近消息生成候选");
            fetch();
            return;
        }
        toast("本地还没有聊天记录，正在截屏识别微信消息…");
        WeChatReaderService.forceCapture(ok -> {
            String c2 = NotifyStore.buildContext(10);
            if ((ok || c2 != null) && c2 != null) {
                session.setContext("你在微信聊天中，根据【最近聊天记录】回复。\n" + c2);
                toast("已从微信屏幕识别 " + WeChatReaderService.lastCount + " 行消息");
                fetch();
            } else {
                toast("未读到微信消息：" + WeChatReaderService.lastCollectPreview
                        + "\n请检查通知使用权/无障碍授权（设置页可查）；或长按本按钮改用剪贴板");
            }
        });
    }

    /** 长按：手动路径——读取剪贴板里复制的对方消息。 */
    private void actClipboardManual() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
        if (TextUtils.isEmpty(text)) {
            toast("剪贴板是空的：请先在微信里长按复制对方的消息");
            return;
        }
        session.setContext("你在微信聊天中，根据【对方消息】回复。\n对方消息：" + text);
        toast("已读取剪贴板消息");
        fetch();
    }

    private void setKbMode(boolean on) {
        kbRow.setVisibility(on ? View.VISIBLE : View.GONE);
        grid.setVisibility(on ? View.GONE : View.VISIBLE);
        actionRow.setVisibility(on ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.tone_row).setVisibility(on ? View.GONE : View.VISIBLE); // 自由打字时不需要语气调制
        if (on) kbInput.requestFocus();
        else render();
    }

    private void actKbCommit() {
        String t = kbInput.getText().toString();
        kbInput.setText("");
        if (t.trim().isEmpty()) {
            setKbMode(false);
            return;
        }
        session.typeText(t);
        setKbMode(false);
        fetch();
    }

    // ---- 候选获取 ----
    private void fetch() {
        final int gen = ++generation;
        final String ctx = session.context;
        final String draft = session.draft;
        final int k = session.k;
        final int batch = session.batch;
        final int stance = session.stance;
        final java.util.List<String> prev = session.prevCandidates;
        renderPending();

        exec.submit(() -> {
            String[] result;
            try {
                result = session.provider.get(ctx, draft, k, batch, stance, prev);
            } catch (Exception e) {
                result = client.fallbackPublic(k, batch); // 双保险：provider 异常也走兜底
            }
            final String[] finalResult = result;
            main.post(() -> {
                if (gen != generation) return;          // 状态已变化，丢弃过期结果
                session.cachedCandidates = java.util.Arrays.asList(finalResult);
                render();
            });
        });
    }

    // ---- 渲染 ----
    private void renderPending() {
        draftBar.setText(session.draft.isEmpty() ? "∅" : session.draft);
        sendBtn.setEnabled(!session.draft.isEmpty());
        sendBtn.setAlpha(session.draft.isEmpty() ? 0.5f : 1f);
        statusBar.setVisibility(View.VISIBLE);
        statusBar.setText(session.context.isEmpty()
                ? "正在生成候选…（提示：先在微信复制对方消息，点 📋 对方）"
                : "正在生成候选…");
        grid.removeAllViews();
    }

    private void render() {
        draftBar.setText(session.draft.isEmpty() ? "∅" : session.draft);
        sendBtn.setEnabled(!session.draft.isEmpty());
        sendBtn.setAlpha(session.draft.isEmpty() ? 0.5f : 1f);

        java.util.List<String> cands = session.cachedCandidates;
        if (cands == null) {
            fetch();
            return;
        }
        statusBar.setVisibility(client.lastError != null ? View.VISIBLE : View.GONE);
        if (client.lastError != null) {
            statusBar.setText("⚠ AI 候选暂不可用，已切换离线兜底：" + client.lastError);
            client.lastError = null;
        }

        grid.removeAllViews();
        for (int i = 0; i < cands.size(); i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(cands.get(i));
            b.setAllCaps(false);
            b.setTextSize(16);
            b.setMaxLines(2);
            b.setGravity(Gravity.CENTER);
            b.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);
            b.setBackgroundColor(0xFFFFFFFF);
            b.setPadding(8, 6, 8, 6);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.setMargins(4, 4, 4, 4);
            b.setMinimumHeight(dp(56));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> actSelect(cands.get(idx)));
            grid.addView(b);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String shortLog(String s) {
        if (s == null) return "null";
        String flat = s.replace('\n', '␤');
        return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }
}
