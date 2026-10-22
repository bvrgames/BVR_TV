package com.bvrgames.rustv;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Пуши на телефонах: раз в несколько часов приложение в фоне спрашивает сервер, есть ли новости,
 * и ставит уведомление ровно на нужное время (например, «через 10 минут UFC»). На телевизоре не работает.
 */
public class News extends BroadcastReceiver {
    private static final String URL_NEWS = "https://rustv.bvrhockey.workers.dev/news";
    private static final String ACTION_CHECK = "com.bvrgames.rustv.NEWS_CHECK";
    private static final String ACTION_SHOW = "com.bvrgames.rustv.NEWS_SHOW";
    private static final String EXTRA_ID = "news_id";
    private static final String EXTRA_TEXT = "news_text";
    private static final String CHANNEL = "news";
    private static final long LATE = 30 * 60 * 1000L; // опоздали больше чем на 30 минут — уже не показываем

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("rustv", Context.MODE_PRIVATE); }

    static boolean isTv(Context c) {
        UiModeManager um = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        return (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || !c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    /** При запуске приложения: включаем фоновую проверку раз в 3 часа и сразу проверяем новости. */
    static void start(Context c) {
        if (isTv(c)) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent check = PendingIntent.getBroadcast(c, 7001, new Intent(c, News.class).setAction(ACTION_CHECK), flags());
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + 3 * AlarmManager.INTERVAL_HOUR,
                3 * AlarmManager.INTERVAL_HOUR, check);
        final Context app = c.getApplicationContext();
        new Thread(() -> fetchAndSchedule(app)).start();
    }

    /** Скачиваем список новостей и ставим уведомления на нужное время (каждую — один раз). */
    private static void fetchAndSchedule(Context c) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(URL_NEWS).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            if (con.getResponseCode() != 200) return;
            InputStream in = con.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONArray list = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("news");
            Set<String> done = new HashSet<>(prefs(c).getStringSet("news_set", new HashSet<>()));
            long now = System.currentTimeMillis();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                String id = o.getString("id"), text = o.getString("text");
                long at = o.getLong("at");
                if (done.contains(id) || at < now - LATE) continue;
                schedule(c, id, text, Math.max(at, now + 3000));
                done.add(id);
            }
            prefs(c).edit().putStringSet("news_set", done).apply();
        } catch (Exception ignored) {
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static void schedule(Context c, String id, String text, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, News.class).setAction(ACTION_SHOW).putExtra(EXTRA_ID, id).putExtra(EXTRA_TEXT, text);
        PendingIntent pi = PendingIntent.getBroadcast(c, id.hashCode(), i, flags());
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String action = i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // после перезагрузки будильники сбрасываются — ставим заново (уже поставленные id сбрасываем)
            prefs(c).edit().remove("news_set").apply();
            start(c);
            return;
        }
        if (ACTION_CHECK.equals(action)) {
            final PendingResult r = goAsync();
            final Context app = c.getApplicationContext();
            new Thread(() -> { try { fetchAndSchedule(app); } finally { r.finish(); } }).start();
            return;
        }
        if (ACTION_SHOW.equals(action)) {
            String id = i.getStringExtra(EXTRA_ID), text = i.getStringExtra(EXTRA_TEXT);
            if (id == null || text == null) return;
            Set<String> shown = new HashSet<>(prefs(c).getStringSet("news_shown", new HashSet<>()));
            if (!shown.add(id)) return; // уже показывали
            prefs(c).edit().putStringSet("news_shown", shown).apply();
            show(c, id, text);
        }
    }

    private static void show(Context c, String id, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Новости rus tv", NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(c, CHANNEL);
        } else {
            //noinspection deprecation
            b = new Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        }
        PendingIntent open = PendingIntent.getActivity(c, id.hashCode(),
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), flags());
        b.setSmallIcon(R.drawable.ic_stat_tv)
                .setContentTitle("rus tv")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT >= 23) {
            //noinspection deprecation
            b.addAction(new Notification.Action.Builder(null, "Смотреть", open).build());
        }
        try { nm.notify(id.hashCode(), b.build()); } catch (Exception ignored) {}
    }
}
