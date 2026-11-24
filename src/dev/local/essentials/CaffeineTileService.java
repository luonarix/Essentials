package dev.local.essentials;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Quick Settings tile for the keep-screen-on switch. */
public class CaffeineTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean next = !Side.caffeineOn(this);
        Side.setCaffeineOn(this, next);
        if (next) {            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Cần quyền hiển thị trên ứng dụng khác",
                        Toast.LENGTH_SHORT).show();
                openApp();
                return;
            }
            Side.setOn(this, true);
            try {
                Intent i = new Intent(this, SidebarService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Exception ignored) {
            }
        }
        if (SidebarService.running()) SidebarService.refreshCaffeine();
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("Giữ sáng");
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_caffeine));
        t.setState(Side.caffeineOn(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
