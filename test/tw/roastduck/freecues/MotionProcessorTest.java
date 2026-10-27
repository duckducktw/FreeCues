package tw.roastduck.freecues;

/** JVM 單元測試：引擎行為。 */
public final class MotionProcessorTest {
    static int fails = 0;
    static void check(String n, boolean ok, String info) {
        System.out.println((ok ? "PASS " : "FAIL ") + n + "  " + info);
        if (!ok) fails++;
    }

    /** 跑 secs 秒：100Hz 感測器 + 60fps step，回傳累積 (dx,dy) dp。 */
    static float[] run(MotionProcessor p, float[] r9, float ax, float ay, float az, float secs) {
        float[] acc = new float[2], o = new float[2];
        int frames = (int) (secs * 60);
        for (int f = 0; f < frames; f++) {
            if (r9 != null) p.onRotation(r9);
            for (int k = 0; k < 2; k++) p.onLinear(ax, ay, az, 0.0083f);
            p.step(1 / 60f, o);
            acc[0] += o[0]; acc[1] += o[1];
        }
        return acc;
    }

    /** 繞裝置 x 軸傾斜 deg 度的旋轉矩陣（0 = 平放；90 = 直立面向使用者）。 */
    static float[] tilt(double deg) {
        double t = Math.toRadians(deg), c = Math.cos(t), s = Math.sin(t);
        return new float[]{1, 0, 0, 0, (float) c, (float) -s, 0, (float) s, (float) c};
    }

    static String f(float[] v) { return String.format("(%.1f, %.1f)", v[0], v[1]); }

    public static void main(String[] a) {
        // 1. 平放：前進加速（世界=車頭，裝置 y）→ 點往下
        MotionProcessor p = new MotionProcessor();
        float[] r = run(p, tilt(0), 0, 1.0f, 0, 1f);
        check("flat forward accel → dots move down", r[1] > 30 && Math.abs(r[0]) < 1, f(r));

        // 2. 斜拿 60°：同樣 1 m/s² 的「車頭方向」加速在裝置座標分散到 y/z
        p = new MotionProcessor();
        double t = Math.toRadians(60);
        // 世界前進 (0,1,0) 轉回裝置座標 = R^T * (0,1,0)
        float[] R = tilt(60);
        float dx = R[3], dy = R[4], dz = R[5];
        float[] r2 = run(p, R, dx * 1f, dy * 1f, dz * 1f, 1f);
        check("tilted 60° forward still strong (≥80% of flat)", r2[1] > r[1] * 0.8f, f(r2) + " vs flat " + f(r));

        // 3. 煞車 → 點往上
        p = new MotionProcessor();
        float[] r3 = run(p, tilt(0), 0, -1.0f, 0, 1f);
        check("braking → dots move up", r3[1] < -30, f(r3));

        // 4. 小加速 0.15 m/s²（舊版門檻以下）仍會動
        p = new MotionProcessor();
        float[] r4 = run(p, tilt(0), 0, 0.15f, 0, 2f);
        check("small accel 0.15 still moves", r4[1] > 5, f(r4));

        // 5. 雜訊底以下完全不動
        p = new MotionProcessor();
        float[] r5 = run(p, tilt(0), 0.03f, -0.04f, 0.02f, 3f);
        check("noise below floor silent", Math.abs(r5[0]) < 0.01 && Math.abs(r5[1]) < 0.01, f(r5));

        // 6. 停止加速後速度衰減（不會無限滑）
        p = new MotionProcessor();
        run(p, tilt(0), 0, 1f, 0, 1f);
        float[] r6 = run(p, tilt(0), 0, 0, 0, 4f);
        float[] r6b = run(p, tilt(0), 0, 0, 0, 1f);
        check("decays after accel stops", Math.abs(r6b[1]) < 1f, "tail " + f(r6b));

        // 7. 左轉（heading 減少）→ 點往右
        p = new MotionProcessor();
        float[] o = new float[2], acc = new float[2];
        for (int i = 0; i <= 60; i++) {
            double h = -Math.toRadians(i * 0.5);  // 每幀左轉 0.5°
            float c = (float) Math.cos(h), s = (float) Math.sin(h);
            // 繞 z 旋轉：裝置 y 軸指向 heading
            p.onRotation(new float[]{c, s, 0, -s, c, 0, 0, 0, 1});
            p.step(1 / 60f, o);
            acc[0] += o[0]; acc[1] += o[1];
        }
        check("left turn → dots move right", acc[0] > 20, f(acc));

        // 8. 橫放 rotation=1：前進仍是螢幕往下
        p = new MotionProcessor();
        p.rotation = 1;
        float[] r8 = run(p, tilt(0), -1f, 0, 0, 1f);  // 螢幕上方 = 裝置 -x
        check("landscape forward → down", r8[1] > 30 && Math.abs(r8[0]) < 1, f(r8));

        // 9. 低靈敏度較小
        p = new MotionProcessor(); p.sensitivity = 1.8f;
        float[] r9 = run(p, tilt(0), 0, 1f, 0, 1f);
        check("low sensitivity smaller", r9[1] < r[1] && r9[1] > 0, f(r9));

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
