package com.bvrgames.rustv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Автообновление через GitHub: после каждой сборки там лежат rus-tv.apk и tv-version.txt («номер sha256»).
 * Приложение тихо скачивает новую версию в фоне, а потом спрашивает «Обновить?».
 * Без подтверждения Android ставить приложения не даёт — это одно нажатие OK.
 */
final class Updater {
    static final String ACTION_INSTALL = "com.bvrgames.rustv.INSTALL_RESULT";
    private static final String BASE = "https://github.com/bvrgames/BVR_TV/releases/download/tv/";

    private final Activity a;
    private final OkHttpClient http;
    private final ExecutorService pool;
    private final Runnable onBeforeInstall;
    private final Note note;
    private volatile boolean manual;

    /** Что показывать под кнопкой «Проверить обновления». */
    interface Note { void show(String text); }
    private volatile boolean busy;
    private int readyVersion;
    private File readyFile;
    private boolean asked, waitingPermission;

    Updater(Activity a, OkHttpClient http, ExecutorService pool, Runnable onBeforeInstall, Note note) {
        this.a = a; this.http = http; this.pool = pool; this.onBeforeInstall = onBeforeInstall; this.note = note;
    }

    /** Кнопка «Проверить обновления». */
    void checkNow() {
        if (readyFile != null) { asked = false; offer(); return; }
        if (busy) return;
        manual = true;
        note.show("Проверяю…");
        check();
    }

    private void say(String s) { a.runOnUiThread(() -> note.show(s)); }

    /** Проверить GitHub и, если есть новая версия, скачать её в фоне. */
    void check() {
        if (busy || readyFile != null) return;
        busy = true;
        pool.execute(() -> {
            try {
                String info = text(BASE + "tv-version.txt");
                if (info == null) { if (manual) say("Нет связи с GitHub, попробуй позже"); return; }
                String[] p = info.trim().split("\\s+");
                int ver = Integer.parseInt(p[0]);
                String sha = p.length > 1 ? p[1].toLowerCase() : null;
                if (ver <= BuildConfig.VERSION_CODE) { if (manual) say("У тебя последняя версия " + BuildConfig.VERSION_NAME); return; }
                say("Скачиваю версию 1." + ver + "…");
                File f = new File(a.getCacheDir(), "update-" + ver + ".apk");
                if (!(f.exists() && sha != null && sha.equals(sha256(f)))) {
                    File tmp = new File(a.getCacheDir(), "update.part");
                    if (!download(BASE + "rus-tv.apk", tmp)) { say("Не скачалось, нажми ещё раз"); return; }
                    if (sha != null && !sha.equals(sha256(tmp))) { tmp.delete(); say("Скачалось с ошибкой, нажми ещё раз"); return; }
                    File[] old = a.getCacheDir().listFiles((d, n) -> n.startsWith("update-"));
                    if (old != null) for (File o : old) o.delete();
                    if (!tmp.renameTo(f)) return;
                }
                final int v = ver;
                a.runOnUiThread(() -> { readyVersion = v; readyFile = f; note.show("Версия 1." + v + " готова — нажми, чтобы установить"); offer(); });
            } catch (Exception e) {
                if (manual) say("Не получилось проверить, попробуй позже");
            } finally {
                busy = false;
                manual = false;
            }
        });
    }

    /** Вызывать из onResume: вернулись из настроек с разрешением — продолжаем установку. */
    void onResume() {
        if (waitingPermission && readyFile != null) {
            waitingPermission = false;
            if (canInstall()) install(); else asked = false;
        }
    }

    private void offer() {
        if (asked || readyFile == null || a.isFinishing()) return;
        asked = true;
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Вышла новая версия rus tv")
                .setMessage("Версия 1." + readyVersion + " уже скачана. Обновить сейчас? Это займёт несколько секунд.")
                .setPositiveButton("Обновить", (d, w) -> {
                    if (canInstall()) install();
                    else askPermission();
                })
                .setNegativeButton("Позже", (d, w) -> {})
                .show();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls();
    }

    private void askPermission() {
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Нужно одно разрешение")
                .setMessage("Сейчас откроются настройки. Включи «Разрешить установку» для rus tv и нажми «Назад» — обновление продолжится само. Это нужно сделать только один раз.")
                .setPositiveButton("Открыть настройки", (d, w) -> {
                    waitingPermission = true;
                    try {
                        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                    } catch (Exception e) {
                        try { a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } catch (Exception ignored) { waitingPermission = false; }
                    }
                })
                .setNegativeButton("Позже", (d, w) -> asked = false)
                .show();
    }

    private void install() {
        final File f = readyFile;
        if (f == null || !f.exists()) return;
        onBeforeInstall.run();
        pool.execute(() -> {
            try {
                PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(a.getPackageName());
                params.setSize(f.length());
                int id = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("base.apk", 0, f.length())) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        s.fsync(out);
                    }
                    Intent cb = new Intent(a, MainActivity.class).setAction(ACTION_INSTALL)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pend = PendingIntent.getActivity(a, 77, cb, flags);
                    s.commit(pend.getIntentSender());
                }
            } catch (Exception e) {
                a.runOnUiThread(() -> { asked = false; });
            }
        });
    }

    /** Ответ системы установки приходит в onNewIntent. */
    void onInstallResult(Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { a.startActivity(confirm); } catch (Exception ignored) {}
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            asked = false; // отменили или ошибка — предложим в следующий раз
        }
    }

    private String text(String url) {
        try (Response r = http.newCall(new Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute()) {
            return r.isSuccessful() && r.body() != null ? r.body().string() : null;
        } catch (Exception e) { return null; }
    }

    private boolean download(String url, File to) {
        OkHttpClient slow = http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(10, java.util.concurrent.TimeUnit.MINUTES).build();
        try (Response r = slow.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return false;
            try (InputStream in = r.body().byteStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return to.length() > 100_000;
        } catch (Exception e) { return false; }
    }

    private static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
