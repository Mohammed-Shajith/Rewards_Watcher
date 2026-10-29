package io.github.mohammedshajith.rewardswatcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Local notifications: no ntfy or push service needed. */
final class Notifier {

    static final String RESTOCK = "restock";
    static final String INFO = "info";

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel r = new NotificationChannel(RESTOCK, "Back in stock", NotificationManager.IMPORTANCE_HIGH);
        r.setDescription("A watched gift card is redeemable again");
        r.enableVibration(true);
        nm.createNotificationChannel(r);
        NotificationChannel i = new NotificationChannel(INFO, "Watcher status", NotificationManager.IMPORTANCE_DEFAULT);
        i.setDescription("New cards, sign-in problems, repeated failures");
        nm.createNotificationChannel(i);
    }

    /** Show a notification; tapping opens url (or the app when url is null). */
    static void show(Context c, String channel, int id, String title, String text, String url) {
        ensureChannels(c);
        Intent open = url != null
                ? new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                : new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, id, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(RESTOCK.equals(channel) ? Notification.CATEGORY_ALARM : Notification.CATEGORY_STATUS)
                .build();
        c.getSystemService(NotificationManager.class).notify(id, n);
    }
}
