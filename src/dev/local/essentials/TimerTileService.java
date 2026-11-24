package dev.local.essentials;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Quick Settings tile that starts a five-minute countdown. */
public class TimerTileService extends TileService {

    private static final int MINUTES = 5;

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        TimerStore.startMinutes(this, 0, MINUTES);
        Toast.makeText(this, "Đã hẹn giờ " + MINUTES + " phút", Toast.LENGTH_SHORT).show();
        if (SidebarService.running()) SidebarService.refreshTimers();
        if (Settings.canDrawOverlays(this)) {
            Side.setOn(this, true);
            try {
                Intent i = new Intent(this, SidebarService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Exception ignored) {
            }
        }
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("Hẹn giờ");
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_multitimer));
        t.setState(TimerStore.activeCount(this) > 0
                ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
}
