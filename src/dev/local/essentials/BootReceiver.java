package dev.local.essentials;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms countdown alarms after the phone restarts. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            TimerStore.restoreAfterBoot(c);
        }
    }
}
