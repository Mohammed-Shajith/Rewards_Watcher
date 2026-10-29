package io.github.mohammedshajith.rewardswatcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Wakes the app with an exact alarm that is allowed to fire in Doze
 * (setExactAndAllowWhileIdle). Nothing runs between checks. Android limits
 * these alarms to roughly one per 9-10 minutes per app while the phone is
 * idle, which is why the default interval is 10 minutes.
 */
final class Scheduler {

    private static PendingIntent intent(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction("io.github.mohammedshajith.rewardswatcher.CHECK");
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static boolean canExact(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    static void scheduleIn(Context c, long delayMs) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long at = System.currentTimeMillis() + delayMs;
        if (canExact(c)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c));
        } else {
            // Without the "Alarms & reminders" permission Android may delay this a lot.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c));
        }
        State.prefs(c).edit().putLong("nextAt", at).apply();
    }

    static void scheduleNext(Context c) {
        scheduleIn(c, State.intervalMin(c) * 60_000L);
    }

    static void cancel(Context c) {
        c.getSystemService(AlarmManager.class).cancel(intent(c));
        State.prefs(c).edit().putLong("nextAt", 0).apply();
    }
}
