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

    /** 平放；陀螺儀 (0, wPitch, wz) rad/s、側向加速度 latAcc（裝置 x）、前向 fwdAcc（裝置 y），100Hz。 */
    static float[] turn(MotionProcessor p, float wz, float latAcc, float wPitch, float fwdAcc, float secs) {
        float[] acc = new float[2], o = new float[2];
        int frames = (int) (secs * 60);
        for (int fr = 0; fr < frames; fr++) {
            p.onRotation(tilt(0));
            for (int k = 0; k < 2; k++) {
                p.onGyro(wPitch, 0, wz, 0.0083f);
                p.onLinear(latAcc, fwdAcc, 0, 0.0083f);
            }
            p.step(1 / 60f, o);
            acc[0] += o[0]; acc[1] += o[1];
        }
        return acc;
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

        // 7. 車左轉（陀螺儀 ωz>0 + 向心加速度向左，v≈10 m/s）→ 點往右
        float[] acc = turn(new MotionProcessor(), 0.3f, -3.0f, 0, 0, 2f);
        check("car left turn (gyro+centripetal) → dots move right", acc[0] > 40, f(acc));

        // 7b. 車右轉 → 點往左
        acc = turn(new MotionProcessor(), -0.3f, 3.0f, 0, 0, 2f);
        check("car right turn → dots move left", acc[0] < -40, f(acc));

        // 7c. 手在原地水平轉手機（ωz 有、向心加速度≈0）→ 不動
        acc = turn(new MotionProcessor(), 0.6f, 0, 0, 0, 2f);
        check("hand yaw spin, no centripetal → still", Math.abs(acc[0]) < 3 && Math.abs(acc[1]) < 3, f(acc));

        // 7d. 手翻手機（俯仰 1.5 rad/s）+ 重力洩漏造成的假加速度 2 m/s² → 不動
        acc = turn(new MotionProcessor(), 0, 0, 1.5f, 2.0f, 1f);
        check("hand pitch flip + leaked accel → still", Math.abs(acc[0]) < 3 && Math.abs(acc[1]) < 3, f(acc));

        // 7e. 翻完後 1 秒內恢復正常反應
        MotionProcessor q = new MotionProcessor();
        turn(q, 0, 0, 1.5f, 2.0f, 0.5f);
        turn(q, 0, 0, 0, 0, 1.2f);
        float[] after = run(q, tilt(0), 0, 1f, 0, 1f);
        check("recovers after hand rotation", after[1] > 30, f(after));

        // 7f. 單幀位移上限（不瞬移）
        q = new MotionProcessor();
        float[] o1 = new float[2];
        for (int i = 0; i < 30; i++) q.onLinear(0, 50f, 0, 0.01f);
        float maxStep = 0;
        for (int i = 0; i < 120; i++) { q.step(1 / 60f, o1); maxStep = Math.max(maxStep, (float) Math.hypot(o1[0], o1[1])); }
        check("per-frame displacement capped", maxStep <= MotionProcessor.MAX_SPEED / 60f + 0.01f, "max " + maxStep);

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
