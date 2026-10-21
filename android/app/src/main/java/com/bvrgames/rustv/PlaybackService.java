package com.bvrgames.rustv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Звук в фоне (телефон): пока играет канал, держим приложение живым и показываем уведомление
 * «rus tv — Первый» с кнопкой «Стоп». Экран можно выключить или перейти в другое приложение — звук идёт.
 */
public class PlaybackService extends Service {
    static final String EXTRA_TITLE = "title";
    static final String ACTION_STOP = "com.bvrgames.rustv.STOP";
    private static final String CHANNEL = "playback";
    private static final int ID = 1;

    static void show(Context c, String title) {
        Intent i = new Intent(c, PlaybackService.class).putExtra(EXTRA_TITLE, title);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {} // система не дала — просто без фонового звука
    }

    static void hide(Context c) {
        c.stopService(new Intent(c, PlaybackService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            MainActivity.stopFromNotification();
            stopSelf();
            return START_NOT_STICKY;
        }
        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
        Notification n = build(title == null ? "rus tv" : title);
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(ID, n);
        return START_NOT_STICKY;
    }

    private Notification build(String title) {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, PlaybackService.class).setAction(ACTION_STOP), piFlags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Звук в фоне", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            //noinspection deprecation
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        b.setSmallIcon(R.drawable.ic_stat_tv)
                .setContentTitle(title)
                .setContentText("rus tv · звук в фоне")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(0, "Стоп", stop).build());
        if (Build.VERSION.SDK_INT >= 21) b.setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT);
        return b.build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
