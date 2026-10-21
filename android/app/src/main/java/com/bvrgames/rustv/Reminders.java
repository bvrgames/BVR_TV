package com.bvrgames.rustv;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.HashSet;
import java.util.Set;

/**
 * Напоминания о передачах: за 5 минут до начала — уведомление (на телефоне),
 * а если приложение открыто — плашка «Скоро начнётся…» с переключением на канал (и на ТВ, и на телефоне).
 */
public class Reminders extends BroadcastReceiver {
    static final String EXTRA_CHANNEL = "reminder_channel";
    private static final String EXTRA_TITLE = "reminder_title";
    private static final String EXTRA_KEY = "reminder_key";
    private static final String CHANNEL = "reminders";
    static final long BEFORE_SEC = 5 * 60;

    static String key(String channel, long start) { return channel + "|" + start; }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("rustv", Context.MODE_PRIVATE); }

    static Set<String> all(Context c) {
        Set<String> s = new HashSet<>(prefs(c).getStringSet("reminders", new HashSet<>()));
        // чистим прошедшие
        long now = System.currentTimeMillis() / 1000;
        Set<String> alive = new HashSet<>();
        for (String k : s) {
            try { if (Long.parseLong(k.substring(k.lastIndexOf('|') + 1)) > now - 3600) alive.add(k); } catch (Exception ignored) {}
        }
        return alive;
    }

    static boolean has(Context c, String channel, long start) { return all(c).contains(key(channel, start)); }

    /** Включить/выключить напоминание. Возвращает true, если теперь включено. */
    static boolean toggle(Context c, String channel, long start, String title) {
        Set<String> s = all(c);
        String k = key(channel, start);
        boolean on = !s.contains(k);
        if (on) s.add(k); else s.remove(k);
        prefs(c).edit().putStringSet("reminders", s).apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pending(c, channel, start, title);
        if (am == null) return on;
        if (!on) { am.cancel(pi); return false; }
        long at = Math.max(System.currentTimeMillis() + 5000, (start - BEFORE_SEC) * 1000);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
        return true;
    }

    private static PendingIntent pending(Context c, String channel, long start, String title) {
        Intent i = new Intent(c, Reminders.class)
                .putExtra(EXTRA_CHANNEL, channel).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_KEY, key(channel, start));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, key(channel, start).hashCode(), i, flags);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String channel = i.getStringExtra(EXTRA_CHANNEL), title = i.getStringExtra(EXTRA_TITLE), k = i.getStringExtra(EXTRA_KEY);
        if (channel == null || title == null) return;
        Set<String> s = all(c);
        if (k != null && !s.remove(k)) return; // напоминание уже отменили
        prefs(c).edit().putStringSet("reminders", s).apply();
        if (MainActivity.showReminder(channel, title)) return; // приложение открыто — показали плашку
        notify(c, channel, title);
    }

    private static void notify(Context c, String channel, String title) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Напоминания о передачах", NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(c, CHANNEL);
        } else {
            //noinspection deprecation
            b = new Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(c, channel.hashCode(),
                new Intent(c, MainActivity.class).putExtra(EXTRA_CHANNEL, channel)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        b.setSmallIcon(R.drawable.ic_stat_tv)
                .setContentTitle("Через 5 минут: " + title)
                .setContentText(channel + " · нажми, чтобы смотреть")
                .setContentIntent(open)
                .setAutoCancel(true);
        try { nm.notify(channel.hashCode(), b.build()); } catch (Exception ignored) {}
    }
}
