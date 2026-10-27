package tw.roastduck.freecues;

/**
 * 運動引擎（純邏輯，不依賴 Android，可在 JVM 測試）。
 *
 * 1. 方向：用旋轉矩陣把線性加速度轉到世界座標取水平分量，再投影到「車頭方向」
 *    （螢幕上緣與螢幕背面的水平投影和）→ 手機平放、斜拿、直立都抓得到前後加速。
 * 2. 軟死區 + EMA 低通：扣掉雜訊底而非一刀切 → 小加速度也有連續反應，不會卡住。
 * 3. 速度模型：加速度推動點的速度、阻尼回歸 0；每幀積分 → 平滑連續。
 * 4. 轉彎：車頭方向 heading 的變化直接帶動水平位移。
 */
final class MotionProcessor {
    static final float NOISE_FLOOR = 0.10f;  // m/s²
    static final float TAU = 0.10f;          // s
    static final float DAMP = 2.0f;          // 1/s
    static final float MAX_SPEED = 900f;     // dp/s
    static final float BASE_GAIN = 300f;     // dp/s² per m/s²（sensitivity 2.7 時）
    static final float YAW_GAIN = 260f;      // dp per rad（sensitivity 2.7 時）

    float sensitivity = 2.7f;
    /** Display.getRotation()：0..3 */
    int rotation = 0;

    private final float[] R = new float[9];
    private boolean haveR;
    private float fwd, lat, vx, vy, pendingYaw;
    private float prevHeading;
    private boolean havePrevHeading;

    void clearRotation() { haveR = false; havePrevHeading = false; }

    void reset() {
        fwd = lat = vx = vy = pendingYaw = 0;
        havePrevHeading = false;
    }

    /** 螢幕上方向／右方向（裝置座標）。 */
    private float[] screenUp() {
        switch (rotation & 3) {
            case 1: return new float[]{-1, 0, 0};
            case 2: return new float[]{0, -1, 0};
            case 3: return new float[]{1, 0, 0};
            default: return new float[]{0, 1, 0};
        }
    }

    private float[] screenRight() {
        switch (rotation & 3) {
            case 1: return new float[]{0, 1, 0};
            case 2: return new float[]{-1, 0, 0};
            case 3: return new float[]{0, -1, 0};
            default: return new float[]{1, 0, 0};
        }
    }

    private float[] toWorld(float x, float y, float z) {
        return new float[]{
                R[0] * x + R[1] * y + R[2] * z,
                R[3] * x + R[4] * y + R[5] * z,
                R[6] * x + R[7] * y + R[8] * z};
    }

    /** 世界座標水平「車頭方向」單位向量；拿不準時回傳 null。 */
    private float[] forward() {
        float[] u = screenUp();
        float[] a = toWorld(u[0], u[1], u[2]);
        float[] b = toWorld(0, 0, -1);
        float fx = a[0] + b[0], fy = a[1] + b[1];
        float n = (float) Math.hypot(fx, fy);
        if (n < 0.2f) return null;
        return new float[]{fx / n, fy / n};
    }

    /** r9 = SensorManager.getRotationMatrixFromVector 的 3x3（裝置→世界）。 */
    void onRotation(float[] r9) {
        System.arraycopy(r9, 0, R, 0, 9);
        haveR = true;
        float[] f = forward();
        if (f == null) { havePrevHeading = false; return; }
        float h = (float) Math.atan2(f[0], f[1]);   // 0=北，順時針為正
        if (havePrevHeading) {
            float d = h - prevHeading;
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            if (Math.abs(d) < 0.3f) pendingYaw += d;  // 濾掉翻轉手機的大跳變
        }
        prevHeading = h;
        havePrevHeading = true;
    }

    /** 線性加速度（裝置座標，已去重力，m/s²），dt 秒。 */
    void onLinear(float ax, float ay, float az, float dt) {
        dt = Math.max(0.001f, Math.min(0.1f, dt));
        float f, l;
        float[] fw = haveR ? forward() : null;
        if (fw != null) {
            float[] w = toWorld(ax, ay, az);
            f = w[0] * fw[0] + w[1] * fw[1];
            l = w[0] * fw[1] - w[1] * fw[0];   // right = (fy, -fx)
        } else {
            float[] u = screenUp(), r = screenRight();
            f = ax * u[0] + ay * u[1] + az * u[2];
            l = ax * r[0] + ay * r[1] + az * r[2];
        }
        float k = dt / (TAU + dt);
        fwd += (f - fwd) * k;
        lat += (l - lat) * k;
    }

    static float soft(float v) {
        float a = Math.abs(v) - NOISE_FLOOR;
        return a <= 0 ? 0 : Math.copySign(a, v);
    }

    /** 每幀呼叫：out = 本幀點位移 (dx, dy)，單位 dp。前進加速→點往下；左轉→點往右。 */
    void step(float dt, float[] out) {
        dt = Math.max(0.001f, Math.min(0.05f, dt));
        float s = sensitivity / 2.7f;
        vx += -soft(lat) * BASE_GAIN * s * dt;
        vy += soft(fwd) * BASE_GAIN * s * dt;
        float d = (float) Math.exp(-DAMP * dt);
        vx *= d;
        vy *= d;
        float sp = (float) Math.hypot(vx, vy);
        if (sp > MAX_SPEED) { vx *= MAX_SPEED / sp; vy *= MAX_SPEED / sp; }
        float yaw = -pendingYaw * YAW_GAIN * s;
        pendingYaw = 0;
        out[0] = vx * dt + yaw;
        out[1] = vy * dt;
        lastYawSpeed = Math.abs(yaw) / dt;
    }

    private float lastYawSpeed;
    String debug() { return String.format("fwd=%.3f lat=%.3f v=%.1f,%.1f", fwd, lat, vx, vy); }

    /** 目前視覺運動速度 dp/s（判斷「在車上」用）。 */
    float motion() { return (float) Math.hypot(vx, vy) + lastYawSpeed; }
}
