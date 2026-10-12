package com.dongni.ime;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 微信消息采集服务：无障碍事件触发 + 系统级截屏 + 本地中文 OCR。
 *
 * 背景：新版微信对非特权无障碍服务屏蔽了节点树（uiautomator dump 均为空），
 * 但像素未被屏蔽（普通截屏完整可读）。因此改用 takeScreenshot（无障碍授权一次，
 * 无需每次弹窗）+ ML Kit 离线中文 OCR，从像素里读聊天气泡，按气泡中心 x 坐标
 * 区分对方（左）/ 我（右）。只读屏幕，绝不模拟点击/输入。
 */
public class WeChatReaderService extends AccessibilityService {

    static final String WECHAT_PKG = "com.tencent.mm";
    /** 最近一次采集的上下文与时间戳（同进程共享）。 */
    public static volatile String lastContext = null;
    public static volatile long lastTime = 0;
    public static volatile int lastCount = 0;

    // ---- 诊断信息（设置页展示） ----
    public static volatile long lastAnyEventTs = 0;
    public static volatile String lastAnyPkg = "（无）";
    public static volatile long lastWechatTs = 0;
    public static volatile int collectCalls = 0;
    public static volatile String lastCollectPreview = "（尚未采集）";
    public static volatile String lastRootPkg = "（无）";

    private static final long FRESH_MS = 2 * 60 * 1000;   // 数据新鲜窗口 2 分钟
    private static final long COLLECT_INTERVAL_MS = 1500; // 截屏+OCR 较重，1.5s 节流

    private static WeChatReaderService instance; // 同进程共享，供输入法主动触发截屏

    /** 主动截屏+OCR 的完成回调（主线程）。 */
    public interface CaptureCallback { void onResult(boolean ok); }

    private volatile CaptureCallback pendingCb;

    /** 用户点按钮时调用：不依赖无障碍事件，立即截屏并 OCR 当前屏幕。 */
    public static void forceCapture(CaptureCallback cb) {
        WeChatReaderService s = instance;
        if (s == null || Build.VERSION.SDK_INT < 30) {
            cb.onResult(false);
            return;
        }
        s.pendingCb = cb;
        s.lastShotTs.set(System.currentTimeMillis()); // 绕过事件节流
        s.takeScreenshotCompat();
    }

    public static boolean hasFreshData() {
        return lastContext != null && System.currentTimeMillis() - lastTime < FRESH_MS;
    }

    private final ExecutorService ocrExec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextRecognizer recognizer;
    private final AtomicReference<Long> lastShotTs = new AtomicReference<>(0L);

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        lastAnyEventTs = System.currentTimeMillis();
        CharSequence pkg = event.getPackageName();
        String pkgName = pkg == null ? "?" : pkg.toString();
        lastAnyPkg = pkgName;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        if (!WECHAT_PKG.equals(pkgName)) return;
        lastWechatTs = lastAnyEventTs;
        lastRootPkg = "截屏模式";

        long now = System.currentTimeMillis();
        long prev = lastShotTs.get();
        if (now - prev < COLLECT_INTERVAL_MS) return;
        if (!lastShotTs.compareAndSet(prev, now)) return;

        if (Build.VERSION.SDK_INT >= 30) {
            takeScreenshotCompat();
        } else {
            lastCollectPreview = "（系统低于 Android 11，不支持无障碍截屏，请用剪贴板：长按「📋 对方」）";
        }
    }

    private void takeScreenshotCompat() {
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult screenshot) {
                Bitmap hw = Bitmap.wrapHardwareBuffer(
                        screenshot.getHardwareBuffer(), screenshot.getColorSpace());
                if (hw == null) {
                    lastCollectPreview = "（截屏转换失败）";
                    return;
                }
                // 拷为软件位图供 OCR 使用，并裁掉状态栏/标题栏(顶部)与输入法键盘(底部)
                Bitmap full = hw.copy(Bitmap.Config.ARGB_8888, false);
                screenshot.getHardwareBuffer().close();
                if (full == null) {
                    lastCollectPreview = "（截屏转换失败）";
                    return;
                }
                int h = full.getHeight(), w = full.getWidth();
                int top = Math.round(h * 0.095f);   // 状态栏+微信标题栏
                int bottom = Math.round(h * 0.575f); // 聊天内容区下缘（再往下是输入条和我们的键盘）
                Bitmap crop = Bitmap.createBitmap(full, 0, top, w, Math.max(1, bottom - top));
                runOcr(crop);
            }

            @Override public void onFailure(int errorCode) {
                lastCollectPreview = "（截屏失败 code=" + errorCode + "）";
                android.util.Log.d("TopKIME", "screenshot fail code=" + errorCode);
                firePending(false);
            }
        });
    }

    /** OCR 成功/失败后调用，通知等待中的主动触发（forceCapture）。 */
    private void firePending(boolean ok) {
        CaptureCallback cb = pendingCb;
        pendingCb = null;
        if (cb != null) main.post(() -> cb.onResult(ok && hasFreshData()));
    }

    private void runOcr(Bitmap crop) {
        recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener(ocrExec, (Text text) -> {
                    List<Bubble> bubbles = new ArrayList<>();
                    int halfW = crop.getWidth() / 2;
                    for (Text.TextBlock block : text.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            String t = line.getText().trim();
                            Rect r = line.getBoundingBox();
                            if (t.length() < 1 || r == null || r.width() <= 0) continue;
                            // 行中心 x 相对整屏：气泡文字几乎都在半屏一侧；横跨中线>70%的行多为系统条
                            int centerX = r.centerX();
                            boolean crosses = r.left < halfW * 0.3f && r.right > halfW * 1.7f;
                            if (crosses && t.length() <= 20) continue; // 时间条/居中提示
                            Bubble b = new Bubble();
                            b.text = t;
                            b.isOther = centerX < halfW;
                            b.top = r.top;
                            bubbles.add(b);
                        }
                    }
                    Collections.sort(bubbles, (a, b2) -> a.top - b2.top);
                    List<Bubble> last = bubbles.size() > 8
                            ? bubbles.subList(bubbles.size() - 8, bubbles.size()) : bubbles;

                    StringBuilder pv = new StringBuilder();
                    StringBuilder ctx = new StringBuilder("最近聊天记录（按时间顺序，OCR 自屏幕）：\n");
                    for (Bubble b : last) {
                        pv.append(b.isOther ? "[对方]" : "[我]").append(b.text).append("  ");
                        ctx.append(b.isOther ? "对方：" : "我：").append(b.text).append('\n');
                    }
                    lastCollectPreview = pv.length() == 0 ? "（OCR 没识别到文字）" : pv.toString();
                    collectCalls++;
                    android.util.Log.d("TopKIME", "ocr collect #" + collectCalls + " got "
                            + last.size() + " lines: " + lastCollectPreview);
                    if (!last.isEmpty()) {
                        lastContext = ctx.toString();
                        lastTime = System.currentTimeMillis();
                        lastCount = last.size();
                        // 并入本地聊天记录（去重）：用户向上滑动聊天页后再点一次按钮即可补更早的上文
                        long now = System.currentTimeMillis();
                        for (Bubble b : last) NotifyStore.mergeOcr(b.isOther ? "对方" : "我", b.text, now);
                    }
                    firePending(!last.isEmpty());
                })
                .addOnFailureListener(ocrExec, (Exception e) -> {
                    lastCollectPreview = "（OCR 失败：" + e.getMessage() + "）";
                    android.util.Log.d("TopKIME", "ocr fail: " + e);
                    firePending(false);
                });
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        ocrExec.shutdownNow();
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }

    private static class Bubble {
        String text;
        boolean isOther; // true=对方（气泡在屏幕左侧）
        int top;
    }
}
