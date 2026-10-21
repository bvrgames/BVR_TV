package com.bvrgames.rustv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Колесо источников справа: выбранный кружок крупный по центру, соседние — меньше сверху и снизу.
 * При выборе другого колесо плавно прокручивается: выбранный уезжает вверх и уменьшается,
 * новый подъезжает в центр и растёт. На телефоне кружок можно нажать пальцем или прокрутить свайпом.
 */
final class SourceWheel extends View {
    interface Listener { void onPick(int index); }

    static final int UNKNOWN = 0, OK = 1, BAD = 2;

    private final float d; // density
    private static final float BASE = 62; // размер кружка в dp; меньшие рисуем масштабом — без пересчёта градиентов
    private int count;
    private int selected;
    private int playing = -1;
    private int[] status = new int[0];
    private float pos;                 // текущая (анимируемая) позиция колеса
    private ValueAnimator anim;
    private boolean focusedLook;
    private Listener listener;

    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Glass[] glass = new Glass[0];
    private float downY, downX;
    private long downT;

    SourceWheel(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        num.setColor(0xFFFFFFFF);
        num.setTextAlign(Paint.Align.CENTER);
        num.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        num.setShadowLayer(3 * d, 0, d, 0x73000000); // цифра читается на светлом стекле
    }

    void setListener(Listener l) { listener = l; }

    /** Задать источники канала. Прыжок без анимации — если сменился канал. */
    void setData(int count, int selected, int playing, int[] status, boolean jump) {
        boolean countChanged = count != this.count;
        this.count = count;
        this.playing = playing;
        this.status = status;
        if (countChanged) {
            glass = new Glass[count];
            int b = Math.round(BASE * d);
            for (int i = 0; i < count; i++) { glass[i] = Glass.circle(d).frosted(); glass[i].setBounds(-b / 2, -b / 2, b / 2, b / 2); }
        }
        select(selected, jump || countChanged);
    }

    void select(int index, boolean jump) {
        if (count == 0) { selected = 0; pos = 0; invalidate(); return; }
        selected = ((index % count) + count) % count;
        if (anim != null) anim.cancel();
        if (jump) { pos = selected; invalidate(); return; }
        anim = ValueAnimator.ofFloat(pos, selected);
        // через «край» (с последнего на первый) прокручиваем быстрее
        anim.setDuration(Math.abs(pos - selected) > 1.5f ? 320 : 240);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> { pos = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    int selected() { return selected; }

    void setFocusedLook(boolean f) { focusedLook = f; invalidate(); }

    // Геометрия: d — расстояние от центра в «шагах» колеса
    private float offset(float k) {
        float a = Math.abs(k), y = a <= 1 ? a * 64 : 64 + Math.min(a - 1, 1.5f) * 44;
        return Math.signum(k) * y * d;
    }
    private float size(float k) {
        float a = Math.abs(k);
        return (a <= 1 ? 62 - 22 * a : 40 - 12 * Math.min(a - 1, 1f)) * d;
    }
    private float alpha(float k) {
        float a = Math.abs(k);
        if (a <= 1) return 1f - 0.2f * a;
        if (a <= 2) return 0.8f - 0.45f * (a - 1);
        return Math.max(0f, 0.35f - 0.35f * (a - 2) * 2);
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(84 * d), Math.round(300 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        // сначала дальние, потом ближние — чтобы центральный был сверху
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < count; i++) {
                float k = i - pos;
                boolean near = Math.abs(k) < 0.5f;
                if ((pass == 0) == near) continue;
                float al = alpha(k);
                if (al <= 0.01f) continue;
                float s = size(k), y = cy + offset(k);
                Glass g = glass[i];
                boolean sel = i == selected;
                int st = i < status.length ? status[i] : UNKNOWN;
                int glowColor = sel && focusedLook ? 0xFFBF5AF2 : 0;
                // цвет как раньше: зелёный — работает, красный — не отвечает, серый — ещё не проверен
                int tintColor = i == playing || st == OK ? 0xFF30D158 : st == BAD ? 0xFFFF453A : 0xFF8E8E93;
                g.tint(tintColor).glow(glowColor).strength(sel ? (focusedLook ? 1.8f : 1.35f) : 1f);
                g.setAlpha(Math.round(al * 255));
                c.save();
                c.translate(cx, y);
                float sc = s / (BASE * d);
                c.scale(sc, sc);
                g.draw(c);
                c.restore();
                // номер
                num.setTextSize(s * 0.4f);
                num.setAlpha(Math.round(al * 255));
                Paint.FontMetrics fm = num.getFontMetrics();
                c.drawText(String.valueOf(i + 1), cx, y - (fm.ascent + fm.descent) / 2f, num);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (count == 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); downT = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_UP:
                float dy = e.getY() - downY;
                if (Math.abs(dy) > 24 * d) { // свайп по колесу — на один вверх/вниз
                    pick(selected + (dy < 0 ? 1 : -1));
                    return true;
                }
                // нажатие — ищем ближайший кружок
                float cy = getHeight() / 2f, best = Float.MAX_VALUE;
                int hit = -1;
                for (int i = 0; i < count; i++) {
                    float k = i - pos;
                    if (alpha(k) < 0.2f) continue;
                    float dist = Math.abs(cy + offset(k) - e.getY());
                    if (dist < size(k) / 2f + 8 * d && dist < best) { best = dist; hit = i; }
                }
                if (hit >= 0) pick(hit);
                return true;
        }
        return true;
    }

    private void pick(int i) {
        if (count == 0) return;
        i = ((i % count) + count) % count;
        select(i, false);
        if (listener != null) listener.onPick(i);
    }
}
