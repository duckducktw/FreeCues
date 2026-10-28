package tw.roastduck.freecues;

/**
 * 運動引擎（純邏輯，不依賴 Android，可在 JVM 測試）。
 *
 * 1. 方向：旋轉矩陣 R（GAME_ROTATION_VECTOR，無磁力計 → 不會有磁場校正造成的方位角跳變）
 *    把線性加速度轉到世界座標，投影到平滑後的「車頭方向」。
 * 2. 軟死區 + EMA 低通 + 速度模型（阻尼）→ 小加速度也連續、不會卡住。
 * 3. 轉彎（陀螺儀）：角速度轉世界座標，取垂直分量 ωz 當候選偏航。
 *    只有「車在轉」才採用：
 *    - 向心加速度一致性：車轉彎時 a_lat = v·ωz（v 為車速）。估 v = -a_lat/ωz，
 *      方向一致且 v 落在合理範圍才給權重。手在原地轉手機幾乎沒有向心加速度 → 權重 0。
 *    - ωz 上限：車輛偏航極少 > 1 rad/s。
 * 4. 手動轉手機偵測（陀螺儀）：世界水平分量 ω_h = 手機俯仰/側傾的速率；車不會讓手機
 *    快速翻轉。|ω_h| 或過大 |ωz| 超過門檻 → hand gate 關閉，暫停所有輸入
 *    （LINEAR_ACCELERATION 在快速轉動時會漏重力，產生假加速度），停止後 0.5 s 漸回。
 * 5. 防瞬移：每幀位移有上限（MAX_SPEED·dt）；偏航只走陀螺儀積分（不再用 heading 差分）。
 */
final class MotionProcessor {
    static final float NOISE_FLOOR = 0.10f;   // m/s²
    static final float TAU = 0.12f;           // s 線性加速度低通
    static final float DAMP = 2.0f;           // 1/s 速度阻尼
    static final float MAX_SPEED = 900f;      // dp/s 每幀位移上限也由此得出
    static final float BASE_GAIN = 300f;      // dp/s² per m/s²（sensitivity 2.7 時）
    static final float YAW_GAIN = 260f;       // dp per rad（sensitivity 2.7 時）

    // 手動轉手機判定
    static final float HAND_TILT_ON = 0.55f;  // rad/s 水平角速度（俯仰/側傾）
    static final float HAND_TILT_OFF = 0.25f;
    static final float HAND_YAW_MAX = 1.3f;   // rad/s 超過不像車
    static final float HAND_HOLD_S = 0.35f;   // 停止轉動後維持靜音
    static final float HAND_RECOVER_S = 0.5f; // 之後漸回

    // 偏航一致性（v = a_lat/ωz）
    static final float YAW_DEADBAND = 0.04f;  // rad/s 陀螺儀殘餘 bias
    static final float V_MIN = 1.5f, V_FULL = 4f, V_MAX = 70f;  // m/s
    static final float YAW_TAU = 0.35f;       // s 一致性低通

    float sensitivity = 2.7f;
    /** Display.getRotation()：0..3 */
    int rotation = 0;

    private final float[] R = new float[9];
    private boolean haveR;
    private float fwd, lat, vx, vy;
    private float fdx, fdy;          // 平滑後車頭方向（世界水平單位向量）
    private boolean haveFwdDir;

    // 陀螺儀
    private boolean haveGyro;
    private float wz, wzLp, latLp;   // 世界垂直角速度（瞬時/低通）、低通側向加速度
    private float pendingYaw;        // 本幀待套用偏航（rad，已乘權重）
    private float yawWeight;         // 0..1 車輛轉彎可信度
    private float handTimer = 0;     // >0 = 手動中／保持中
    private float handGate = 1f;     // 1 = 正常，0 = 靜音
    private float handRate;          // 最近一次判定用速率（debug）
    private float lastYawSpeed;

    // 無陀螺儀後援：heading 差分
    private float prevHeading;
    private boolean havePrevHeading;

    void clearRotation() { haveR = false; havePrevHeading = false; haveFwdDir = false; }

    void reset() {
        fwd = lat = vx = vy = pendingYaw = 0;
        wz = wzLp = latLp = 0;
        yawWeight = 0;
        handTimer = 0;
        handGate = 1f;
        havePrevHeading = false;
    }

    // ───────────── 座標 ─────────────

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

    /** 由目前姿態算出的車頭方向（未平滑）；n 太小（拿法退化）回傳 null。 */
    private float[] rawForward() {
        float[] u = screenUp();
        float[] a = toWorld(u[0], u[1], u[2]);
        float[] b = toWorld(0, 0, -1);
        float fx = a[0] + b[0], fy = a[1] + b[1];
        float n = (float) Math.hypot(fx, fy);
        if (n < 0.35f) return null;
        return new float[]{fx / n, fy / n};
    }

    // ───────────── 輸入 ─────────────

    /** r9 = SensorManager.getRotationMatrixFromVector 的 3x3（裝置→世界）。 */
    void onRotation(float[] r9) {
        System.arraycopy(r9, 0, R, 0, 9);
        haveR = true;
        float[] f = rawForward();
        if (f != null) {
            if (!haveFwdDir) { fdx = f[0]; fdy = f[1]; haveFwdDir = true; }
            else {
                // 低通（~0.3 s @100Hz），手動中凍結 → 轉手機時投影軸不會亂甩
                float k = handGate > 0.5f ? 0.035f : 0f;
                fdx += (f[0] - fdx) * k; fdy += (f[1] - fdy) * k;
                float n = (float) Math.hypot(fdx, fdy);
                if (n > 1e-3f) { fdx /= n; fdy /= n; }
            }
        }
        if (haveGyro || f == null) { if (f == null) havePrevHeading = false; return; }
        // 無陀螺儀後援：heading 差分，單樣本限制在 0.02 rad（100Hz = 2 rad/s）
        float h = (float) Math.atan2(f[0], f[1]);   // 順時針為正
        if (havePrevHeading) {
            float d = h - prevHeading;
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            if (Math.abs(d) < 0.02f) pendingYaw += -d * handGate;  // 左轉(d<0)→正
        }
        prevHeading = h;
        havePrevHeading = true;
    }

    /** 陀螺儀（裝置座標 rad/s），dt 秒。 */
    void onGyro(float gx, float gy, float gz, float dt) {
        if (!haveR) return;
        haveGyro = true;
        dt = Math.max(0.001f, Math.min(0.05f, dt));
        float[] w = toWorld(gx, gy, gz);
        float wh = (float) Math.hypot(w[0], w[1]);   // 俯仰/側傾 → 手
        wz = w[2];                                   // 垂直軸 → 車偏航候選（CCW/左轉為正）

        // 手動判定（遲滯）
        handRate = Math.max(wh, Math.abs(wz) > HAND_YAW_MAX ? Math.abs(wz) : 0);
        if (handRate > HAND_TILT_ON || Math.abs(wz) > HAND_YAW_MAX) handTimer = HAND_HOLD_S + HAND_RECOVER_S;
        else if (handRate > HAND_TILT_OFF && handTimer > 0) handTimer = Math.max(handTimer, HAND_HOLD_S + HAND_RECOVER_S);
        else handTimer = Math.max(0, handTimer - dt);
        handGate = handTimer <= 0 ? 1f
                : handTimer > HAND_RECOVER_S ? 0f
                : 1f - smooth(handTimer / HAND_RECOVER_S);

        // 偏航一致性：v = -a_lat/ωz（左轉 ωz>0、向心加速度向左 → lat<0）
        float k = dt / (YAW_TAU + dt);
        wzLp += (wz - wzLp) * k;
        latLp += (lat - latLp) * k;
        float wAbs = Math.abs(wzLp);
        float wt;
        if (wAbs < YAW_DEADBAND) wt = 0;
        else {
            float v = -latLp / wzLp;
            wt = v <= V_MIN || v > V_MAX ? 0 : clamp01((v - V_MIN) / (V_FULL - V_MIN));
        }
        // 權重本身也平滑，避免開關造成跳動
        yawWeight += (wt - yawWeight) * Math.min(1f, dt / 0.25f);
        float wUse = Math.abs(wz) < YAW_DEADBAND ? 0 : wz - Math.copySign(YAW_DEADBAND, wz);
        pendingYaw += wUse * dt * yawWeight * handGate;
    }

    /** 線性加速度（裝置座標，已去重力，m/s²），dt 秒。 */
    void onLinear(float ax, float ay, float az, float dt) {
        dt = Math.max(0.001f, Math.min(0.1f, dt));
        float f, l;
        if (haveR && haveFwdDir) {
            float[] w = toWorld(ax, ay, az);
            f = w[0] * fdx + w[1] * fdy;
            l = w[0] * fdy - w[1] * fdx;   // right = (fy, -fx)
        } else {
            float[] u = screenUp(), r = screenRight();
            f = ax * u[0] + ay * u[1] + az * u[2];
            l = ax * r[0] + ay * r[1] + az * r[2];
        }
        // 手動中：輸入視為 0（讓濾波值自然衰減，不是瞬間歸零）
        f *= handGate; l *= handGate;
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
        float yaw = pendingYaw * YAW_GAIN * s;
        pendingYaw = 0;
        float ox = vx * dt + yaw, oy = vy * dt;
        // 防瞬移：單幀位移上限
        float lim = MAX_SPEED * dt, m = (float) Math.hypot(ox, oy);
        if (m > lim) { ox *= lim / m; oy *= lim / m; }
        out[0] = ox;
        out[1] = oy;
        lastYawSpeed = Math.abs(yaw) / dt;
    }

    String debug() {
        return String.format("fwd=%.3f lat=%.3f v=%.1f,%.1f wz=%.3f yawW=%.2f hand=%.2f gate=%.2f",
                fwd, lat, vx, vy, wz, yawWeight, handRate, handGate);
    }

    float handGate() { return handGate; }
    float yawWeight() { return yawWeight; }

    /** 目前視覺運動速度 dp/s（判斷「在車上」用）。 */
    float motion() { return (float) Math.hypot(vx, vy) + lastYawSpeed; }

    private static float smooth(float t) { t = clamp01(t); return t * t * (3 - 2 * t); }
    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
