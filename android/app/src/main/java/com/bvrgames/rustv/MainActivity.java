package com.bvrgames.rustv;

import android.app.Activity;
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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
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
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
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

    private static final String SITE = "https://bvrgames.github.io/BVR_TV/";
    private static final String RAW = "https://raw.githubusercontent.com/bvrgames/BVR_TV/main/";
    private static final String EPG = "https://raw.githubusercontent.com/bvrgames/BVR_TV/epg/epg.json";
    private static final String PROXY = "https://rustv.bvrhockey.workers.dev/?u=";
    private static final String BOT = "Telegram · @rus_tv_bot";
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private static final int ACCENT = 0xFFBF5AF2, OK = 0xFF30D158, BAD = 0xFFFF453A, MUTED = 0xFF8E8E93, SOFT = 0xCCFFFFFF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // Один общий HTTP-клиент: проверка источников «прогревает» соединение, и плеер потом подключается быстрее
    private OkHttpClient http;
    private ExecutorService pool;

    private List<Channels.Channel> channels = new ArrayList<>();
    private Bitmap logoSprite;
    private final Map<Integer, Bitmap> logoCache = new HashMap<>();

    // Телепрограмма: канал → [начало, конец] в секундах и названия передач
    private Map<String, long[][]> epgTimes = new HashMap<>();
    private Map<String, String[]> epgTitles = new HashMap<>();
    private long clockFix; // поправка, если часы телевизора врут (берём время с сервера)
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private ExoPlayer player;
    private PlayerView playerView;
    private FrameLayout splashBox;
    private VideoView splash;
    private LinearLayout panel, infoBar;
    private ListView list;
    private ChannelAdapter adapter;
    private FrameLayout infoBadge;
    private TextView infoName, infoStatus, liveTag, infoNow, infoNext, footer;
    private ProgressBar infoProgress;

    private boolean splashDone;
    private int current = -1, currentSrc = 0, pendingZap = -1;
    private int attempt = 0;
    private boolean played, firstFrame, retried;

    // Очередь источников текущего канала: идём по ней, а когда кончилась — начинаем новый круг
    private final List<Integer> order = new ArrayList<>();
    private int pos, round;
    private final Map<String, Long> probeMs = new HashMap<>(); // url → время ответа, -1 = не отвечает
    private long backPressedAt;

    // ---------- запуск ----------

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("rustv", MODE_PRIVATE);
        http = new OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(8, 2, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true).followRedirects(true).followSslRedirects(true)
                .build();
        pool = Executors.newFixedThreadPool(6);
        buildUi();
        loadLocalData();
        refreshFromSite();
        // Пока идёт заставка, уже проверяем источники последнего канала — сеть работает, а видеодекодер свободен
        int last = indexOf(prefs.getString("last", null));
        probe(last >= 0 ? last : 0);
        if (saved == null) startSplash(); else hideSplash();
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideSystemBars();
        if (player == null) createPlayer();
        if (splashDone) startLastOrCurrent();
        ui.post(epgTimer);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Ушли с приложения (кнопка «Домой») — освобождаем видеодекодер: слабому телевизору он нужен другим.
        attempt++;
        ui.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        if (splash != null && splash.isPlaying()) splash.stopPlayback();
        if (!splashDone) hideSplash();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
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

    private void startSplash() {
        splash.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.splash));
        splash.setOnCompletionListener(mp -> hideSplash());
        splash.setOnErrorListener((mp, w, e) -> { hideSplash(); return true; });
        splash.start();
        ui.postDelayed(this::hideSplash, 9000); // страховка, если видео на этом устройстве не запустится
    }

    private void hideSplash() {
        if (splashDone) return;
        splashDone = true;
        try { splash.stopPlayback(); } catch (Exception ignored) {}
        splashBox.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            splashBox.setVisibility(View.GONE);
            ((ViewGroup) splashBox.getParent()).removeView(splashBox);
        }).start();
        if (player != null) startLastOrCurrent();
    }

    // ---------- плеер ----------

    private void createPlayer() {
        DefaultTrackSelector ts = new DefaultTrackSelector(this);
        // Слабому телевизору и Wi-Fi 2.4 ГГц хватает 720p: картинка та же, а нагрузки и рывков меньше
        ts.setParameters(ts.buildUponParameters().setMaxVideoSize(1280, 720).setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true));
        // Начинаем с умеренного качества — картинка появляется быстрее, дальше плеер сам поднимет качество
        DefaultBandwidthMeter meter = new DefaultBandwidthMeter.Builder(this).setInitialBitrateEstimate(1_500_000).build();
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20000, 50000, 1500, 3000).build();
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setTrackSelector(ts).setLoadControl(lc).setBandwidthMeter(meter).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
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

    private HlsMediaSource source(String url) {
        OkHttpDataSource.Factory ds = new OkHttpDataSource.Factory(http).setUserAgent(UA);
        // Скорость всегда 1×: без подгонки к эфиру слабому процессору не нужно пересчитывать звук
        MediaItem item = new MediaItem.Builder().setUri(url)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
                .build();
        return new HlsMediaSource.Factory(ds).setAllowChunklessPreparation(true).createMediaSource(item);
    }

    private void startLastOrCurrent() {
        if (channels.isEmpty()) return;
        int ci = current;
        if (ci < 0) ci = indexOf(prefs.getString("last", null));
        if (ci < 0) ci = 0;
        startChannel(ci);
    }

    private void startChannel(int ci) {
        Channels.Channel ch = channels.get(ci);
        int saved = prefs.getInt("src:" + ch.name, 0);
        if (saved >= ch.sources.size()) saved = 0;
        current = ci;
        round = 1;
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
        Long ms = probeMs.get(ch.sources.get(si));
        if (ms == null) return 100_000 + si;     // ещё не проверен
        if (ms < 0) return 1_000_000 + si;      // не ответил
        return ms;
    }

    /** Параллельно проверяем все источники канала: отвечает ли и как быстро. */
    private void probe(int ci) {
        if (ci < 0 || ci >= channels.size()) return;
        final Channels.Channel ch = channels.get(ci);
        for (final String url : ch.sources) {
            pool.execute(() -> {
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
        probeMs.put(url, ms);
        if (current < 0 || current >= channels.size() || channels.get(current) != ch || played) return;
        sortRest(ch, pos + 1);
        // Источник, к которому сейчас подключаемся, не отвечает — не ждём таймаута, сразу берём следующий
        if (ms < 0 && ch.sources.get(currentSrc).equals(url)) fail(attempt);
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

        player.stop();
        player.setMediaSource(source(ch.sources.get(si)));
        player.prepare();
        player.setPlayWhenReady(true);

        long wait = ch.viaProxy(si) ? 25000 : 15000; // через прокси поток раскачивается дольше
        ui.postDelayed(() -> {
            if (my != attempt || played) return;
            if (!retried && firstFrame) {
                setStatus("Источник " + (currentSrc + 1) + " медленный, перезапускаю…", MUTED, false);
                playAt(true);
            } else fail(my);
        }, wait);
    }

    private void onPlaying() {
        if (played || current < 0) return;
        played = true;
        Channels.Channel ch = channels.get(current);
        probeMs.put(ch.sources.get(currentSrc), 0L);
        prefs.edit().putInt("src:" + ch.name, currentSrc).putString("last", ch.name).apply();
        setStatus("Идёт · источник " + (currentSrc + 1) + " из " + ch.sources.size(), OK, true);
        showInfo(5000);
    }

    private void fail(int my) {
        if (my != attempt || current < 0) return;
        attempt++;
        Channels.Channel ch = channels.get(current);
        probeMs.put(ch.sources.get(currentSrc), -1L);
        setStatus("Источник " + (currentSrc + 1) + " не отвечает, пробую следующий…", BAD, false);
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
            long pause = Math.min(3000L * (round - 1), 20000L);
            setStatus("Все источники пока молчат. Новая попытка через " + (pause / 1000) + " с (круг " + round + ")", BAD, false);
            showInfo(0);
            final int ci = current;
            final int my = ++attempt;
            if (player != null) player.stop();
            ui.postDelayed(() -> {
                if (my != attempt || current != ci) return;
                for (String u : ch.sources) probeMs.remove(u); // на новом круге проверяем всех заново
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!splashDone) {
            if (e.getAction() == KeyEvent.ACTION_UP) hideSplash();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        boolean panelOpen = panel.getVisibility() == View.VISIBLE;
        if (panelOpen) {
            panelTimer();
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MENU:
                    hidePanel(); return true;
            }
            return super.onKeyDown(code, e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                zap(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                zap(1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MENU:
                showPanel(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                manualNext(); return true;
            case KeyEvent.KEYCODE_INFO:
                showInfo(5000); return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public void onBackPressed() {
        if (!splashDone) { hideSplash(); return; }
        if (panel.getVisibility() == View.VISIBLE) { hidePanel(); return; }
        long now = System.currentTimeMillis();
        if (now - backPressedAt < 2500) { finish(); return; }
        backPressedAt = now;
        setStatus("Нажми «Назад» ещё раз, чтобы выйти", MUTED, false);
        showInfo(2500);
    }

    /** Листание каналов стрелками вверх/вниз, как на обычном телевизоре. Включаем, когда перестали жать. */
    private void zap(int dir) {
        if (channels.isEmpty()) return;
        int from = pendingZap >= 0 ? pendingZap : Math.max(current, 0);
        pendingZap = (from + dir + channels.size()) % channels.size();
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

    private void manualNext() {
        if (current < 0) return;
        Channels.Channel ch = channels.get(current);
        if (ch.sources.size() < 2) { setStatus("У этого канала один источник", MUTED, played); showInfo(2500); return; }
        attempt++;
        nextSource(0);
    }

    // ---------- экран ----------

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
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setShutterBackgroundColor(Color.BLACK);
        playerView.setKeepScreenOn(true);
        playerView.setOnClickListener(v -> { if (panel.getVisibility() == View.VISIBLE) hidePanel(); else showPanel(); });
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        // Нижняя плашка: какой канал, что сейчас идёт и что с подключением
        infoBar = new LinearLayout(this);
        infoBar.setOrientation(LinearLayout.HORIZONTAL);
        infoBar.setGravity(Gravity.CENTER_VERTICAL);
        infoBar.setPadding(dp(16), dp(12), dp(22), dp(12));
        infoBar.setBackground(round(0xD91C1C1E, 16));
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

        // Список каналов слева
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF2000000, 0xE6000000, 0x00000000});
        panel.setBackground(shade);
        panel.setPadding(dp(28), dp(20), dp(60), dp(12));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(-2, dp(34));
        logoLp.leftMargin = dp(8);
        panel.addView(logo, logoLp);
        TextView bot = text(13, MUTED, false);
        bot.setText(BOT);
        bot.setPadding(dp(8), dp(4), 0, dp(10));
        panel.addView(bot);
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
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { panelTimer(); }
            @Override public void onScroll(AbsListView v, int a, int b, int c) {}
        });
        adapter = new ChannelAdapter();
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        footer = text(13, MUTED, false);
        footer.setSingleLine(false);
        footer.setPadding(dp(8), dp(8), 0, 0);
        panel.addView(footer);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(dp(440), -1, Gravity.START));

        // Заставка поверх всего
        splashBox = new FrameLayout(this);
        splashBox.setBackgroundColor(Color.BLACK);
        splash = new VideoView(this);
        splashBox.addView(splash, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(splashBox, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
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
        GradientDrawable on = round(0x40FFFFFF, 12);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_selected}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
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
        int position = adapter.positionOf(Math.max(current, 0));
        list.requestFocus();
        list.setSelectionFromTop(position, dp(120));
        panelTimer();
    }

    private void hidePanel() {
        ui.removeCallbacks(hidePanelRun);
        panel.setVisibility(View.GONE);
        playerView.requestFocus();
    }

    private final Runnable hidePanelRun = this::hidePanel;
    private void panelTimer() { ui.removeCallbacks(hidePanelRun); ui.postDelayed(hidePanelRun, 15000); }

    private final Runnable hideInfoRun = this::hideInfo;
    private void hideInfo() { infoBar.setVisibility(View.GONE); }

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
            notifyDataSetChanged();
        }

        int positionOf(int ci) { int p = rows.indexOf(ci); return Math.max(p, 0); }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return rows.get(p) instanceof String ? 0 : 1; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int p) { return rows.get(p) instanceof Integer; }

        @Override
        public View getView(int p, View convert, ViewGroup parent) {
            Object row = rows.get(p);
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
            name.setText(ch.name);
            boolean isCur = ci == current;
            name.setTextColor(isCur ? ACCENT : Color.WHITE);
            name.setTypeface(isCur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            int i = progIndex(ch.name, now());
            if (i >= 0) { prog.setText(epgTitles.get(ch.name)[i]); prog.setVisibility(View.VISIBLE); }
            else prog.setVisibility(View.GONE);
            r.getChildAt(2).setVisibility(isCur && played ? View.VISIBLE : View.INVISIBLE);
            return r;
        }
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
        footer.setText("версия " + BuildConfig.VERSION_NAME + "\n▲▼ каналы  ·  OK список  ·  ► другой источник");
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
                if (wasEmpty && splashDone && player != null) startLastOrCurrent();
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
