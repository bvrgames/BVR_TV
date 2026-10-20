package com.bvrgames.rustv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity {

    private static final String SITE = "https://bvrgames.github.io/BVR_TV/";
    private static final String RAW = "https://raw.githubusercontent.com/bvrgames/BVR_TV/main/";
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private static final int ACCENT = 0xFFBF5AF2, OK = 0xFF30D158, BAD = 0xFFFF453A, MUTED = 0xFF8E8E93;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private List<Channels.Channel> channels = new ArrayList<>();
    private Bitmap logoSprite;
    private final Map<Integer, Bitmap> logoCache = new HashMap<>();

    private ExoPlayer player;
    private PlayerView playerView;
    private FrameLayout splashBox;
    private VideoView splash;
    private LinearLayout panel, infoBar;
    private ListView list;
    private ChannelAdapter adapter;
    private FrameLayout infoBadge;
    private TextView infoName, infoStatus, liveTag, footer;

    private boolean splashDone;
    private int current = -1, currentSrc = 0, pendingZap = -1;
    private int attempt = 0;
    private boolean played, firstFrame, autoNext, retried;
    private int tried;
    private long backPressedAt;

    // ---------- запуск ----------

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("rustv", MODE_PRIVATE);
        buildUi();
        loadLocalData();
        refreshFromSite();
        if (saved == null) startSplash(); else hideSplash();
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideSystemBars();
        if (player == null) createPlayer();
        if (splashDone) startLastOrCurrent();
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
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20000, 50000, 2000, 4000).build();
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setTrackSelector(ts).setLoadControl(lc).build();
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
            @Override public void onPlayerError(PlaybackException error) { fail(attempt); }
            @Override public void onRenderedFirstFrame() { firstFrame = true; }
        });
        playerView.setPlayer(player);
    }

    private HlsMediaSource source(String url) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent(UA)
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10000).setReadTimeoutMs(15000);
        return new HlsMediaSource.Factory(http).setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(url));
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
        play(ci, saved < ch.sources.size() ? saved : 0, true, 0, false);
    }

    /** Как в веб-версии: пробуем источники по очереди, пока какой-то не пойдёт. */
    private void play(int ci, int si, boolean auto, int triedCount, boolean isRetry) {
        if (player == null) return;
        Channels.Channel ch = channels.get(ci);
        if (triedCount >= ch.sources.size()) {
            setStatus("Все источники сейчас не отвечают, попробуй позже", BAD, false);
            showInfo(0);
            return;
        }
        si = si % ch.sources.size();
        final int my = ++attempt;
        current = ci; currentSrc = si; autoNext = auto; tried = triedCount; retried = isRetry;
        played = false; firstFrame = false;
        ui.removeCallbacks(stallCheck);
        adapter.notifyDataSetChanged();
        showInfo(0);
        setStatus("Подключаюсь к источнику " + (si + 1) + " из " + ch.sources.size() + "…", MUTED, false);

        player.stop();
        player.setMediaSource(source(ch.sources.get(si)));
        player.prepare();
        player.setPlayWhenReady(true);

        long wait = ch.viaProxy(si) ? 25000 : 15000; // через прокси поток раскачивается дольше
        ui.postDelayed(() -> {
            if (my != attempt || played) return;
            if (!retried && firstFrame) {
                setStatus("Источник " + (currentSrc + 1) + " медленный, перезапускаю…", MUTED, false);
                play(current, currentSrc, autoNext, tried, true);
            } else fail(my);
        }, wait);
    }

    private void onPlaying() {
        if (played || current < 0) return;
        played = true;
        Channels.Channel ch = channels.get(current);
        prefs.edit().putInt("src:" + ch.name, currentSrc).putString("last", ch.name).apply();
        setStatus("Идёт · источник " + (currentSrc + 1) + " из " + ch.sources.size(), OK, true);
        showInfo(3500);
    }

    private void fail(int my) {
        if (my != attempt || current < 0) return;
        attempt++;
        Channels.Channel ch = channels.get(current);
        boolean more = autoNext && tried + 1 < ch.sources.size();
        setStatus("Источник " + (currentSrc + 1) + " не отвечает" + (more ? ", пробую следующий…" : ""), BAD, false);
        showInfo(0);
        if (more) {
            final int ci = current, next = currentSrc + 1, t = tried + 1;
            ui.postDelayed(() -> play(ci, next, true, t, false), 300);
        } else if (autoNext) {
            setStatus("Все источники сейчас не отвечают, попробуй позже", BAD, false);
        }
    }

    // Сторож: если уже шедшее видео застряло в загрузке на 15 секунд — берём следующий источник
    private final Runnable stallCheck = new Runnable() {
        @Override public void run() {
            if (player == null || !played) return;
            if (player.getPlaybackState() == Player.STATE_BUFFERING) {
                setStatus("Поток завис, переключаю…", BAD, false);
                showInfo(0);
                play(current, currentSrc + 1, true, 0, false);
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
                nextSource(); return true;
            case KeyEvent.KEYCODE_INFO:
                showInfo(4000); return true;
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

    private void nextSource() {
        if (current < 0) return;
        Channels.Channel ch = channels.get(current);
        if (ch.sources.size() < 2) { setStatus("У этого канала один источник", MUTED, played); showInfo(2500); return; }
        play(current, currentSrc + 1, true, 0, false);
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

        // Нижняя плашка: какой канал и что с ним происходит
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
        infoStatus = text(16, MUTED, false);
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
        logoLp.bottomMargin = dp(10);
        logoLp.leftMargin = dp(8);
        panel.addView(logo, logoLp);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(2));
        list.setSelector(selector());
        list.setDrawSelectorOnTop(false);
        list.setVerticalScrollBarEnabled(false);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object item = adapter.getItem(pos);
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
        footer.setPadding(dp(8), dp(8), 0, 0);
        panel.addView(footer);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(dp(420), -1, Gravity.START));

        // Заставка поверх всего
        splashBox = new FrameLayout(this);
        splashBox.setBackgroundColor(Color.BLACK);
        splash = new VideoView(this);
        splashBox.addView(splash, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(splashBox, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
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
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(150).start();
        int pos = adapter.positionOf(Math.max(current, 0));
        list.requestFocus();
        list.setSelectionFromTop(pos, dp(120));
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
            List<String> order = new ArrayList<>();
            for (Channels.Channel c : channels) if (!order.contains(c.sec)) order.add(c.sec);
            for (String s : order) {
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
                TextView name = text(19, Color.WHITE, false);
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f);
                np.leftMargin = dp(14);
                r.addView(name, np);
                View dot = new View(MainActivity.this);
                dot.setBackground(round(OK, 5));
                r.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
            }
            FrameLayout bf = (FrameLayout) r.getChildAt(0);
            bf.removeAllViews();
            bf.addView(badge(ch, 13), new FrameLayout.LayoutParams(-1, -1));
            TextView name = (TextView) r.getChildAt(1);
            name.setText(ch.name);
            boolean isCur = ci == current;
            name.setTextColor(isCur ? ACCENT : Color.WHITE);
            name.setTypeface(isCur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            r.getChildAt(2).setVisibility(isCur && played ? View.VISIBLE : View.INVISIBLE);
            return r;
        }
    }

    private int indexOf(String name) {
        if (name == null) return -1;
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).name.equals(name)) return i;
        return -1;
    }

    // ---------- данные: сначала то, что есть на устройстве, потом свежее с сайта ----------

    private void loadLocalData() {
        String html = readFile("index.html");
        if (html == null || Channels.parse(html).size() < 5) html = readAsset("index.html");
        applyChannels(Channels.parse(html));
        byte[] sprite = readBytes(new File(getFilesDir(), "logos.webp"));
        if (sprite == null) sprite = readAssetBytes("logos.webp");
        if (sprite != null) logoSprite = BitmapFactory.decodeByteArray(sprite, 0, sprite.length);
        footer.setText("rus tv для ТВ · версия " + BuildConfig.VERSION_NAME + "\n▲▼ каналы  ·  OK список  ·  ► другой источник");
        footer.setSingleLine(false);
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
        new Thread(() -> {
            String html = download(SITE + "index.html?t=" + System.currentTimeMillis());
            if (html == null || Channels.parse(html).size() < 5) html = download(RAW + "index.html");
            if (html == null) return;
            final List<Channels.Channel> fresh = Channels.parse(html);
            if (fresh.size() < 5) return;
            String old = readFile("index.html");
            if (html.equals(old)) return;
            writeFile("index.html", html.getBytes(StandardCharsets.UTF_8));
            byte[] sprite = downloadBytes(SITE + "logos.webp?t=" + System.currentTimeMillis());
            if (sprite == null) sprite = downloadBytes(RAW + "logos.webp");
            final Bitmap bm = sprite == null ? null : BitmapFactory.decodeByteArray(sprite, 0, sprite.length);
            if (bm != null) writeFile("logos.webp", sprite);
            ui.post(() -> {
                if (bm != null) { logoSprite = bm; logoCache.clear(); }
                // Если канал ещё не выбран (идёт заставка) — просто берём новый список. Иначе обновим тихо, не прерывая просмотр.
                boolean wasEmpty = channels.isEmpty();
                applyChannels(fresh);
                if (wasEmpty && splashDone && player != null) startLastOrCurrent();
            });
        }).start();
    }

    private String download(String url) {
        byte[] b = downloadBytes(url);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private byte[] downloadBytes(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", UA);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return readAll(in); }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
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

    private void writeFile(String name, byte[] data) {
        File tmp = new File(getFilesDir(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        } catch (Exception e) { return; }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(getFilesDir(), name));
    }
}
