import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Looper;

/**
 * 量測工具（app_process 直接跑，不安裝）：比較 ROTATION_VECTOR（含磁力計）與
 * GAME_ROTATION_VECTOR（無磁力計）的 heading 單樣本跳變，及陀螺儀雜訊。
 * 用法：見 tools/probe.sh
 */
public final class SensorProbe {
    public static void main(String[] a) throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        android.content.Context ctx = (android.content.Context) at.getMethod("getSystemContext").invoke(thread);
        SensorManager sm = ctx.getSystemService(SensorManager.class);
        final int secs = a.length > 0 ? Integer.parseInt(a[0]) : 20;
        final float[] r = new float[9];
        final double[][] st = new double[3][4]; // per sensor: prevHeading, maxJump, count>0.05, samples
        for (double[] s : st) s[0] = Double.NaN;
        final double[] gyro = new double[3];    // maxAbs, sumSq, n
        SensorEventListener l = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                int t = e.sensor.getType();
                if (t == Sensor.TYPE_GYROSCOPE) {
                    double m = Math.sqrt(e.values[0]*e.values[0] + e.values[1]*e.values[1] + e.values[2]*e.values[2]);
                    gyro[0] = Math.max(gyro[0], m); gyro[1] += m*m; gyro[2]++;
                    return;
                }
                int i = t == Sensor.TYPE_ROTATION_VECTOR ? 0 : 1;
                SensorManager.getRotationMatrixFromVector(r, e.values);
                // 螢幕上緣 + 背面 的水平投影 = 車頭方向（同 MotionProcessor.forward）
                double fx = r[1] - r[2], fy = r[4] - r[5];
                double h = Math.atan2(fx, fy);
                double[] s = st[i];
                if (!Double.isNaN(s[0])) {
                    double d = h - s[0];
                    while (d > Math.PI) d -= 2*Math.PI;
                    while (d < -Math.PI) d += 2*Math.PI;
                    s[1] = Math.max(s[1], Math.abs(d));
                    if (Math.abs(d) > 0.05) s[2]++;
                }
                s[0] = h; s[3]++;
            }
            @Override public void onAccuracyChanged(Sensor s, int acc) {}
        };
        sm.registerListener(l, sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), 10_000);
        sm.registerListener(l, sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR), 10_000);
        sm.registerListener(l, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), 10_000);
        new android.os.Handler(Looper.myLooper()).postDelayed(() -> {
            String[] n = {"ROTATION_VECTOR(mag)", "GAME_ROTATION_VECTOR"};
            for (int i = 0; i < 2; i++)
                System.out.printf("%-22s samples=%d maxJump=%.4f rad (%.1f°) jumps>0.05rad=%d%n",
                        n[i], (long) st[i][3], st[i][1], Math.toDegrees(st[i][1]), (long) st[i][2]);
            System.out.printf("GYROSCOPE |w| max=%.4f rms=%.4f rad/s n=%d%n",
                    gyro[0], Math.sqrt(gyro[1] / Math.max(1, gyro[2])), (long) gyro[2]);
            System.exit(0);
        }, secs * 1000L);
        Looper.loop();
    }
}
