package tw.roastduck.freecues;

import android.content.Context;
import android.content.SharedPreferences;

/** 設定（靈敏度 1.8/2.7、大小 0.8/1.0/1.8）。 */
final class Prefs {
    static final String NAME = "freecues";
    static final String SENS = "sens";          // 0=Low 1=Standard
    static final String VIS = "vis";            // 0=Always 1=Only in vehicle
    static final String COLOR_MODE = "colorMode"; // 0=White 1=Black 2=Pick
    static final String COLOR = "color";
    static final String STYLE = "style";        // 0=Grid 1=Bubble
    static final String SIZE = "size";          // 0=Small 1=Standard 2=Large

    static SharedPreferences get(Context c) { return c.getSharedPreferences(NAME, Context.MODE_PRIVATE); }

    static float sensitivity(SharedPreferences p) { return p.getInt(SENS, 1) == 0 ? 1.8f : 2.7f; }
    static boolean always(SharedPreferences p) { return p.getInt(VIS, 1) == 0; }
    static int style(SharedPreferences p) { return p.getInt(STYLE, 0); }
    static float size(SharedPreferences p) {
        switch (p.getInt(SIZE, 1)) { case 0: return 0.8f; case 2: return 1.8f; default: return 1f; }
    }
    /** {內圈, 外圈} */
    static int[] colors(SharedPreferences p) {
        switch (p.getInt(COLOR_MODE, 0)) {
            case 1: return new int[]{0xFF292929, 0xFFECECEC};
            case 2: {
                int c = p.getInt(COLOR, 0xFF2196F3) | 0xFF000000;
                double lum = 0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255);
                return new int[]{c, lum > 128 ? 0xFF2C2C2C : 0xFFECECEC};
            }
            default: return new int[]{0xFFFFFFFF, 0xFF2C2C2C};
        }
    }

    private Prefs() {}
}
