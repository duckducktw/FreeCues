package tw.roastduck.freecues;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.view.WindowManager;

public final class CuesService extends Service
        implements SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {

    static final String ACTION_STOP = "tw.roastduck.freecues.STOP";
    static final String ACTION_INJECT = "tw.roastduck.freecues.INJECT";
    static final String CHANNEL = "cues";
    static volatile boolean running;
    static volatile CuesService instance;

    private WindowManager wm;
    private SensorManager sm;
    private CuesView view;
    private final MotionProcessor proc = new MotionProcessor();
    private SharedPreferences prefs;
    private final float[] r9 = new float[9];
    private long lastLinNs;
    private boolean injecting;
    private int dbg;

    static void start(Context c) { c.startForegroundService(new Intent(c, CuesService.class)); }
    /** 停止：先讓點播完消失動畫再真正關掉服務。 */
    static void stop(Context c) {
        CuesService s = instance;
        if (s != null) s.gracefulStop();
        else c.stopService(new Intent(c, CuesService.class));
    }

    private boolean stopping;
    void gracefulStop() {
        if (stopping) return;
        stopping = true;
        running = false;                       // UI 立即切回「未啟動」
        TileService.requestListeningState(this, new ComponentName(this, CuesTile.class));
        if (view == null) { stopSelf(); return; }
        view.dismiss(this::stopSelf);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = getSystemService(WindowManager.class);
        sm = getSystemService(SensorManager.class);
        prefs = Prefs.get(this);
        startFg();
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return; }
        view = new CuesView(this, proc);
        applyPrefs();
        wm.addView(view, overlayParams());
        view.peek(1500);
        view.start();
        registerSensors();
        prefs.registerOnSharedPreferenceChangeListener(this);
        running = true;
        instance = this;
        TileService.requestListeningState(this, new ComponentName(this, CuesTile.class));
    }

    private WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 31)
            lp.alpha = getSystemService(InputManager.class).getMaximumObscuringOpacityForTouch();
        return lp;
    }

    private void registerSensors() {
        sm.unregisterListener(this);
        Sensor rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv == null) rv = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rv != null) sm.registerListener(this, rv, 10_000);
        Sensor lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (lin != null) sm.registerListener(this, lin, 10_000);
        proc.reset();
        proc.clearRotation();
        lastLinNs = 0;
    }

    private void applyPrefs() {
        view.style = Prefs.style(prefs);
        view.sizeScale = Prefs.size(prefs);
        view.always = Prefs.always(prefs);
        int[] c = Prefs.colors(prefs);
        view.inner = c[0];
        view.outer = c[1];
        proc.sensitivity = Prefs.sensitivity(prefs);
        proc.rotation = wm.getDefaultDisplay().getRotation();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (BuildConfig.DEBUG_LOG) android.util.Log.d("FreeCues", "pref changed " + key + " view=" + (view != null));
        if (view == null) return;
        applyPrefs();
        if (BuildConfig.DEBUG_LOG) android.util.Log.d("FreeCues", "applied style=" + view.style + " inner=" + Integer.toHexString(view.inner));
        view.peek(1500);
        view.invalidate();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        proc.rotation = wm.getDefaultDisplay().getRotation();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (injecting) return;
        float[] v = e.values;
        int t = e.sensor.getType();
        if (t == Sensor.TYPE_ROTATION_VECTOR || t == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(r9, v);
            proc.onRotation(r9);
            if (BuildConfig.DEBUG_LOG && (++dbg % 50) == 0) android.util.Log.v("FreeCuesS", "rot " + java.util.Arrays.toString(v));
        } else if (t == Sensor.TYPE_LINEAR_ACCELERATION) {
            float dt = lastLinNs == 0 ? 0.01f : (e.timestamp - lastLinNs) / 1e9f;
            lastLinNs = e.timestamp;
            proc.onLinear(v[0], v[1], v[2], dt);
            if (BuildConfig.DEBUG_LOG && (dbg % 50) == 1) android.util.Log.v("FreeCuesS", "lin " + v[0] + " " + v[1] + " " + v[2] + " " + proc.debug());
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    /** 測試：以裝置座標注入一段持續加速度（真實感測器暫停）。 */
    void inject(float ax, float ay, long ms) {
        injecting = true;
        proc.reset();
        proc.clearRotation();
        final long end = System.nanoTime() + ms * 1_000_000L;
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        h.post(new Runnable() {
            @Override public void run() {
                if (System.nanoTime() < end) {
                    proc.onLinear(ax, ay, 0, 0.01f);
                    h.postDelayed(this, 10);
                } else {
                    for (int i = 0; i < 60; i++) proc.onLinear(0, 0, 0, 0.01f);
                }
            }
        });
    }

    void endInject() { injecting = false; proc.reset(); }
    CuesView view() { return view; }

    @Override
    public int onStartCommand(Intent i, int f, int id) {
        if (i != null && ACTION_STOP.equals(i.getAction())) gracefulStop();
        return START_STICKY;
    }

    private void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "暈車點", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CuesService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("FreeCues 運作中")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (sm != null) sm.unregisterListener(this);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (view != null) {
            view.stop();
            try { wm.removeView(view); } catch (Exception ignored) {}
            view = null;
        }
        TileService.requestListeningState(this, new ComponentName(this, CuesTile.class));
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
