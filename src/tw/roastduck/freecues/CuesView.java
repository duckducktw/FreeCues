package tw.roastduck.freecues;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Choreographer;
import android.view.View;


/**
 * 覆蓋層：逐幀（Choreographer）向 MotionProcessor 取位移並繪製。
 * iOS 風：每顆點有固定的大小差異；靜止後逐顆、慢慢縮小淡出；動起來再長回來。
 */
final class CuesView extends View implements Choreographer.FrameCallback {
    static final int STYLE_GRID = 0, STYLE_BUBBLE = 1;

    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final MotionProcessor proc;

    int style = STYLE_GRID;
    float sizeScale = 1f;
    int inner = 0xFFFFFFFF, outer = 0xFF2C2C2C;
    boolean always = false;

    private double offX, offY;          // px，無上限累積
    private double clock;               // 秒，散點生命週期用
    private float presence = 0f;        // 0..1 整體可見度（已套曲線）
    private float raw = 0f;             // 0..1 等速前進的動畫進度
    private long lastMotionNs, lastFrameNs, showUntilNs;
    private boolean running;
    private int fc;
    private float dbgMax;
    private final float[] out = new float[2];

    // 版面
    private final float colGap, rowGap, baseR;
    // 散點
    private static final float BUBBLE_LIFE_S = 2.6f;   // 單顆生命週期（長大→停留→縮小）

    static final float MOVING_DP_S = 4f;     // 超過視為在動
    static final long HOLD_NS = 1_800_000_000L;

    CuesView(Context c, MotionProcessor proc) {
        super(c);
        this.proc = proc;
        dp = c.getResources().getDisplayMetrics().density;
        colGap = 40 * dp;
        rowGap = 92 * dp;
        baseR = 6.5f * dp;
    }

    private long lastTickMs;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!running) return;
            // 關屏時部分 ROM（Samsung）會丟掉 vsync callback 且亮屏後不再補發 → 超過 400ms 沒跑就重掛
            if (android.os.SystemClock.uptimeMillis() - lastTickMs > 400) kick();
            postDelayed(this, 500);
        }
    };

    void start() {
        if (running) return;
        running = true;
        kick();
        removeCallbacks(watchdog);
        postDelayed(watchdog, 500);
    }

    /** 重新掛上逐幀回呼（亮屏、視窗重新可見、watchdog 都會呼叫）。 */
    void kick() {
        if (!running) return;
        lastFrameNs = 0;                       // 避免把關屏期間算成一大段 dt
        proc.step(0.016f, out);                // 丟掉暫停期間累積的偏航，不一次套用
        lastTickMs = android.os.SystemClock.uptimeMillis();
        Choreographer ch = Choreographer.getInstance();
        ch.removeFrameCallback(this);
        ch.postFrameCallback(this);
        invalidate();
    }

    void stop() {
        running = false;
        removeCallbacks(watchdog);
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        if (v == VISIBLE) kick();
    }

    private Runnable onGone;
    private boolean dismissing;
    /** 結束：強制淡出（逐顆縮小），完成後回呼。 */
    void dismiss(Runnable done) {
        dismissing = true; onGone = done; showUntilNs = 0; lastMotionNs = 0;
        // 最多等 1.6 秒保底
        postDelayed(() -> { if (onGone != null) { Runnable r = onGone; onGone = null; r.run(); } }, 1600);
    }

    /** 短暫強制顯示（啟動、改設定時預覽）。 */
    void peek(long ms) { showUntilNs = System.nanoTime() + ms * 1_000_000L; }

    float presence() { return presence; }
    double offsetY() { return offY; }
    double offsetX() { return offX; }

    @Override
    public void doFrame(long now) {
        if (!running) return;
        lastTickMs = android.os.SystemClock.uptimeMillis();
        float dt = lastFrameNs == 0 ? 0.016f : (now - lastFrameNs) / 1e9f;
        lastFrameNs = now;
        proc.step(dt, out);
        clock += dt;
        offX += out[0] * dp;
        offY += out[1] * dp;
        if (BuildConfig.DEBUG_LOG) {
            float st = (float) Math.hypot(out[0], out[1]);
            if (st > dbgMax) dbgMax = st;
            if ((fc % 30) == 0) { android.util.Log.d("FreeCuesM", "maxStepDp=" + dbgMax + " " + proc.debug()); dbgMax = 0; }
        }
        if (proc.motion() > MOVING_DP_S) lastMotionNs = now;

        boolean show = !dismissing && (always || now - lastMotionNs < HOLD_NS || now < showUntilNs);
        float target = show ? 1f : 0f;
        // 等速推進 raw，再用 smootherstep 映射 → 起步與收尾都柔和
        float dur = target > raw ? 0.9f : (dismissing ? 1.0f : 1.6f);
        float before = presence;
        float stepv = Math.min(dt, 0.05f) / dur;
        raw = target > raw ? Math.min(target, raw + stepv) : Math.max(target, raw - stepv);
        presence = smoother(raw);
        if (dismissing && presence == 0f && onGone != null) { Runnable r = onGone; onGone = null; r.run(); return; }
        if (presence > 0f || before > 0f) invalidate();
        if (BuildConfig.DEBUG_LOG && (++fc % 12) == 0 && (presence > 0f || before > 0f)) android.util.Log.d("FreeCuesV", stats());
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** 由格點座標得到穩定的偽隨機數（0..1）。 */
    private static float hash(long a, long b, int salt) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }

    /** 單顆點的可見量：依各自相位錯開 → 逐顆消失。 */
    private float dotPresence(float phase) {
        return smoother(clamp01((raw - phase * 0.5f) / 0.5f));
    }

    private void dot(Canvas c, float x, float y, float r, float alpha) {
        int a = (int) (255 * alpha);
        if (r < 0.6f || a <= 2) return;
        paint.setColor(outer);
        paint.setAlpha(a);
        c.drawCircle(x, y, r, paint);
        paint.setColor(inner);
        paint.setAlpha(a);
        c.drawCircle(x, y, r * 0.82f, paint);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (presence <= 0f) return;
        if (style == STYLE_BUBBLE) drawBubbles(c); else drawGrid(c);
    }

    boolean statOn; int statN; float statMin, statMax, statA;
    String stats() { return "n=" + statN + " r=" + statMin + ".." + statMax + " avgA=" + (statN == 0 ? 0 : statA / statN) + " presence=" + presence; }
    private void drawGrid(Canvas c) {
        statOn = BuildConfig.DEBUG_LOG; statN = 0; statMin = 1e9f; statMax = 0; statA = 0;
        int w = getWidth(), h = getHeight();
        float band = Math.min(colGap * 2.3f, w * 0.2f);
        float r0 = Math.min(baseR, Math.min(w, h) / 70f) * sizeScale;
        for (int side = 0; side < 2; side++) {
            // 左側 x = k*colGap + offX；右側鏡像（w - …），兩側同向移動
            double base = side == 0 ? offX : -offX;
            long kMin = (long) Math.ceil((-r0 * 2 - base) / colGap);
            long kMax = (long) Math.floor((band - base) / colGap);
            for (long k = kMin; k <= kMax; k++) {
                float dist = (float) (k * colGap + base);
                float x = side == 0 ? dist : w - dist;
                float colA = clamp01((band - dist) / (colGap * 0.9f)) * clamp01((dist + r0 * 2) / (r0 * 3));
                if (colA <= 0) continue;
                double yBase = offY + ((k & 1) == 0 ? 0 : rowGap / 2);
                long jMin = (long) Math.floor((-rowGap - yBase) / rowGap);
                long jMax = (long) Math.ceil((h + rowGap - yBase) / rowGap);
                for (long j = jMin; j <= jMax; j++) {
                    float y = (float) (j * rowGap + yBase) + (hash(k * 2 + side, j, 9) - 0.5f) * 0.24f * rowGap;
                    float size = 0.7f + 0.5f * hash(k * 2 + side, j, 1);
                    float p = dotPresence(hash(k * 2 + side, j, 2));
                    if (p <= 0) continue;
                    float rr = r0 * size * grow(p);
                    if (statOn) { statN++; statMin = Math.min(statMin, rr); statMax = Math.max(statMax, rr); statA += colA * p; }
                    dot(c, x, y, rr, colA * p);
                }
            }
        }
    }

    /**
     * 散點：邊緣帶切成格子，每格一顆，位置在格內隨機偏移（±40%）。
     * 每顆有自己的生命週期與相位；一輪結束後換「世代」→ 新的隨機位置/大小重生。
     * 所有隨機都由 (格, 世代) hash 而來 → 隨車移動時穩定不閃。
     */
    private void drawBubbles(Canvas c) {
        statOn = BuildConfig.DEBUG_LOG; statN = 0; statMin = 1e9f; statMax = 0; statA = 0;
        int w = getWidth(), h = getHeight();
        float r0 = Math.min(baseR, Math.min(w, h) / 70f) * sizeScale;
        float band = Math.min(colGap * 2.6f, w * 0.22f);
        float cell = colGap * 1.1f;
        for (int side = 0; side < 2; side++) {
            double base = side == 0 ? offX : -offX;
            long kMin = (long) Math.floor((-cell - base) / cell);
            long kMax = (long) Math.ceil((band + cell - base) / cell);
            long jMin = (long) Math.floor((-cell - offY) / cell);
            long jMax = (long) Math.ceil((h + cell - offY) / cell);
            for (long k = kMin; k <= kMax; k++) {
                for (long j = jMin; j <= jMax; j++) {
                    long id = k * 2 + side;
                    float phase = hash(id, j, 11);                       // 錯開每顆的週期
                    double t = clock / BUBBLE_LIFE_S + phase;
                    long gen = (long) Math.floor(t);                     // 世代：每輪重生
                    float life = (float) (t - gen);                      // 0..1
                    if (hash(id, j * 31 + gen, 17) < 0.35f) continue;    // 部分格子空著 → 不規則
                    float jx = (hash(id, j * 31 + gen, 3) - 0.5f) * 0.8f * cell;
                    float jy = (hash(id, j * 31 + gen, 5) - 0.5f) * 0.8f * cell;
                    float dist = (float) ((k + 0.5) * cell + base) + jx;
                    float x = side == 0 ? dist : w - dist;
                    float y = (float) ((j + 0.5) * cell + offY) + jy;
                    float colA = clamp01((band - dist) / (cell * 0.9f)) * clamp01((dist + r0 * 2) / (r0 * 3));
                    if (colA <= 0) continue;
                    // 生命週期：0–30% 長大、30–70% 停留、70–100% 縮小淡出
                    float lifeA = life < 0.3f ? smoother(life / 0.3f) : life > 0.7f ? smoother((1f - life) / 0.3f) : 1f;
                    float size = 0.6f + 0.7f * hash(id, j * 31 + gen, 7);
                    float p = dotPresence(phase) * lifeA;
                    if (p <= 0) continue;
                    if (statOn) { statN++; statMin = Math.min(statMin, r0 * size * grow(p)); statMax = Math.max(statMax, r0 * size * grow(p)); statA += x; }
                    dot(c, x, y, r0 * size * grow(p), colA * Math.min(1f, p * 1.4f));
                }
            }
        }
    }

    private static double floorMod(double v, double m) { double r = v % m; return r < 0 ? r + m : r; }
    /** 0→1 帶 6% 過衝的成長曲線（p 已平滑）。 */
    private static float grow(float p) { return p * (1f + 0.06f * (float) Math.sin(Math.PI * p)); }
    private static float smoother(float t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static float ease(float t) { return 1f - (1f - t) * (1f - t); }
    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
