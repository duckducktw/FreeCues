package tw.roastduck.freecues;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 主畫面：
 * 白底、正中 180dp 深灰 #292929 圓形播放鍵；右上齒輪 → 設定面板以 0.5→1 縮放展開（300ms），
 * 齒輪旋轉 90° 變成 ×。設定項：Sensitivity / Visibility / Color / Style / Size，
 * 32sp 標題 + 18sp 細體分頁選項（底線指示）。
 */
public final class MainActivity extends Activity {
    static final int INK = 0xFF292929;
    private SharedPreferences p;
    private FrameLayout root;
    private Glyph startBtn, gear;
    private LinearLayout panel;
    private boolean panelOpen;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Prefs.get(this);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        setContentView(build());
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); }

    @Override
    protected void onResume() {
        super.onResume();
        root.postDelayed(this::syncButton, 250);
    }

    @Override
    public void onBackPressed() {
        if (panelOpen) togglePanel(); else super.onBackPressed();
    }

    // ───────────────────────── UI ─────────────────────────

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        // edge-to-edge 下手動避開狀態列/導覽列/瀏海，按鈕不會被遮
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top;
            int bot = in.getInsets(WindowInsets.Type.systemBars()).bottom;
            v.setPadding(0, top, 0, bot);
            return in;
        });

        startBtn = new Glyph(Glyph.PLAY, Color.WHITE);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(INK);
        startBtn.setBackground(circle);
        startBtn.setElevation(dp(6));
        startBtn.setOnClickListener(v -> onStartClick());
        root.addView(startBtn, new FrameLayout.LayoutParams(dp(180), dp(180), Gravity.CENTER));

        TextView hint = new TextView(this);
        hint.setText("Tap to start · 點一下開始");
        hint.setTextColor(0x99292929);
        hint.setTextSize(14);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        hl.topMargin = dp(140);
        root.addView(hint, hl);
        hintView = hint;

        panel = buildPanel();
        panel.setVisibility(View.INVISIBLE);
        panel.setElevation(dp(12));
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("FreeCues");
        title.setTextSize(18);
        title.setTextColor(INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP | Gravity.START);
        tl.leftMargin = dp(16);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setElevation(dp(16));
        root.addView(title, tl);

        gear = new Glyph(Glyph.GEAR, INK);
        gear.setOnClickListener(v -> togglePanel());
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        gl.rightMargin = dp(8);
        gear.setElevation(dp(16));   // 必須高於 panel(12dp)，否則開面板後叉叉被蓋住
        root.addView(gear, gl);
        return root;
    }

    private TextView hintView;

    private LinearLayout buildPanel() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(Color.WHITE);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(26), dp(56), dp(26), dp(24));
        l.setClickable(true);
        section(l, "Sensitivity", Prefs.SENS, 1, "Low", "Standard");
        section(l, "Visibility", Prefs.VIS, 1, "Always", "Only in Vehicle");
        section(l, "Color", Prefs.COLOR_MODE, 0, "White", "Black", "Pick");
        section(l, "Style", Prefs.STYLE, 0, "Grid", "Bubble");
        section(l, "Size", Prefs.SIZE, 1, "Small", "Standard", "Large");
        return l;
    }

    private void section(LinearLayout parent, String title, String key, int def, String... opts) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(32);
        t.setTextColor(INK);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setPadding(dp(10), dp(14), 0, dp(2));
        parent.addView(t);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] tabs = new TextView[opts.length];
        Runnable paint = () -> {
            int cur = p.getInt(key, def);
            for (int i = 0; i < tabs.length; i++) {
                boolean on = i == cur;
                tabs[i].setTextColor(on ? INK : 0x80292929);
                tabs[i].setTypeface(Typeface.create(on ? "sans-serif" : "sans-serif-light", Typeface.NORMAL));
                ((Underline) tabs[i].getBackground()).on = on;
                tabs[i].getBackground().invalidateSelf();
            }
        };
        for (int i = 0; i < opts.length; i++) {
            TextView tab = new TextView(this);
            tab.setText(opts[i]);
            tab.setTextSize(18);
            tab.setPadding(dp(10), dp(4), dp(10), dp(8));
            tab.setBackground(new Underline(dp(2.5f), dp(10)));
            final int idx = i;
            tab.setOnClickListener(v -> {
                if (key.equals(Prefs.COLOR_MODE) && idx == 2) pickColor(paint);
                else { p.edit().putInt(key, idx).apply(); paint.run(); }
            });
            tabs[i] = tab;
            row.addView(tab, new LinearLayout.LayoutParams(-2, dp(40)));
        }
        paint.run();
        parent.addView(row);
    }

    private void pickColor(Runnable repaint) {
        int[] colors = {0xFFF44336, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF00BCD4, 0xFF2196F3,
                0xFF3F51B5, 0xFF9C27B0, 0xFFE91E63, 0xFF795548, 0xFF9E9E9E, 0xFF607D8B};
        GridLayout g = new GridLayout(this);
        g.setColumnCount(4);
        g.setPadding(dp(20), dp(20), dp(20), dp(8));
        AlertDialog[] d = new AlertDialog[1];
        for (int c : colors) {
            View sw = new View(this);
            GradientDrawable s = new GradientDrawable();
            s.setShape(GradientDrawable.OVAL);
            s.setColor(c);
            sw.setBackground(s);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = lp.height = dp(52);
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            sw.setOnClickListener(v -> {
                p.edit().putInt(Prefs.COLOR, c).putInt(Prefs.COLOR_MODE, 2).apply();
                repaint.run();
                d[0].dismiss();
            });
            g.addView(sw, lp);
        }
        d[0] = new AlertDialog.Builder(this).setView(g).create();
        d[0].show();
    }

    private void togglePanel() {
        panelOpen = !panelOpen;
        android.view.animation.PathInterpolator ease = new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
        gear.animate().rotation(panelOpen ? 90 : 0).setDuration(420).setInterpolator(ease).start();
        gear.setKind(panelOpen ? Glyph.CLOSE : Glyph.GEAR);
        panel.animate().cancel();
        startBtn.animate().cancel();
        if (panelOpen) {
            panel.setVisibility(View.VISIBLE);
            panel.setAlpha(0f); panel.setScaleX(0.92f); panel.setScaleY(0.92f); panel.setTranslationY(dp(24));
            panel.animate().alpha(1).scaleX(1).scaleY(1).translationY(0).setDuration(420).setInterpolator(ease).start();
            // 按鈕有 elevation，會疊在面板上攔截觸控 → 動畫結束一定要 GONE
            startBtn.animate().alpha(0).scaleX(0.85f).scaleY(0.85f).setDuration(260).setInterpolator(ease)
                    .withEndAction(() -> { if (panelOpen) startBtn.setVisibility(View.GONE); }).start();
            hintView.animate().alpha(0).setDuration(200).start();
        } else {
            panel.animate().alpha(0).scaleX(0.96f).scaleY(0.96f).translationY(dp(16)).setDuration(320).setInterpolator(ease)
                    .withEndAction(() -> { if (!panelOpen) panel.setVisibility(View.INVISIBLE); }).start();
            startBtn.setVisibility(View.VISIBLE);
            startBtn.setAlpha(0f); startBtn.setScaleX(0.85f); startBtn.setScaleY(0.85f);
            startBtn.animate().alpha(1).scaleX(1).scaleY(1).setStartDelay(80).setDuration(420).setInterpolator(ease)
                    .withEndAction(() -> startBtn.animate().setStartDelay(0)).start();
            hintView.animate().alpha(1).setStartDelay(120).setDuration(360).start();
        }
    }

    private void onStartClick() {
        if (CuesService.running) {
            CuesService.stop(this);
        } else if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        } else {
            CuesService.start(this);
        }
        // 按下：先柔和縮小再彈性回彈（spring 風格），圖示交叉淡換
        android.view.animation.PathInterpolator in = new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);
        startBtn.animate().cancel();
        startBtn.animate().scaleX(0.9f).scaleY(0.9f).setDuration(140).setInterpolator(in).withEndAction(() ->
                startBtn.animate().scaleX(1f).scaleY(1f).setDuration(520)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start()).start();
        root.postDelayed(this::syncButton, 300);
    }

    private void syncButton() {
        boolean on = CuesService.running;
        startBtn.setKind(on ? Glyph.PAUSE : Glyph.PLAY);
        hintView.setText(on ? "Running · 運作中" : "Tap to start · 點一下開始");
    }

    // ───────────────────────── adb 測試入口 ─────────────────────────

    private void handle(Intent i) {
        String cmd = i == null ? null : i.getStringExtra("cmd");
        if (cmd == null) return;
        if (i.hasExtra("vis")) p.edit().putInt(Prefs.VIS, i.getIntExtra("vis", 1)).commit();
        if (i.hasExtra("style")) p.edit().putInt(Prefs.STYLE, i.getIntExtra("style", 0)).commit();
        switch (cmd) {
            case "start": if (Settings.canDrawOverlays(this)) CuesService.start(this); break;
            case "stop": CuesService.stop(this); break;
            case "inject": {
                CuesService s = CuesService.instance;
                if (BuildConfig.DEBUG_LOG && s != null)
                    s.inject(i.getFloatExtra("ax", 0), i.getFloatExtra("ay", 0), i.getIntExtra("ms", 500));
                break;
            }
            case "probe": {
                CuesService s = CuesService.instance;
                if (s != null && s.view() != null) android.util.Log.d("FreeCues",
                        "probe offX=" + s.view().offsetX() + " offY=" + s.view().offsetY() + " presence=" + s.view().presence());
                if (s != null && i.getBooleanExtra("end", false)) s.endInject();
                break;
            }
        }
        if (i.getBooleanExtra("bg", false)) finish();
        else root.postDelayed(this::syncButton, 300);
    }

    // ───────────────────────── 自繪元件 ─────────────────────────

    /** 分頁底線指示。 */
    static final class Underline extends android.graphics.drawable.Drawable {
        final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float h, inset;
        boolean on;
        Underline(float h, float inset) { this.h = h; this.inset = inset; pt.setColor(INK); }
        @Override public void draw(Canvas c) {
            if (!on) return;
            android.graphics.Rect b = getBounds();
            c.drawRoundRect(b.left + inset, b.bottom - h, b.right - inset, b.bottom, h, h, pt);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** 播放/暫停/齒輪/關閉圖示（自繪向量）。 */
    final class Glyph extends View {
        static final int PLAY = 0, PAUSE = 1, GEAR = 2, CLOSE = 3;
        private int kind;
        private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(int kind, int color) {
            super(MainActivity.this);
            this.kind = kind;
            pt.setColor(color);
            setClickable(true);
        }

        void setKind(int k) { kind = k; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h), cx = w / 2, cy = h / 2;
            pt.setStyle(Paint.Style.FILL);
            switch (kind) {
                case PLAY: {
                    Path tri = new Path();
                    float r = s * 0.2f;
                    tri.moveTo(cx - r * 0.75f, cy - r);
                    tri.lineTo(cx + r * 1.05f, cy);
                    tri.lineTo(cx - r * 0.75f, cy + r);
                    tri.close();
                    pt.setStrokeJoin(Paint.Join.ROUND);
                    pt.setStyle(Paint.Style.FILL_AND_STROKE);
                    pt.setStrokeWidth(s * 0.05f);
                    c.drawPath(tri, pt);
                    break;
                }
                case PAUSE: {
                    pt.setStyle(Paint.Style.STROKE);
                    pt.setStrokeCap(Paint.Cap.ROUND);
                    pt.setStrokeWidth(s * 0.09f);
                    c.drawLine(cx - s * 0.11f, cy - s * 0.17f, cx - s * 0.11f, cy + s * 0.17f, pt);
                    c.drawLine(cx + s * 0.11f, cy - s * 0.17f, cx + s * 0.11f, cy + s * 0.17f, pt);
                    break;
                }
                case CLOSE: {
                    pt.setStyle(Paint.Style.STROKE);
                    pt.setStrokeCap(Paint.Cap.ROUND);
                    pt.setStrokeWidth(s * 0.07f);
                    float r = s * 0.18f;
                    c.drawLine(cx - r, cy - r, cx + r, cy + r, pt);
                    c.drawLine(cx - r, cy + r, cx + r, cy - r, pt);
                    break;
                }
                default: {
                    float ro = s * 0.27f, ri = s * 0.2f;
                    Path g = new Path();
                    for (int i = 0; i < 16; i++) {
                        double a0 = Math.PI * 2 * i / 16;
                        float r = (i % 2 == 0) ? ro : ri;
                        float x = cx + (float) Math.cos(a0) * r, y = cy + (float) Math.sin(a0) * r;
                        if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
                        double a1 = Math.PI * 2 * (i + 1) / 16;
                        g.lineTo(cx + (float) Math.cos(a1 - 0.0001) * r, cy + (float) Math.sin(a1 - 0.0001) * r);
                    }
                    g.close();
                    g.addCircle(cx, cy, s * 0.09f, Path.Direction.CCW);
                    g.setFillType(Path.FillType.EVEN_ODD);
                    pt.setStrokeJoin(Paint.Join.ROUND);
                    pt.setStyle(Paint.Style.FILL_AND_STROKE);
                    pt.setStrokeWidth(s * 0.03f);
                    c.drawPath(g, pt);
                }
            }
        }
    }
}
