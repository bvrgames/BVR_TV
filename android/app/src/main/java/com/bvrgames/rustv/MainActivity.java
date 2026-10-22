package com.bvrgames.rustv;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity {

    private static final String SITE = "https://rus-tv.vercel.app/";
    private static final String RAW = "https://raw.githubusercontent.com/bvrgames/BVR_TV/main/";
    private static final String EPG = "https://raw.githubusercontent.com/bvrgames/BVR_TV/epg/epg.json";
    private static final String PROXY = "https://rustv.bvrhockey.workers.dev/?u=";
    private static final String BOT = "@rus_tv_bot";
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private static final int ACCENT = 0xFFBF5AF2, OK = 0xFF30D158, BAD = 0xFFFF453A, MUTED = 0xFF8E8E93, SOFT = 0xCCFFFFFF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // Один общий HTTP-клиент: проверка источников «прогревает» соединение, и плеер потом подключается быстрее
    private OkHttpClient http;
    private ExecutorService pool;
    private Updater updater;
    private static final String UPDATE_ROW = new String("update");
    private static final String BG_ROW = new String("bg");

    // Открытый экран — чтобы уведомление «Стоп» и напоминания могли до него достучаться
    private static WeakReference<MainActivity> live = new WeakReference<>(null);
    private boolean resumed;

    private DefaultTrackSelector trackSelector;
    private boolean keptInBackground;   // ушли из приложения, а звук продолжает играть (телефон)
    private boolean stoppedByUser;      // нажали «Стоп» в уведомлении
    private boolean inPip;
    private long pipExitAt;
    private Set<String> favorites = new HashSet<>();
    private FrameLayout root, sheet;
    private String reminderChannel;     // плашка «Скоро начнётся» — OK/нажатие переключит на этот канал
    private String updateNote = "Сейчас версия " + BuildConfig.VERSION_NAME;

    private List<Channels.Channel> channels = new ArrayList<>();
    private Bitmap logoSprite;
    private final Map<Integer, Bitmap> logoCache = new HashMap<>();

    // Телепрограмма: канал → [начало, конец] в секундах и названия передач
    private Map<String, long[][]> epgTimes = new HashMap<>();
    private Map<String, String[]> epgTitles = new HashMap<>();
    private long clockFix; // поправка, если часы телевизора врут (берём время с сервера)
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private ExoPlayer player;
    private DefaultBandwidthMeter meter;
    private ExecutorService probePool;
    private PlayerView playerView;
    private FrameLayout splashBox;
    private VideoView splash;
    private LinearLayout panel, infoBar;
    private ListView list;
    private ChannelAdapter adapter;
    private FrameLayout infoBadge;
    private TextView infoName, infoStatus, liveTag, infoNow, infoNext;
    private SourceWheel wheel;
    private LinearLayout wheelBox;
    private TextView wheelCaption;
    private boolean isTv = true;       // телевизор или телефон/планшет — от этого зависит управление
    private boolean wheelFocus;        // в меню выбраны кружки источников (а не список каналов)
    private GestureDetector gestures;
    private boolean touchOutside;
    private ProgressBar infoProgress;

    private boolean splashDone;
    private boolean preparedHidden;     // канал готовится под заставкой: на паузе и без звука
    private boolean splashKeyDown;      // во время заставки нажали кнопку пульта именно в приложении
    private int current = -1, currentSrc = 0, pendingZap = -1, pendingSrc = -1;
    private int attempt = 0;
    private boolean played, firstFrame, retried;

    // Очередь источников текущего канала: идём по ней, а когда кончилась — начинаем новый круг
    private final List<Integer> order = new ArrayList<>();
    private int pos, round;
    private final Map<String, Long> probeMs = new HashMap<>(); // url → время ответа, -1 = не отвечает
    private final Map<String, Long> probeAt = new HashMap<>(); // url → когда проверяли: результат годен 2 минуты
    private final Set<String> probing = new HashSet<>();
    private static final long PROBE_TTL = 2 * 60 * 1000L;
    private long sessionBw;            // скорость, которую плеер замерил в этом сеансе
    private boolean hdNow;             // сейчас разрешено 1080p
    private int droppedHd;             // сколько кадров пропущено на 1080p
    private Runnable srcTimer;         // «источник не ответил»
    private int srcTimerFor = -1;
    private long backPressedAt;
    // статистика для режима разработчика: анонимный id устройства и минуты просмотра
    private static final String STATS_EV = "https://rustv.bvrhockey.workers.dev/ev";
    private String devId;
    private String watchCh;
    private long watchFrom;

    // ---------- запуск ----------

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("rustv", MODE_PRIVATE);
        devId = prefs.getString("dev_id", null);
        if (devId == null) {
            devId = java.util.UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString("dev_id", devId).apply();
        }
        UiModeManager um = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        isTv = (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        http = new OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(8, 2, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true).followRedirects(true).followSslRedirects(true)
                .dns(MainActivity::ipv4First)
                .build();
        pool = Executors.newFixedThreadPool(4);
        probePool = Executors.newFixedThreadPool(3); // не забиваем Wi-Fi проверками, пока стартует видео
        updater = new Updater(this, http, pool, () -> { if (player != null) player.stop(); }, note -> {
            updateNote = note;
            adapter.notifyDataSetChanged();
        });
        favorites = new HashSet<>(prefs.getStringSet("favorites", new HashSet<>()));
        live = new WeakReference<>(this);
        buildUi();
        loadLocalData();
        refreshFromSite();
        // Пока идёт заставка, уже проверяем источники последнего канала — сеть работает, а видеодекодер свободен
        int last = indexOf(prefs.getString("last", null));
        probe(last >= 0 ? last : 0);
        if (saved == null) startSplash(); else hideSplash();
        openFromIntent(getIntent());
    }

    /** Открыли из уведомления-напоминания — включаем этот канал (сразу или после заставки). */
    private void openFromIntent(Intent i) {
        String ch = i == null ? null : i.getStringExtra(Reminders.EXTRA_CHANNEL);
        if (ch == null) return;
        i.removeExtra(Reminders.EXTRA_CHANNEL);
        int ci = indexOf(ch);
        if (ci < 0) return;
        prefs.edit().putString("last", ch).apply();
        if (player != null && (splashDone || prepareUnderSplash())) startChannel(ci);
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideSystemBars();
        boolean fresh = player == null;
        if (fresh) { createPlayer(); sendEv("launch", null, 0); News.start(this); } // пуши на телефоне (на ТВ — ничего)
        if (keptInBackground && !fresh) {
            // вернулись, а звук всё это время играл — просто возвращаем картинку
            keptInBackground = false;
            setVideoEnabled(true);
            if (stoppedByUser) { stoppedByUser = false; startLastOrCurrent(); }
        } else if (splashDone || prepareUnderSplash()) startLastOrCurrent(); // под заставкой канал готовится невидимо
        ui.removeCallbacks(epgTimer);
        ui.removeCallbacks(updateTimer);
        ui.post(epgTimer);
        ui.postDelayed(updateTimer, 20000); // обновление проверяем, когда канал уже включился
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        live = new WeakReference<>(this);
        updater.onResume();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && Updater.ACTION_INSTALL.equals(intent.getAction())) updater.onInstallResult(intent);
        else openFromIntent(intent);
    }

    // Новые версии ищем при запуске и потом раз в 6 часов
    private final Runnable updateTimer = new Runnable() {
        @Override public void run() {
            updater.check();
            ui.postDelayed(this, 6 * 60 * 60 * 1000L);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Закрыли маленькое окно (картинку в картинке) — значит, смотреть больше не хотят: останавливаем
        boolean closedPip = inPip || System.currentTimeMillis() - pipExitAt < 1500;
        inPip = false;
        // Телефон, включён «Звук в фоне» и канал играет — не останавливаем, только выключаем картинку
        if (!closedPip && !isTv && bgAudio() && player != null && played && player.getPlayWhenReady() && !isFinishing()) {
            keptInBackground = true;
            setVideoEnabled(false);
            hidePanel();
            hideSheet();
            return;
        }
        keptInBackground = false;
        watchFlush();
        PlaybackService.hide(this);
        // Ушли с приложения (кнопка «Домой») — освобождаем видеодекодер: слабому телевизору он нужен другим.
        attempt++;
        ui.removeCallbacksAndMessages(null);
        if (meter != null && sessionBw > 0) {
            String k = bwKey();
            prefs.edit().putLong(k, meter.getBitrateEstimate()).putLong(k + "_at", System.currentTimeMillis()).apply();
        }
        if (player != null) { player.release(); player = null; }
        if (splash != null && splash.isPlaying()) splash.stopPlayback();
        if (!splashDone) hideSplash();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PlaybackService.hide(this);
        if (player != null) { player.release(); player = null; }
        pool.shutdownNow();
        probePool.shutdownNow();
    }

    // Картинка в картинке: вышел на главный экран телефона — канал продолжает идти в маленьком окне
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isTv || Build.VERSION.SDK_INT < 26 || player == null || !played || !player.getPlayWhenReady()) return;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build());
        } catch (Exception ignored) {}
    }

    @Override
    public void onPictureInPictureModeChanged(boolean pip, Configuration cfg) {
        super.onPictureInPictureModeChanged(pip, cfg);
        inPip = pip;
        if (pip) { hidePanel(); hideSheet(); hideInfo(); } else pipExitAt = System.currentTimeMillis();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ---------- заставка ----------

    private final Runnable splashNoStart = this::hideSplash;  // видео заставки так и не подготовилось
    private final Runnable splashSafety = this::hideSplash;   // заставка пошла, но почему-то не кончается
    private int splashPos;
    private long splashMoved;

    private void startSplash() {
        splash.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.splash));
        splash.setOnPreparedListener(mp -> {
            // страховка отсчитывается с момента, когда заставка реально пошла
            ui.removeCallbacks(splashNoStart);
            ui.postDelayed(splashSafety, 9000);
            splashPos = -1;
            splashMoved = System.currentTimeMillis();
            ui.postDelayed(splashWatch, 1000);
        });
        splash.setOnCompletionListener(mp -> hideSplash());
        splash.setOnErrorListener((mp, w, e) -> { splashTrouble(); return true; });
        splash.start();
        ui.postDelayed(splashNoStart, 15000); // не подготовилась за 15 с — не держим чёрный экран
    }

    /** Можно ли готовить канал под заставкой. На слабых ТВ декодера на два видео может не хватить — тогда по очереди. */
    private boolean prepareUnderSplash() { return !prefs.getBoolean("splash_seq", false); }

    /** Заставка сломалась или застыла, пока под ней готовился канал: запоминаем — дальше сначала заставка, потом канал. */
    private void splashTrouble() {
        if (preparedHidden) prefs.edit().putBoolean("splash_seq", true).apply();
        hideSplash();
    }

    // Сторож заставки: картинка стоит больше 2 секунд — заставка перестала играть
    private final Runnable splashWatch = new Runnable() {
        @Override public void run() {
            if (splashDone) return;
            int p = splash.getCurrentPosition();
            long now = System.currentTimeMillis();
            if (p != splashPos) { splashPos = p; splashMoved = now; }
            else if (now - splashMoved > 2000) { splashTrouble(); return; }
            ui.postDelayed(this, 500);
        }
    };

    private void hideSplash() {
        if (splashDone) return;
        splashDone = true;
        ui.removeCallbacks(splashNoStart);
        ui.removeCallbacks(splashSafety);
        ui.removeCallbacks(splashWatch);
        try { splash.stopPlayback(); } catch (Exception ignored) {}
        // видео заставки лежит поверх канала — убираем его сразу, а чёрный фон растворяем, открывая канал
        splash.setVisibility(View.INVISIBLE);
        splashBox.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            splashBox.setVisibility(View.GONE);
            ((ViewGroup) splashBox.getParent()).removeView(splashBox);
        }).start();
        if (player == null) { preparedHidden = false; return; }
        if (preparedHidden) revealChannel(); else startLastOrCurrent();
    }

    /** Заставка кончилась — включаем канал, который готовился под ней. Не успел загрузиться — обычная загрузка. */
    private void revealChannel() {
        preparedHidden = false;
        player.setVolume(1f);
        player.setPlayWhenReady(true);
        if (played || current < 0) return;
        if (player.getPlaybackState() != Player.STATE_READY) showInfo(0);
        // ожидание источника считаем с этого момента: пауза под заставкой — не поломка источника
        if (srcTimerFor == attempt) armSourceTimer(attempt);
    }

    /** Под заставкой не хватило декодера на канал: запоминаем для устройства и ждём конца заставки, как раньше. */
    private void splashSequential() {
        prefs.edit().putBoolean("splash_seq", true).apply();
        preparedHidden = false;
        attempt++;
        if (srcTimer != null) ui.removeCallbacks(srcTimer);
        if (player != null) player.stop();
    }

    // ---------- плеер ----------

    private void createPlayer() {
        // Как у YouTube: качество повышаем, только когда скорость стабильно держится 10 секунд,
        // а при просадке сразу опускаем — лучше чуть мыльнее, чем крутилка
        AdaptiveTrackSelection.Factory abr = new AdaptiveTrackSelection.Factory(
                10000, 25000, 25000, 0.6f);
        DefaultTrackSelector ts = new DefaultTrackSelector(this, abr);
        trackSelector = ts;
        // Слабому телевизору и Wi-Fi 2.4 ГГц хватает 720p: картинка та же, а нагрузки и рывков меньше
        ts.setParameters(ts.buildUponParameters().setMaxVideoSize(1280, 720).setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true));
        // Начинаем с умеренного качества — картинка появляется быстрее, дальше плеер сам поднимет качество
        // Стартовое качество — по скорости, которая была у тебя в прошлый раз (а не наугад)
        sessionBw = 0;
        long known = freshBw();
        if (known <= 0) known = 800_000L;
        meter = new DefaultBandwidthMeter.Builder(this)
                .setInitialBitrateEstimate(Math.max(500_000L, Math.min(known * 7 / 10, 4_000_000L))).build();
        meter.addEventListener(ui, (ms, bytes, bitrate) -> sessionBw = bitrate);
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                // старт через 2 с запаса; после рывка ждём 5 с, чтобы не было «рывок-рывок-рывок»
                .setBufferDurationsMs(30000, 60000, 2000, 5000)
                .setPrioritizeTimeOverSizeThresholds(true).build();
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setTrackSelector(ts).setLoadControl(lc).setBandwidthMeter(meter).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        if (!isTv) player.setWakeMode(C.WAKE_MODE_NETWORK); // звук не обрывается при выключенном экране
        // Страховка: если на 1080p устройство пропускает кадры (слабый процессор), возвращаем 720p и запоминаем
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onDroppedVideoFrames(EventTime t, int dropped, long elapsedMs) {
                if (!hdNow || player == null) return;
                Format f = player.getVideoFormat();
                if (f == null || f.height <= 720) return;
                droppedHd += dropped;
                if (droppedHd >= 60) {
                    prefs.edit().putBoolean("no1080", true).apply();
                    applyVideoCap();
                }
            }
        });
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) onPlaying();
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING && played) watchStall(attempt);
                if (state == Player.STATE_READY) ui.removeCallbacks(stallCheck);
                if (state == Player.STATE_ENDED) fail(attempt);
            }
            @Override public void onPlayerError(PlaybackException error) {
                // Под заставкой канал не смог занять декодер — это не поломка источника, а нехватка декодеров на два видео
                if (preparedHidden && !splashDone && (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED)) {
                    splashSequential();
                    return;
                }
                // Отстали от прямого эфира — это не поломка источника, просто прыгаем к эфиру
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                fail(attempt);
            }
            @Override public void onRenderedFirstFrame() { firstFrame = true; }
        });
        playerView.setPlayer(player);
    }

    /** В фоне картинка не нужна: не качаем и не декодируем видео — меньше трафика и батареи. */
    private void setVideoEnabled(boolean on) {
        if (trackSelector == null) return;
        trackSelector.setParameters(trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !on));
    }

    private boolean bgAudio() { return prefs.getBoolean("bg_audio", true); }

    /** Свежий замер скорости (бит/с) или 0. В этом сеансе — замер плеера; сохранённый годен на телефоне час, на ТВ сутки. */
    private long freshBw() {
        if (sessionBw > 0) return sessionBw;
        String k = bwKey();
        // ТВ стоит в одной сети — помним неделю; на телефоне Wi-Fi помним сутки, мобильный интернет — час
        long ttl = isTv ? 7 * 24 * 3600_000L : k.equals("bw_cell") ? 3600_000L : 24 * 3600_000L;
        long at = prefs.getLong(k + "_at", 0);
        if (at == 0 && k.equals("bw_wifi")) { k = "bw"; at = prefs.getLong("bw_at", 0); } // замер из прошлой версии
        return at > 0 && System.currentTimeMillis() - at < ttl ? prefs.getLong(k, 0) : 0;
    }

    /** Скорость запоминаем отдельно для Wi-Fi/кабеля и для мобильного интернета — как у KION. */
    private String bwKey() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= 23) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "bw_cell";
            } else if (cm != null) {
                //noinspection deprecation
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                //noinspection deprecation
                if (ni != null && ni.getType() == ConnectivityManager.TYPE_MOBILE) return "bw_cell";
            }
        } catch (Exception ignored) {}
        return "bw_wifi";
    }

    /** 1080p — только при свежем замере от 6 Мбит/с, иначе 720p: слабому ТВ и Wi-Fi 2.4 ГГц так легче. */
    private void applyVideoCap() {
        if (trackSelector == null) return;
        // устройство уже показало, что 1080p ему тяжело (пропускало кадры), — дальше только 720p
        boolean hd = !prefs.getBoolean("no1080", false) && freshBw() >= 6_000_000L;
        hdNow = hd;
        droppedHd = 0;
        trackSelector.setParameters(trackSelector.buildUponParameters().setMaxVideoSize(hd ? 1920 : 1280, hd ? 1080 : 720));
    }

    // ---------- статистика (режим разработчика) ----------

    /** Минуты просмотра текущего канала — на сервер, если смотрели хотя бы 10 секунд. */
    private void watchFlush() {
        if (watchCh != null && watchFrom > 0) {
            long sec = (System.currentTimeMillis() - watchFrom) / 1000;
            if (sec >= 10) sendEv("watch", watchCh, sec);
        }
        watchCh = null;
        watchFrom = 0;
    }

    /** Событие на сервер в фоне. Нет сети или сервер молчит — просто пропускаем. */
    private void sendEv(String kind, String ch, long sec) {
        if (pool == null || devId == null) return;
        try {
            JSONObject o = new JSONObject().put("kind", kind).put("dev", devId);
            if (ch != null) o.put("ch", ch);
            if (sec > 0) o.put("sec", sec);
            final byte[] body = o.toString().getBytes(StandardCharsets.UTF_8);
            pool.execute(() -> {
                java.net.HttpURLConnection c = null;
                try {
                    c = (java.net.HttpURLConnection) new java.net.URL(STATS_EV).openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    c.setRequestMethod("POST"); c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.getOutputStream().write(body);
                    c.getResponseCode();
                } catch (Exception ignored) {
                } finally { if (c != null) c.disconnect(); }
            });
        } catch (Exception ignored) {}
    }

    /** Кнопка «Стоп» в уведомлении. */
    static void stopFromNotification() {
        MainActivity a = live.get();
        if (a == null || a.player == null) return;
        a.stoppedByUser = true;
        a.attempt++;
        a.ui.removeCallbacksAndMessages(null);
        a.player.stop();
    }

    private HlsMediaSource source(String url) {
        OkHttpDataSource.Factory ds = new OkHttpDataSource.Factory(http).setUserAgent(UA);
        // Если запас кончается, плеер незаметно замедляется на 3% и копит его — вместо остановки
        MediaItem item = new MediaItem.Builder().setUri(url)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1f).build())
                .build();
        return new HlsMediaSource.Factory(ds).setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(6))
                .createMediaSource(item);
    }

    private void startLastOrCurrent() {
        if (channels.isEmpty()) return;
        int ci = current;
        if (ci < 0) ci = indexOf(prefs.getString("last", null));
        if (ci < 0) ci = 0;
        startChannel(ci);
    }

    private void startChannel(int ci) {
        watchFlush();
        Channels.Channel ch = channels.get(ci);
        ui.removeCallbacks(pickRun);
        pendingSrc = -1;
        int saved = prefs.getInt("src:" + ch.name, 0);
        if (saved >= ch.sources.size()) saved = 0;
        current = ci;
        round = 1;
        applyVideoCap();
        buildOrder(ci, saved);
        probe(ci);
        playAt(false);
    }

    /** Сначала тот, что работал в прошлый раз, потом отвечающие (быстрые раньше), потом непроверенные, потом молчащие. */
    private void buildOrder(int ci, int first) {
        Channels.Channel ch = channels.get(ci);
        order.clear();
        for (int i = 0; i < ch.sources.size(); i++) order.add(i);
        Collections.swap(order, 0, order.indexOf(first));
        sortRest(ch, 1);
        pos = 0;
    }

    private void sortRest(Channels.Channel ch, int from) {
        if (from >= order.size()) return;
        List<Integer> rest = new ArrayList<>(order.subList(from, order.size()));
        Collections.sort(rest, (a, b) -> Long.compare(rank(ch, a), rank(ch, b)));
        for (int i = 0; i < rest.size(); i++) order.set(from + i, rest.get(i));
    }

    private long rank(Channels.Channel ch, int si) {
        String u = ch.sources.get(si);
        Long ms = probeMs.get(u);
        long base = ms == null ? 6000 : ms < 0 ? 60000 : ms;
        return base + 3000L * fails(u) - 800L * Math.min(oks(u), 5) + si;
    }

    private String hkey(String u) { return Integer.toHexString(u.hashCode()); }
    private int oks(String u) { return prefs.getInt("ok:" + hkey(u), 0); }
    private int fails(String u) { return prefs.getInt("bad:" + hkey(u), 0); }
    private void score(String u, boolean good) {
        int ok = oks(u), bad = fails(u);
        if (good) { ok = Math.min(ok + 1, 20); bad = Math.max(bad - 2, 0); } else bad = Math.min(bad + 1, 5);
        prefs.edit().putInt("ok:" + hkey(u), ok).putInt("bad:" + hkey(u), bad).apply();
    }

    /** Параллельно проверяем все источники канала: отвечает ли и как быстро. */
    private void probe(int ci) {
        if (ci < 0 || ci >= channels.size()) return;
        final Channels.Channel ch = channels.get(ci);
        long now = System.currentTimeMillis();
        for (final String url : ch.sources) {
            Long at = probeAt.get(url);
            if (probing.contains(url) || (at != null && now - at < PROBE_TTL)) continue; // уже проверяется или проверен недавно
            probing.add(url);
            probePool.execute(() -> {
                long t0 = System.currentTimeMillis();
                long ms = -1;
                OkHttpClient c = http.newBuilder()
                        .callTimeout(url.startsWith(PROXY) ? 12 : 8, TimeUnit.SECONDS).build();
                try (Response r = c.newCall(new Request.Builder().url(url).header("User-Agent", UA).build()).execute()) {
                    ResponseBody body = r.body();
                    if (r.isSuccessful() && body != null) {
                        byte[] head = new byte[2048];
                        int n = body.byteStream().read(head);
                        if (n > 0 && new String(head, 0, n, StandardCharsets.UTF_8).contains("#EXTM3U"))
                            ms = System.currentTimeMillis() - t0;
                    }
                } catch (Exception ignored) {}
                final long result = ms;
                ui.post(() -> onProbe(ch, url, result));
            });
        }
    }

    private void onProbe(Channels.Channel ch, String url, long ms) {
        probing.remove(url);
        probeMs.put(url, ms);
        probeAt.put(url, System.currentTimeMillis());
        if (current < 0 || current >= channels.size() || channels.get(current) != ch || played) return;
        sortRest(ch, pos + 1);
        updateDots();
    }

    private void playAt(boolean isRetry) {
        if (player == null || current < 0) return;
        Channels.Channel ch = channels.get(current);
        int si = order.get(pos);
        final int my = ++attempt;
        currentSrc = si; retried = isRetry;
        played = false; firstFrame = false;
        ui.removeCallbacks(stallCheck);
        adapter.notifyDataSetChanged();
        showInfo(0);
        setStatus("Подключаюсь к источнику " + (si + 1) + " из " + ch.sources.size()
                + (round > 1 ? " · круг " + round : "") + "…", MUTED, false);

        updateDots();
        // Пока идёт заставка, канал готовится невидимо и беззвучно — включится, когда она кончится
        preparedHidden = !splashDone;
        player.stop();
        player.setMediaSource(source(ch.sources.get(si)));
        player.prepare();
        player.setVolume(preparedHidden ? 0f : 1f);
        player.setPlayWhenReady(!preparedHidden);
        armSourceTimer(my);
    }

    private void armSourceTimer(final int my) {
        if (srcTimer != null) ui.removeCallbacks(srcTimer);
        srcTimerFor = my;
        long wait = channels.get(current).viaProxy(currentSrc) ? 25000 : 15000; // через прокси поток раскачивается дольше
        srcTimer = () -> {
            if (my != attempt || played) return;
            // под заставкой канал на паузе нарочно; раз он уже готов — источник в порядке, ждём конца заставки
            if (!splashDone && player != null && player.getPlaybackState() == Player.STATE_READY) return;
            if (!retried && firstFrame) {
                setStatus("Источник " + (currentSrc + 1) + " медленный, перезапускаю…", MUTED, false);
                playAt(true);
            } else fail(my);
        };
        ui.postDelayed(srcTimer, wait);
    }

    private void onPlaying() {
        if (played || current < 0) return;
        played = true;
        Channels.Channel ch = channels.get(current);
        probeMs.put(ch.sources.get(currentSrc), 0L);
        score(ch.sources.get(currentSrc), true);
        prefs.edit().putInt("src:" + ch.name, currentSrc).putString("last", ch.name).apply();
        if (!ch.name.equals(watchCh)) { watchFlush(); watchCh = ch.name; }
        if (watchFrom == 0) watchFrom = System.currentTimeMillis();
        setStatus("Идёт · источник " + (currentSrc + 1) + " из " + ch.sources.size(), OK, true);
        if (!isTv && bgAudio()) PlaybackService.show(this, ch.name);
        // Пуши на телефоне: один раз спрашиваем разрешение на уведомления (Android 13+), когда канал уже пошёл
        if (!isTv && !prefs.getBoolean("news_asked", false)) { prefs.edit().putBoolean("news_asked", true).apply(); askNotifications(); }
        updateDots();
        showInfo(5000);
        ui.removeCallbacks(probeNeighbors);
        ui.postDelayed(probeNeighbors, 5000);
    }

    // Канал пошёл — через 5 с тихо проверяем источники соседних каналов (куда переключат пультом или свайпом)
    private final Runnable probeNeighbors = () -> {
        if (!played || current < 0 || channels.isEmpty()) return;
        int n = channels.size();
        probe((current + n - 1) % n);
        probe((current + 1) % n);
    };

    private void fail(int my) {
        if (my != attempt || current < 0) return;
        attempt++;
        Channels.Channel ch = channels.get(current);
        probeMs.put(ch.sources.get(currentSrc), -1L);
        score(ch.sources.get(currentSrc), false);
        setStatus("Источник " + (currentSrc + 1) + " не отвечает, пробую следующий…", BAD, false);
        updateDots();
        showInfo(0);
        nextSource(300);
    }

    /** Следующий источник по очереди. Кончились — новый круг: источник, который сейчас молчит, через минуту может заработать. */
    private void nextSource(long delay) {
        if (current < 0) return;
        Channels.Channel ch = channels.get(current);
        pos++;
        if (pos >= order.size()) {
            round++;
            if (round == 2) sendEv("fail", ch.name, 0); // ни один источник не пошёл — в статистику
            long pause = Math.min(3000L * (round - 1), 20000L);
            setStatus("Все источники пока молчат. Новая попытка через " + (pause / 1000) + " с (круг " + round + ")", BAD, false);
            showInfo(0);
            final int ci = current;
            final int my = ++attempt;
            if (player != null) player.stop();
            ui.postDelayed(() -> {
                if (my != attempt || current != ci) return;
                for (String u : ch.sources) { probeMs.remove(u); probeAt.remove(u); } // на новом круге проверяем всех заново
                buildOrder(ci, order.isEmpty() ? 0 : order.get(0));
                probe(ci);
                playAt(false);
            }, pause);
            return;
        }
        final int my = attempt;
        ui.postDelayed(() -> { if (my == attempt) playAt(false); }, delay);
    }

    // Сторож: если уже шедшее видео застряло в загрузке на 15 секунд — берём следующий источник
    private final Runnable stallCheck = new Runnable() {
        @Override public void run() {
            if (player == null || !played) return;
            if (player.getPlaybackState() == Player.STATE_BUFFERING) {
                score(channels.get(current).sources.get(currentSrc), false);
                probeMs.put(channels.get(current).sources.get(currentSrc), -1L);
                setStatus("Поток завис, переключаю…", BAD, false);
                showInfo(0);
                attempt++;
                nextSource(0);
            }
        }
    };

    private void watchStall(int my) {
        ui.removeCallbacks(stallCheck);
        if (my == attempt) ui.postDelayed(stallCheck, 15000);
    }

    // ---------- пульт ----------

    /*
     * Пульт.
     *  Смотрим ТВ: ▲▼◄► — переключение каналов, OK — меню.
     *  В меню: ▲▼ — по списку каналов, ► — к кружкам источников (там ▲▼ — выбор источника, OK — включить сразу),
     *          ◄ — обратно к списку, «Назад» — закрыть меню.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!splashDone) {
            // Отпускание кнопки, нажатой ещё до приложения (OK, которым его открыли), заставку не пропускает
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) splashKeyDown = true;
            else if (e.getAction() == KeyEvent.ACTION_UP && splashKeyDown) hideSplash();
            return true;
        }
        if (sheet != null) return super.dispatchKeyEvent(e); // окошко канала — обычная навигация по кнопкам
        if (reminderChannel != null && e.getAction() == KeyEvent.ACTION_DOWN && panel.getVisibility() != View.VISIBLE
                && (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            switchToReminder();
            return true;
        }
        if (panel.getVisibility() == View.VISIBLE) {
            int code = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (down) panelTimer();
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { if (down) setWheelFocus(true); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { if (down) setWheelFocus(false); return true; }
            if (wheelFocus) {
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_UP: if (down) pickSource(-1); return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN: if (down) pickSource(1); return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_NUMPAD_ENTER:
                        if (down && e.getRepeatCount() == 0) applyPickNow();
                        return true;
                }
            }
            if (code == KeyEvent.KEYCODE_MENU) { if (down) hidePanel(); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (panel.getVisibility() == View.VISIBLE) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                zap(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                zap(1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MENU:
                showPanel(); return true;
            case KeyEvent.KEYCODE_INFO:
                showInfo(5000); return true;
        }
        return super.onKeyDown(code, e);
    }

    /*
     * Телефон: свайп в любую сторону — переключение канала, нажатие — меню.
     * В меню кружок источника выбирается пальцем; нажатие мимо меню — закрыть.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (!splashDone) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) hideSplash();
            return true;
        }
        if (sheet != null) return super.dispatchTouchEvent(e);
        if (panel.getVisibility() == View.VISIBLE) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN)
                touchOutside = !inside(panel, e) && !inside(wheelBox, e);
            if (touchOutside) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) hidePanel();
                return true;
            }
            panelTimer();
            return super.dispatchTouchEvent(e);
        }
        if (gestures != null) gestures.onTouchEvent(e);
        return true;
    }

    private static boolean inside(View v, MotionEvent e) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        float x = e.getRawX(), y = e.getRawY();
        return x >= xy[0] && x < xy[0] + v.getWidth() && y >= xy[1] && y < xy[1] + v.getHeight();
    }

    private void setupGestures() {
        final float min = dp(56), minV = dp(300);
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (reminderChannel != null) switchToReminder(); else showPanel();
                return true;
            }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) > Math.abs(dy)) {
                    if (Math.abs(dx) < min || Math.abs(vx) < minV) return false;
                    zap(dx < 0 ? 1 : -1);          // влево — следующий, вправо — предыдущий
                } else {
                    if (Math.abs(dy) < min || Math.abs(vy) < minV) return false;
                    zap(dy < 0 ? 1 : -1);          // вверх — следующий, вниз — предыдущий
                }
                return true;
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (!splashDone) { hideSplash(); return; }
        if (sheet != null) { hideSheet(); return; }
        if (panel.getVisibility() == View.VISIBLE) { hidePanel(); return; }
        long now = System.currentTimeMillis();
        if (now - backPressedAt < 2500) { finish(); return; }
        backPressedAt = now;
        setStatus("Нажми «Назад» ещё раз, чтобы выйти", MUTED, false);
        showInfo(2500);
    }

    /** Листание каналов стрелками влево/вправо. Включаем, когда перестали жать. */
    private void zap(int dir) {
        if (channels.isEmpty()) return;
        reminderChannel = null;
        int from = pendingZap >= 0 ? pendingZap : Math.max(current, 0);
        pendingZap = (from + dir + channels.size()) % channels.size();
        ui.removeCallbacks(pickRun);
        pendingSrc = -1;
        fillInfo(pendingZap);
        setStatus("", MUTED, false);
        showInfo(0);
        ui.removeCallbacks(zapRun);
        ui.postDelayed(zapRun, 700);
    }

    private final Runnable zapRun = () -> {
        int ci = pendingZap;
        pendingZap = -1;
        if (ci >= 0) startChannel(ci);
    };

    /** Выбор источника в колесе (стрелками или пальцем). Включаем выбранный, когда перестали листать. */
    private void pickSource(int dir) {
        if (current < 0) return;
        Channels.Channel ch = channels.get(current);
        if (ch.sources.size() < 2) { updateDots(); return; }
        int from = pendingSrc >= 0 ? pendingSrc : currentSrc;
        pickSourceAt((from + dir + ch.sources.size()) % ch.sources.size());
    }

    private void pickSourceAt(int si) {
        if (current < 0) return;
        pendingSrc = si;
        updateDots();
        ui.removeCallbacks(pickRun);
        ui.postDelayed(pickRun, 700);
    }

    private void applyPickNow() {
        if (pendingSrc < 0) return;
        ui.removeCallbacks(pickRun);
        pickRun.run();
    }

    private void setWheelFocus(boolean f) {
        if (!f && pendingSrc >= 0) applyPickNow();
        wheelFocus = f;
        wheel.setFocusedLook(f);
        list.animate().alpha(f ? 0.55f : 1f).setDuration(150).start();
    }

    private final Runnable pickRun = () -> {
        int si = pendingSrc;
        pendingSrc = -1;
        if (si < 0 || current < 0) return;
        Channels.Channel ch = channels.get(current);
        // выбранный — первым, дальше по кругу; если он не пойдёт, приложение само возьмёт следующий
        order.clear();
        for (int i = 0; i < ch.sources.size(); i++) order.add((si + i) % ch.sources.size());
        pos = 0; round = 1;
        attempt++;
        playAt(false);
    };

    // ---------- экран ----------

    /*
     * Сначала IPv4, потом IPv6. На многих домашних Wi-Fi IPv6 «есть, но не работает»: телефон по мобильной сети
     * ходит по IPv4 и грузит быстро, а по Wi-Fi приложение сначала долго стучится по IPv6. Так этого не будет.
     */
    private static List<java.net.InetAddress> ipv4First(String host) throws java.net.UnknownHostException {
        List<java.net.InetAddress> all = okhttp3.Dns.SYSTEM.lookup(host), v4 = new ArrayList<>(), v6 = new ArrayList<>();
        for (java.net.InetAddress a : all) (a instanceof java.net.Inet4Address ? v4 : v6).add(a);
        v4.addAll(v6);
        return v4;
    }

    private float density() { return getResources().getDisplayMetrics().density; }

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setShutterBackgroundColor(Color.BLACK);
        playerView.setKeepScreenOn(true);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        // Нижняя плашка: какой канал, что сейчас идёт и что с подключением
        infoBar = new LinearLayout(this);
        infoBar.setOrientation(LinearLayout.HORIZONTAL);
        infoBar.setGravity(Gravity.CENTER_VERTICAL);
        infoBar.setPadding(dp(16), dp(12), dp(22), dp(12));
        infoBar.setBackground(new Glass(dp(22), density()));
        infoBadge = new FrameLayout(this);
        infoBar.addView(infoBadge, new LinearLayout.LayoutParams(dp(76), dp(48)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, 0, 0);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        infoName = text(24, Color.WHITE, true);
        nameRow.addView(infoName);
        liveTag = text(11, Color.WHITE, true);
        liveTag.setText("LIVE");
        liveTag.setPadding(dp(6), dp(1), dp(6), dp(1));
        liveTag.setBackground(round(BAD, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(10);
        nameRow.addView(liveTag, lp);
        col.addView(nameRow);
        infoNow = text(18, SOFT, false);
        col.addView(infoNow);
        infoProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        infoProgress.setMax(1000);
        infoProgress.setProgressDrawable(progressDrawable());
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(360), dp(3));
        pp.topMargin = dp(5); pp.bottomMargin = dp(4);
        col.addView(infoProgress, pp);
        infoNext = text(15, MUTED, false);
        col.addView(infoNext);
        infoStatus = text(15, MUTED, false);
        col.addView(infoStatus);
        infoBar.addView(col, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        ip.setMargins(dp(40), 0, dp(40), dp(32));
        infoBar.setVisibility(View.GONE);
        root.addView(infoBar, ip);

        // Колесо источников — справа по центру, появляется только вместе с меню
        wheelBox = new LinearLayout(this);
        wheelBox.setOrientation(LinearLayout.VERTICAL);
        wheelBox.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView wheelTitle = text(12, 0xB3FFFFFF, true);
        wheelTitle.setText(isTv ? "ИСТОЧНИК  ►" : "ИСТОЧНИК");
        wheelTitle.setGravity(Gravity.CENTER);
        wheelTitle.setLetterSpacing(0.08f);
        wheelBox.addView(wheelTitle);
        wheel = new SourceWheel(this);
        wheel.setListener(i -> { panelTimer(); pickSourceAt(i); });
        wheelBox.addView(wheel);
        wheelCaption = text(13, SOFT, false);
        wheelCaption.setGravity(Gravity.CENTER);
        wheelCaption.setPadding(dp(12), dp(5), dp(12), dp(5));
        wheelCaption.setBackground(new Glass(dp(14), density()));
        wheelBox.addView(wheelCaption, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        wl.rightMargin = dp(isTv ? 36 : 20);
        wheelBox.setVisibility(View.GONE);
        root.addView(wheelBox, wl);

        // Список каналов слева
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(new Glass(dp(28), density()));
        panel.setPadding(dp(16), dp(6), dp(16), dp(14));
        // Логотип с фиолетовым кружком-свечением, как в Telegram-боте
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_glow);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(-2, dp(76));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(logo, logoLp);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(2));
        list.setSelector(selector());
        list.setDrawSelectorOnTop(false);
        list.setVerticalScrollBarEnabled(false);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setOnItemClickListener((p, v, position, id) -> {
            Object item = adapter.getItem(position);
            if (item instanceof Integer) { startChannel((Integer) item); hidePanel(); }
            else if (item == UPDATE_ROW) { panelTimer(); updater.checkNow(); }
            else if (item == BG_ROW) {
                panelTimer();
                boolean on = !bgAudio();
                prefs.edit().putBoolean("bg_audio", on).apply();
                if (on) { askNotifications(); if (played && current >= 0) PlaybackService.show(this, channels.get(current).name); }
                else PlaybackService.hide(this);
                adapter.notifyDataSetChanged();
            }
        });
        // Долгое нажатие на канал (пальцем или OK на пульте) — избранное и напоминания о передачах
        list.setLongClickable(true);
        list.setOnItemLongClickListener((p, v, position, id) -> {
            Object item = adapter.getItem(position);
            if (!(item instanceof Integer)) return false;
            openSheet((Integer) item);
            return true;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { panelTimer(); }
            @Override public void onScroll(AbsListView v, int a, int b, int c) {}
        });
        adapter = new ChannelAdapter();
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        panel.addView(botCard());
        TextView hint = text(12, MUTED, false);
        hint.setText((isTv ? "▲▼◄► каналы  ·  OK меню  ·  ► источники  ·  удерживай OK — ★ и 🔔"
                : "Свайп — каналы  ·  нажатие — меню  ·  удерживай канал — ★ и 🔔")
                + "  ·  версия " + BuildConfig.VERSION_NAME);
        hint.setPadding(dp(8), dp(8), 0, 0);
        panel.addView(hint);
        panel.setVisibility(View.GONE);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(isTv ? 440 : 400), -1, Gravity.START);
        pl.setMargins(dp(16), dp(16), 0, dp(16));
        root.addView(panel, pl);
        if (!isTv) setupGestures();

        // Заставка поверх всего
        splashBox = new FrameLayout(this);
        splashBox.setBackgroundColor(Color.BLACK);
        splash = new VideoView(this);
        splash.setZOrderMediaOverlay(true); // видео заставки — поверх видео канала, который готовится под ней
        splashBox.addView(splash, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(splashBox, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    /** Карточка бота внизу списка: QR-код (навести камеру телефона) и @имя. На телевизоре ссылку не нажмёшь — а QR сканируется за секунду. */
    private View botCard() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(10), dp(10), dp(14), dp(10));
        c.setBackground(new Glass(dp(18), density()));
        ImageView qr = new ImageView(this);
        qr.setImageResource(R.drawable.bot_qr);
        qr.setBackground(round(Color.WHITE, 8));
        qr.setPadding(dp(4), dp(4), dp(4), dp(4));
        c.addView(qr, new LinearLayout.LayoutParams(dp(68), dp(68)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView a = text(13, MUTED, false);
        a.setText("rus tv в Telegram");
        TextView b = text(18, Color.WHITE, true);
        b.setText(BOT);
        TextView d = text(12, MUTED, false);
        d.setText("Наведи камеру телефона на код");
        t.addView(a); t.addView(b); t.addView(d);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(14);
        c.addView(t, tl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(8);
        c.setLayoutParams(cl);
        return c;
    }

    private LayerDrawable progressDrawable() {
        GradientDrawable bg = round(0x33FFFFFF, 2);
        ClipDrawable fg = new ClipDrawable(round(ACCENT, 2), Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{bg, fg});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        return ld;
    }

    private StateListDrawable selector() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, new Glass(dp(14), density()).strength(1.6f).glow(ACCENT));
        s.addState(new int[]{android.R.attr.state_selected}, new Glass(dp(14), density()).strength(1.6f).glow(ACCENT));
        s.addState(new int[]{android.R.attr.state_pressed}, new Glass(dp(14), density()).strength(1.6f));
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private void showPanel() {
        if (channels.isEmpty()) return;
        hideInfo();
        adapter.notifyDataSetChanged(); // свежие названия передач
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(150).start();
        wheelFocus = false;
        wheel.setFocusedLook(false);
        list.setAlpha(1f);
        updateDots();
        wheel.select(pendingSrc >= 0 ? pendingSrc : currentSrc, true);
        wheelBox.setVisibility(View.VISIBLE);
        wheelBox.setAlpha(0f);
        wheelBox.setTranslationX(dp(24));
        wheelBox.animate().alpha(1f).translationX(0).setDuration(200).start();
        int position = adapter.positionOf(Math.max(current, 0));
        list.requestFocus();
        list.setSelectionFromTop(position, dp(120));
        panelTimer();
    }

    private void hidePanel() {
        ui.removeCallbacks(hidePanelRun);
        if (pendingSrc >= 0) applyPickNow();
        wheelFocus = false;
        panel.setVisibility(View.GONE);
        wheelBox.setVisibility(View.GONE);
        playerView.requestFocus();
    }

    private final Runnable hidePanelRun = this::hidePanel;
    private void panelTimer() { ui.removeCallbacks(hidePanelRun); ui.postDelayed(hidePanelRun, 15000); }

    private final Runnable hideInfoRun = this::hideInfo;
    private void hideInfo() {
        infoBar.setVisibility(View.GONE);
    }

    /** ms = 0 — держать, пока не придёт новое событие. */
    private void showInfo(long ms) {
        if (!splashDone || panel.getVisibility() == View.VISIBLE) return;
        if (pendingZap < 0 && current >= 0) fillInfo(current);
        infoBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideInfoRun);
        if (ms > 0) ui.postDelayed(hideInfoRun, ms);
    }

    private void fillInfo(int ci) {
        Channels.Channel ch = channels.get(ci);
        infoName.setText(ch.name);
        infoBadge.removeAllViews();
        infoBadge.addView(badge(ch, 15), new FrameLayout.LayoutParams(-1, -1));
        long now = now();
        int i = progIndex(ch.name, now);
        long[][] t = epgTimes.get(ch.name);
        String[] titles = epgTitles.get(ch.name);
        if (i >= 0) {
            infoNow.setText(hhmm.format(new Date(t[i][0] * 1000)) + "  " + titles[i]);
            infoNow.setVisibility(View.VISIBLE);
            long len = Math.max(1, t[i][1] - t[i][0]);
            infoProgress.setProgress((int) Math.min(1000, (now - t[i][0]) * 1000 / len));
            infoProgress.setVisibility(View.VISIBLE);
            if (i + 1 < t.length) {
                infoNext.setText("Далее " + hhmm.format(new Date(t[i + 1][0] * 1000)) + "  " + titles[i + 1]);
                infoNext.setVisibility(View.VISIBLE);
            } else infoNext.setVisibility(View.GONE);
        } else {
            infoNow.setVisibility(View.GONE);
            infoProgress.setVisibility(View.GONE);
            infoNext.setVisibility(View.GONE);
        }
    }

    /** Колесо источников: выбранный в центре, статус точкой (зелёная — работает, красная — не отвечает). */
    private void updateDots() {
        if (current < 0 || wheel == null) return;
        Channels.Channel ch = channels.get(current);
        int n = ch.sources.size();
        int[] st = new int[n];
        for (int i = 0; i < n; i++) {
            Long ms = probeMs.get(ch.sources.get(i));
            st[i] = ms == null ? SourceWheel.UNKNOWN : ms >= 0 ? SourceWheel.OK : SourceWheel.BAD;
        }
        int sel = pendingSrc >= 0 ? pendingSrc : currentSrc;
        boolean jump = wheel.getVisibility() != View.VISIBLE || wheelBox.getVisibility() != View.VISIBLE;
        wheel.setData(n, sel, played ? currentSrc : -1, st, jump);
        String cap;
        if (n < 2) cap = "Один источник";
        else if (pendingSrc >= 0 && pendingSrc != currentSrc) cap = "Источник " + (sel + 1) + " из " + n + (isTv ? " · нажми OK" : "");
        else cap = "Источник " + (sel + 1) + " из " + n + (played ? " · идёт" : " · подключаюсь");
        wheelCaption.setText(cap);
    }

    private void setStatus(String s, int color, boolean live) {
        infoStatus.setText(s);
        infoStatus.setTextColor(color);
        infoStatus.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        liveTag.setVisibility(live ? View.VISIBLE : View.GONE);
    }

    private View badge(Channels.Channel ch, float textSp) {
        Bitmap bm = logo(ch);
        if (bm != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bm);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(5), dp(4), dp(5), dp(4));
            iv.setBackground(round(ch.logoDark ? 0xFF2C2C2E : Color.WHITE, 9));
            return iv;
        }
        TextView t = text(ch.shortName.length() > 4 ? textSp * 0.7f : textSp, Color.WHITE, true);
        t.setText(ch.shortName.isEmpty() ? ch.name.substring(0, 1) : ch.shortName);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(ch.color, 9));
        return t;
    }

    private Bitmap logo(Channels.Channel ch) {
        if (logoSprite == null || ch.logoRow < 0) return null;
        Bitmap b = logoCache.get(ch.logoRow);
        if (b != null) return b;
        int w = logoSprite.getWidth(), h = w / 2, y = ch.logoRow * h;
        if (y + h > logoSprite.getHeight()) return null;
        b = Bitmap.createBitmap(logoSprite, 0, y, w, h);
        logoCache.put(ch.logoRow, b);
        return b;
    }

    // ---------- список каналов ----------

    private class ChannelAdapter extends BaseAdapter {
        private final List<Object> rows = new ArrayList<>(); // String — раздел, Integer — канал

        void rebuild() {
            rows.clear();
            boolean anyFav = false;
            for (int i = 0; i < channels.size(); i++) {
                if (!favorites.contains(channels.get(i).name)) continue;
                if (!anyFav) { rows.add("★ Избранное"); anyFav = true; }
                rows.add(i);
            }
            String sec = null;
            List<String> secs = new ArrayList<>();
            for (Channels.Channel c : channels) if (!secs.contains(c.sec)) secs.add(c.sec);
            for (String s : secs) {
                for (int i = 0; i < channels.size(); i++) {
                    if (!channels.get(i).sec.equals(s)) continue;
                    if (!s.equals(sec)) { rows.add(s); sec = s; }
                    rows.add(i);
                }
            }
            rows.add(UPDATE_ROW);
            if (!isTv) rows.add(BG_ROW);
            notifyDataSetChanged();
        }

        int positionOf(int ci) { int p = rows.indexOf(ci); return Math.max(p, 0); }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int p) { Object r = rows.get(p); return r == UPDATE_ROW || r == BG_ROW ? 2 : r instanceof String ? 0 : 1; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int p) { Object r = rows.get(p); return r instanceof Integer || r == UPDATE_ROW || r == BG_ROW; }

        @Override
        public View getView(int p, View convert, ViewGroup parent) {
            Object row = rows.get(p);
            if (row == UPDATE_ROW || row == BG_ROW) {
                LinearLayout r = convert instanceof LinearLayout ? (LinearLayout) convert : null;
                if (r == null) {
                    r = new LinearLayout(MainActivity.this);
                    r.setGravity(Gravity.CENTER_VERTICAL);
                    r.setPadding(dp(8), dp(12), dp(12), dp(12));
                    TextView icon = text(20, ACCENT, true);
                    icon.setText("⟳");
                    icon.setGravity(Gravity.CENTER);
                    r.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(40)));
                    LinearLayout texts = new LinearLayout(MainActivity.this);
                    texts.setOrientation(LinearLayout.VERTICAL);
                    texts.addView(text(19, Color.WHITE, false));
                    texts.addView(text(13, MUTED, false));
                    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f);
                    np.leftMargin = dp(14);
                    r.addView(texts, np);
                }
                LinearLayout texts = (LinearLayout) r.getChildAt(1);
                if (row == UPDATE_ROW) {
                    ((TextView) r.getChildAt(0)).setText("⟳");
                    ((TextView) texts.getChildAt(0)).setText("Проверить обновления");
                    ((TextView) texts.getChildAt(1)).setText(updateNote);
                } else {
                    boolean on = bgAudio();
                    ((TextView) r.getChildAt(0)).setText(on ? "🔊" : "🔈");
                    ((TextView) texts.getChildAt(0)).setText("Звук в фоне: " + (on ? "включён" : "выключен"));
                    ((TextView) texts.getChildAt(1)).setText(on ? "Экран выключен — канал всё равно слышно" : "Нажми, чтобы включить");
                }
                return r;
            }
            if (row instanceof String) {
                TextView t = convert instanceof TextView ? (TextView) convert : text(13, MUTED, false);
                t.setText(((String) row).toUpperCase());
                t.setPadding(dp(8), dp(p == 0 ? 4 : 18), 0, dp(6));
                return t;
            }
            int ci = (Integer) row;
            Channels.Channel ch = channels.get(ci);
            LinearLayout r;
            if (convert instanceof LinearLayout) r = (LinearLayout) convert;
            else {
                r = new LinearLayout(MainActivity.this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(8), dp(7), dp(12), dp(7));
                r.addView(new FrameLayout(MainActivity.this), new LinearLayout.LayoutParams(dp(64), dp(40)));
                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(text(19, Color.WHITE, false));
                texts.addView(text(13, MUTED, false));
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f);
                np.leftMargin = dp(14);
                r.addView(texts, np);
                View dot = new View(MainActivity.this);
                dot.setBackground(round(OK, 5));
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
                dl.leftMargin = dp(8);
                r.addView(dot, dl);
            }
            FrameLayout bf = (FrameLayout) r.getChildAt(0);
            bf.removeAllViews();
            bf.addView(badge(ch, 13), new FrameLayout.LayoutParams(-1, -1));
            LinearLayout texts = (LinearLayout) r.getChildAt(1);
            TextView name = (TextView) texts.getChildAt(0);
            TextView prog = (TextView) texts.getChildAt(1);
            name.setText(favorites.contains(ch.name) ? "★ " + ch.name : ch.name);
            boolean isCur = ci == current;
            name.setTextColor(isCur ? ACCENT : Color.WHITE);
            name.setTypeface(isCur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            int i = progIndex(ch.name, now());
            if (i >= 0) { prog.setText(epgTitles.get(ch.name)[i] + (hasReminder(ch.name) ? "  🔔" : "")); prog.setVisibility(View.VISIBLE); }
            else prog.setVisibility(View.GONE);
            r.getChildAt(2).setVisibility(isCur && played ? View.VISIBLE : View.INVISIBLE);
            return r;
        }
    }

    // ---------- избранное и напоминания ----------

    private boolean hasReminder(String channel) {
        for (String k : Reminders.all(this)) if (k.startsWith(channel + "|")) return true;
        return false;
    }

    private void toggleFavorite(String name) {
        if (!favorites.remove(name)) favorites.add(name);
        prefs.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        adapter.rebuild();
    }

    /** На Android 13+ уведомления нужно разрешить — спрашиваем, когда они впервые понадобились. */
    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
    }

    private TextView sheetButton(String label) {
        TextView b = text(17, Color.WHITE, false);
        b.setText(label);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setFocusable(true);
        b.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, new Glass(dp(14), density()).strength(1.6f).glow(ACCENT));
        bg.addState(new int[]{android.R.attr.state_pressed}, new Glass(dp(14), density()).strength(1.6f));
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        b.setBackground(bg);
        return b;
    }

    /** Окошко канала: в избранное и колокольчики на ближайшие передачи. */
    private void openSheet(int ci) {
        hideSheet();
        final Channels.Channel ch = channels.get(ci);
        panelTimer();
        ui.removeCallbacks(hidePanelRun); // пока открыто окошко, меню не прячем
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        box.setBackground(new Glass(dp(26), density()));
        TextView title = text(22, Color.WHITE, true);
        title.setText(ch.name);
        title.setPadding(dp(16), 0, 0, dp(8));
        box.addView(title);
        final TextView fav = sheetButton(favorites.contains(ch.name) ? "★  Убрать из избранного" : "☆  Добавить в избранное");
        fav.setOnClickListener(v -> {
            toggleFavorite(ch.name);
            fav.setText(favorites.contains(ch.name) ? "★  Убрать из избранного" : "☆  Добавить в избранное");
        });
        box.addView(fav);
        long[][] t = epgTimes.get(ch.name);
        String[] titles = epgTitles.get(ch.name);
        long now = now();
        int shown = 0;
        if (t != null) {
            TextView h = text(13, MUTED, false);
            h.setText("НАПОМНИТЬ ЗА 5 МИНУТ");
            h.setPadding(dp(16), dp(14), 0, dp(4));
            box.addView(h);
            for (int i = 0; i < t.length && shown < 7; i++) {
                if (t[i][0] <= now + Reminders.BEFORE_SEC) continue; // уже идёт или вот-вот начнётся
                final long start = t[i][0];
                final String pt = titles[i];
                final TextView row = sheetButton("");
                final Runnable upd = () -> row.setText((Reminders.has(this, ch.name, start) ? "🔔  " : "      ")
                        + hhmm.format(new Date(start * 1000)) + "   " + pt);
                upd.run();
                row.setOnClickListener(v -> {
                    boolean on = Reminders.toggle(this, ch.name, start, pt);
                    if (on) askNotifications();
                    upd.run();
                    adapter.notifyDataSetChanged();
                });
                box.addView(row);
                shown++;
            }
        }
        if (shown == 0) {
            TextView none = text(14, MUTED, false);
            none.setText("Программы передач для этого канала пока нет");
            none.setPadding(dp(16), dp(12), 0, dp(4));
            box.addView(none);
        }
        sheet = new FrameLayout(this);
        sheet.setBackgroundColor(0x80000000);
        sheet.setOnClickListener(v -> hideSheet());
        box.setClickable(true);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(520), -2, Gravity.CENTER);
        sheet.addView(box, bl);
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));
        fav.requestFocus();
    }

    private void hideSheet() {
        if (sheet == null) return;
        root.removeView(sheet);
        sheet = null;
        if (panel.getVisibility() == View.VISIBLE) { list.requestFocus(); panelTimer(); }
    }

    /** Напоминание сработало, а приложение открыто — показываем плашку (OK или нажатие — переключиться). */
    static boolean showReminder(String channel, String title) {
        MainActivity a = live.get();
        if (a == null || !a.resumed || a.isFinishing()) return false;
        a.ui.post(() -> {
            int ci = a.indexOf(channel);
            if (ci < 0) return;
            a.reminderChannel = channel;
            a.fillInfo(ci);
            a.setStatus("🔔 Через 5 минут: " + title + (a.isTv ? " · OK — смотреть" : " · нажми — смотреть"), Color.WHITE, false);
            a.infoBar.setVisibility(View.VISIBLE);
            a.ui.removeCallbacks(a.hideInfoRun);
            a.ui.removeCallbacks(a.clearReminder);
            a.ui.postDelayed(a.clearReminder, 20000);
        });
        return true;
    }

    private final Runnable clearReminder = () -> { reminderChannel = null; hideInfo(); };

    private void switchToReminder() {
        int ci = indexOf(reminderChannel);
        ui.removeCallbacks(clearReminder);
        reminderChannel = null;
        if (ci >= 0) startChannel(ci);
    }

    private int indexOf(String name) {
        if (name == null) return -1;
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).name.equals(name)) return i;
        return -1;
    }

    // ---------- телепрограмма ----------

    private long now() { return (System.currentTimeMillis() + clockFix) / 1000; }

    private int progIndex(String name, long now) {
        long[][] t = epgTimes.get(name);
        if (t == null) return -1;
        for (int i = 0; i < t.length; i++) if (t[i][0] <= now && now < t[i][1]) return i;
        return -1;
    }

    private void applyEpg(String json) {
        try {
            JSONObject ch = new JSONObject(json).getJSONObject("ch");
            Map<String, long[][]> times = new HashMap<>();
            Map<String, String[]> titles = new HashMap<>();
            for (Iterator<String> it = ch.keys(); it.hasNext(); ) {
                String name = it.next();
                JSONArray arr = ch.getJSONArray(name);
                long[][] t = new long[arr.length()][2];
                String[] s = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray p = arr.getJSONArray(i);
                    t[i][0] = p.getLong(0); t[i][1] = p.getLong(1); s[i] = p.getString(2);
                }
                times.put(name, t); titles.put(name, s);
            }
            epgTimes = times; epgTitles = titles;
            adapter.notifyDataSetChanged();
        } catch (Exception ignored) {}
    }

    // Программа обновляется раз в 3 часа (на сервере) — тянем её при запуске и потом раз в час
    private final Runnable epgTimer = new Runnable() {
        @Override public void run() {
            loadEpg();
            ui.postDelayed(this, 60 * 60 * 1000L);
        }
    };

    private void loadEpg() {
        pool.execute(() -> {
            String json = fetchText(EPG, true);
            if (json == null) json = fetchText(PROXY + Channels.encode(EPG), false);
            if (json == null || !json.contains("\"ch\"")) return;
            writeFile("epg.json", json.getBytes(StandardCharsets.UTF_8));
            final String j = json;
            ui.post(() -> {
                applyEpg(j);
                if (infoBar.getVisibility() == View.VISIBLE) showInfo(0);
            });
        });
    }

    // ---------- данные: сначала то, что есть на устройстве, потом свежее с сайта ----------

    private void loadLocalData() {
        String html = readFile("index.html");
        if (html == null || Channels.parse(html).size() < 5) html = readAsset("index.html");
        applyChannels(Channels.parse(html));
        byte[] sprite = readBytes(new File(getFilesDir(), "logos.webp"));
        if (sprite == null) sprite = readAssetBytes("logos.webp");
        if (sprite != null) logoSprite = BitmapFactory.decodeByteArray(sprite, 0, sprite.length);
        String epg = readFile("epg.json");
        if (epg != null) applyEpg(epg);
    }

    private void applyChannels(List<Channels.Channel> fresh) {
        if (fresh.size() < 5) return;
        String curName = current >= 0 && current < channels.size() ? channels.get(current).name : null;
        channels = fresh;
        int ci = indexOf(curName);
        if (curName != null && ci < 0) { current = -1; } else if (ci >= 0) current = ci;
        adapter.rebuild();
    }

    private void refreshFromSite() {
        pool.execute(() -> {
            String html = fetchText(SITE + "index.html?t=" + System.currentTimeMillis(), false);
            if (html == null || Channels.parse(html).size() < 5) html = fetchText(RAW + "index.html", false);
            if (html == null) return;
            final List<Channels.Channel> fresh = Channels.parse(html);
            if (fresh.size() < 5) return;
            String old = readFile("index.html");
            if (html.equals(old)) return;
            writeFile("index.html", html.getBytes(StandardCharsets.UTF_8));
            byte[] sprite = fetch(SITE + "logos.webp?t=" + System.currentTimeMillis(), false);
            if (sprite == null) sprite = fetch(RAW + "logos.webp", false);
            final Bitmap bm = sprite == null ? null : BitmapFactory.decodeByteArray(sprite, 0, sprite.length);
            if (bm != null) writeFile("logos.webp", sprite);
            ui.post(() -> {
                if (bm != null) { logoSprite = bm; logoCache.clear(); }
                // Обновляем тихо, не прерывая просмотр (текущий канал находим по названию)
                boolean wasEmpty = channels.isEmpty();
                applyChannels(fresh);
                if (current >= 0) {
                    // очередь источников могла поменяться — начнём заново со следующего переключения
                    Channels.Channel ch = channels.get(current);
                    if (currentSrc >= ch.sources.size()) currentSrc = 0;
                    order.clear();
                    for (int i = 0; i < ch.sources.size(); i++) order.add(i);
                    Collections.swap(order, 0, currentSrc);
                    pos = 0;
                }
                if (wasEmpty && (splashDone || prepareUnderSplash()) && player != null) startLastOrCurrent();
            });
        });
    }

    private String fetchText(String url, boolean syncClock) {
        byte[] b = fetch(url, syncClock);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private byte[] fetch(String url, boolean syncClock) {
        try (Response r = http.newCall(new Request.Builder().url(url).header("User-Agent", UA).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return null;
            if (syncClock) {
                Date server = r.headers().getDate("Date");
                if (server != null) {
                    long fix = server.getTime() - System.currentTimeMillis();
                    // часы на дешёвых ТВ бывают сбиты; поправляем, только если разница заметная
                    clockFix = Math.abs(fix) > 120_000 ? fix : 0;
                }
            }
            return r.body().bytes();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private String readAsset(String name) {
        byte[] b = readAssetBytes(name);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private byte[] readAssetBytes(String name) {
        try (InputStream in = getAssets().open(name)) { return readAll(in); } catch (Exception e) { return null; }
    }

    private String readFile(String name) {
        byte[] b = readBytes(new File(getFilesDir(), name));
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File f) {
        if (!f.exists()) return null;
        try (InputStream in = new java.io.FileInputStream(f)) { return readAll(in); } catch (Exception e) { return null; }
    }

    private synchronized void writeFile(String name, byte[] data) {
        File tmp = new File(getFilesDir(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        } catch (Exception e) { return; }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(getFilesDir(), name));
    }
}
