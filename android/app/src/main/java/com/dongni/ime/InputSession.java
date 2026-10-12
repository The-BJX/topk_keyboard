package com.dongni.ime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** 输入会话状态机：场景上下文、当前前缀、撤销栈。与 UI 解耦（移植自 Python 版 topk.session）。 */
public class InputSession {

    public final Provider provider;
    public String context = "";   // 场景/对话上文
    public int k = 6;             // 每批候选数量（大按钮界面）
    public String draft = "";     // 当前已确定的输入前缀
    int batch = 0;                // 换批计数，参与缓存键
    int stance = 0;               // 立场倾向值：0 中性，正积极负消极（相对上一轮调制，限 ±5）

    private final Deque<String> undoStack = new ArrayDeque<>();
    List<String> cachedCandidates; // 最近一次结果，null 表示需要刷新
    List<String> prevCandidates;   // 上一轮候选，tone/换批时作为相对调制基准进入 prompt

    public interface Provider {
        /** 阻塞调用，返回候选短语数组。异常由调用方处理。 */
        String[] get(String context, String draft, int k, int batch, int stance, List<String> prev) throws Exception;
    }

    public InputSession(Provider provider) {
        this.provider = provider;
    }

    public void reset() {
        context = "";
        draft = "";
        batch = 0;
        undoStack.clear();
        resetTone();
        cachedCandidates = null;
    }

    public void invalidate() {
        cachedCandidates = null;
    }

    /** 点选/撤销/发送/打字/换场景后，立场与上一轮基准一并归零。 */
    private void resetTone() {
        stance = 0;
        prevCandidates = null;
    }

    /** 换批前快照当前候选，作为下一轮 prompt 的「上一轮候选」。 */
    private void snapshotPrev() {
        prevCandidates = cachedCandidates == null ? null : new java.util.ArrayList<>(cachedCandidates);
    }

    // ---- 用户动作 ----
    public void select(String text) {
        undoStack.push(draft);
        draft += text;
        batch = 0;
        resetTone();
        invalidate();
    }

    /** 换一批：保持当前立场倾向，相对上一轮给出不同表达方向。 */
    public void more() {
        snapshotPrev();
        batch++;
        invalidate();
    }

    /**
     * 情绪/立场调制换批（相对上一轮）：dir=+1 立场倾向 +1，dir=-1 则 -1（限 ±5）。
     * 立场倾向值与上一轮候选一并进入 prompt；缓存键自动区分；
     * 点选/撤销/发送/打字后立场归零。
     */
    public void moreTone(int dir) {
        snapshotPrev();
        stance = Math.max(-5, Math.min(5, stance + (dir > 0 ? 1 : -1)));
        batch++;
        invalidate();
    }

    public void typeText(String text) {
        text = text.trim();
        if (text.isEmpty()) return;
        undoStack.push(draft);
        draft += text;
        batch = 0;
        resetTone();
        invalidate();
    }

    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        draft = undoStack.pop();
        batch = 0;
        resetTone();
        invalidate();
        return true;
    }

    /** 发送当前前缀：返回要上屏的消息并把其并入场景作为下一轮上文。 */
    public String commit() {
        String msg = draft;
        draft = "";
        undoStack.clear();
        batch = 0;
        resetTone();
        invalidate();
        if (!msg.isEmpty()) {
            context = context.isEmpty() ? "我：" + msg : context + "\n我：" + msg;
        }
        return msg;
    }

    public void setContext(String text) {
        context = text == null ? "" : text.trim();
        batch = 0;
        resetTone();
        invalidate();
    }
}
