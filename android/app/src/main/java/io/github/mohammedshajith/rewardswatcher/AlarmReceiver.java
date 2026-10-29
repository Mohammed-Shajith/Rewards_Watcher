package io.github.mohammedshajith.rewardswatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/** Woken by the exact alarm; runs one check off the main thread, then the app sleeps. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final Context c = context.getApplicationContext();
        final PendingResult pending = goAsync();
        PowerManager pm = c.getSystemService(PowerManager.class);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rewardswatcher:check");
        wl.acquire(40_000); // hard cap: a check takes ~1-3 s
        new Thread(() -> {
            try {
                Checker.run(c, true);
            } finally {
                if (wl.isHeld()) wl.release();
                pending.finish();
            }
        }, "rw-check").start();
    }
}
