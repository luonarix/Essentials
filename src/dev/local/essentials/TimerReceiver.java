package dev.local.essentials;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Wakes up when a countdown ends, even if the app process was killed. */
public class TimerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        int slot = intent == null ? -1 : intent.getIntExtra("slot", -1);
        if (slot < 0 || slot >= TimerStore.SLOTS) return;
        if (!TimerStore.running(c, slot)) return;
        TimerStore.clear(c, slot);
        TimerStore.notifyDone(c, slot);

        // Keep a running sidebar in sync with the new state.
        if (SidebarService.running()) SidebarService.refreshTimers();
    }
}
