package tw.roastduck.freecues;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class CuesTile extends TileService {
    @Override
    public void onStartListening() { update(); }

    @Override
    public void onClick() {
        if (CuesService.running) {
            CuesService.stop(this);
        } else if (Settings.canDrawOverlays(this)) {
            CuesService.start(this);
        } else {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i,
                        android.app.PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(i);
            }
        }
        getQsTile().setState(Tile.STATE_UNAVAILABLE);
        getQsTile().updateTile();
        new android.os.Handler(getMainLooper()).postDelayed(this::update, 400);
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(CuesService.running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
}
