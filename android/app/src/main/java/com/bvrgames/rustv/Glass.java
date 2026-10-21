package com.bvrgames.rustv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Подложка «жидкое стекло»: тёмная полупрозрачная основа (чтобы текст читался на любой картинке),
 * мягкий свет сверху, тонкая светящаяся кромка и блик по верхнему краю.
 * Рисуется без размытия фона — так быстро даже на слабом телевизоре.
 */
final class Glass extends Drawable {
    private final float radius;      // радиус скругления; < 0 — круг
    private final float density;
    private int glow;                // цвет подсветки кромки (0 — обычное стекло)
    private float strength = 1f;     // 1 — обычное, больше — ярче (выбранный элемент)
    private int tint;                // цвет стекла (0 — тёмное); зелёный/красный/серый для кружков источников

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spec = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int alpha = 255;

    Glass(float radiusPx, float density) {
        this.radius = radiusPx;
        this.density = density;
        edge.setStyle(Paint.Style.STROKE);
        spec.setStyle(Paint.Style.STROKE);
        spec.setStrokeCap(Paint.Cap.ROUND);
        halo.setStyle(Paint.Style.STROKE);
    }

    static Glass circle(float density) { return new Glass(-1, density); }

    Glass glow(int color) { if (glow != color) { glow = color; rebuild(); invalidateSelf(); } return this; }
    Glass tint(int color) { if (tint != color) { tint = color; rebuild(); invalidateSelf(); } return this; }
    Glass strength(float s) { if (strength != s) { strength = s; rebuild(); invalidateSelf(); } return this; }

    @Override protected void onBoundsChange(Rect b) { rebuild(); }

    private static int a(int color, float alpha) {
        int al = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return (al << 24) | (color & 0x00FFFFFF);
    }

    /** Смесь двух цветов: k — доля второго, alpha — прозрачность результата. */
    private static int mix(int c1, int c2, float k, float alpha) {
        int r = Math.round(((c1 >> 16) & 255) * (1 - k) + ((c2 >> 16) & 255) * k);
        int g = Math.round(((c1 >> 8) & 255) * (1 - k) + ((c2 >> 8) & 255) * k);
        int b = Math.round((c1 & 255) * (1 - k) + (c2 & 255) * k);
        return (Math.round(alpha * 255) << 24) | (r << 16) | (g << 8) | b;
    }

    private void rebuild() {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float sw = Math.max(1f, density * (strength > 1f ? 1.6f : 1f));
        r.set(b.left + sw, b.top + sw, b.right - sw, b.bottom - sw);
        float h = r.height(), w = r.width();
        float s = strength;
        int top = tint == 0 ? 0xB3141826 : mix(tint, 0xFF0B0E18, 0.25f, 0.78f);
        int bottom = tint == 0 ? 0xC70B0E18 : mix(tint, 0xFF0B0E18, 0.55f, 0.85f);
        base.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[]{top, bottom}, null, Shader.TileMode.CLAMP));
        light.setShader(new RadialGradient(r.left + w * 0.3f, r.top, Math.max(w, h) * 0.9f,
                new int[]{a(0xFFFFFF, 0.13f * s), a(0xFFFFFF, 0.03f * s), 0}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        int g = glow != 0 ? glow : tint != 0 ? mix(tint, 0xFFFFFFFF, 0.45f, 1f) : 0xFFB9C8FF;
        edge.setStrokeWidth(sw);
        edge.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[]{a(0xFFFFFF, Math.min(1f, 0.55f * s)), a(g, 0.18f * s), a(0xFFFFFF, 0.08f), a(g, Math.min(1f, 0.45f * s))},
                new float[]{0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        spec.setStrokeWidth(sw * 1.4f);
        spec.setShader(new LinearGradient(r.left + w * 0.15f, 0, r.right - w * 0.15f, 0,
                new int[]{0, a(0xFFFFFF, Math.min(1f, 0.85f * s)), 0}, null, Shader.TileMode.CLAMP));
        halo.setStrokeWidth(density * 6);
        halo.setColor(glow != 0 ? a(glow, 0.22f * s) : 0);
    }

    @Override
    public void draw(Canvas c) {
        if (r.isEmpty()) rebuild();
        int save = alpha < 255 ? c.saveLayerAlpha(new RectF(getBounds()), alpha) : c.save();
        if (radius < 0) {
            float cx = r.centerX(), cy = r.centerY(), rad = Math.min(r.width(), r.height()) / 2f;
            if (glow != 0) c.drawCircle(cx, cy, rad - density * 2, halo);
            c.drawCircle(cx, cy, rad, base);
            c.drawCircle(cx, cy, rad, light);
            c.drawCircle(cx, cy, rad, edge);
            RectF arc = new RectF(cx - rad + density, cy - rad + density, cx + rad - density, cy + rad - density);
            c.drawArc(arc, 225, 90, false, spec);
        } else {
            float rad = Math.min(radius, r.height() / 2f);
            if (glow != 0) c.drawRoundRect(r, rad, rad, halo);
            c.drawRoundRect(r, rad, rad, base);
            c.drawRoundRect(r, rad, rad, light);
            c.drawRoundRect(r, rad, rad, edge);
            float y = r.top + edge.getStrokeWidth() / 2f;
            c.drawLine(r.left + rad, y, r.right - rad, y, spec);
        }
        c.restoreToCount(save);
    }

    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
