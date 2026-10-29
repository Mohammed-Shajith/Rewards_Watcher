package io.github.mohammedshajith.rewardswatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Alarms are cleared on reboot and app updates; restart the chain if the user
 * had watching turned on. Also reacts to the exact-alarm permission changing.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        if (State.watching(c)) Scheduler.scheduleIn(c, 60_000);
    }
}
