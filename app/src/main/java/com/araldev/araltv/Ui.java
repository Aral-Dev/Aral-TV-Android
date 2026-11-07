package com.araldev.araltv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

/**
 * ابزارهای مشترک رابط کاربری — دقیقاً بر پایهٔ دیزاین‌سیستم خود سایت Aral TV v1.7:
 * فونت‌ها (Vazirmatn / Orbitron / JetBrains Mono — همان فایل‌های سایت)
 * پالت رنگ سایت، و گوشه‌های بریدهٔ امضای ARAL (clip-path سایت).
 */
public final class Ui {

    /* ---------- فونت‌های خود سایت ---------- */
    public static Typeface fontRegular;      // Vazirmatn 400
    public static Typeface fontBold;         // Vazirmatn 700
    public static Typeface fontBlack;        // Vazirmatn 800
    public static Typeface fontDisplay;      // Orbitron 900  (تیترها/برند)
    public static Typeface fontDisplayBold;  // Orbitron 700
    public static Typeface fontMono;         // JetBrains Mono 400

    /* ---------- پالت دیزاین‌سیستم سایت (تم دارک) ---------- */
    public static final int C_BG      = 0xFF070707;  // --bg
    public static final int C_CARD    = 0xFF14120F;  // --glass روی مشکی
    public static final int C_FIELD   = 0xFF0D0C0A;  // --field
    public static final int C_BORDER  = 0xFF1D1C1A;   // --border
    public static final int C_BORDER2 = 0xFF2E2D2B;   // --border2
    public static final int C_TEXT    = 0xFFE8E6E1;   // --text
    public static final int C_TEXT2   = 0xFFC9C4BD;   // --text2
    public static final int C_MUTED   = 0xFF8F8A83;   // --muted
    public static final int C_DIM     = 0xFF57534C;   // --dim
    public static final int C_FIRE    = 0xFFFF4D00;   // --fire
    public static final int C_FIRE2   = 0xFFFF6A26;   // --fire2
    public static final int C_FIRE_DK = 0xFFD64100;   // حالت فشردهٔ دکمه
    public static final int C_NEON    = 0xFF00FF9D;   // --neon
    public static final int C_INK     = 0xFF120500;   // --fire-ink (متن روی دکمهٔ نارنجی)

    /* ---------- پالت سایت (تم لایت) ---------- */
    public static final int C_BG_L    = 0xFFF4FAF6;   // --bg
    public static final int C_TEXT_L  = 0xFF0E2018;
    public static final int C_MUTED_L = 0xFF5B7566;
    public static final int C_FIRE_L  = 0xFF00B34D;   // --fire لایت
    public static final int C_INK_L   = 0xFFFFFFFF;

    private Ui() {}

    public static void init(Context ctx) {
        if (fontRegular != null) return;
        fontRegular = load(ctx, "fonts/Vazirmatn-Regular.ttf");
        fontBold = load(ctx, "fonts/Vazirmatn-Bold.ttf");
        fontBlack = load(ctx, "fonts/Vazirmatn-Black.ttf");
        fontDisplay = load(ctx, "fonts/Orbitron-Black.ttf");
        fontDisplayBold = load(ctx, "fonts/Orbitron-Bold.ttf");
        fontMono = load(ctx, "fonts/JetBrainsMono-Regular.ttf");
        if (fontBold == null) fontBold = fontRegular;
        if (fontBlack == null) fontBlack = fontBold;
        if (fontDisplayBold == null) fontDisplayBold = fontDisplay;
        if (fontMono == null) fontMono = Typeface.MONOSPACE;
    }

    private static Typeface load(Context ctx, String path) {
        try {
            return Typeface.createFromAsset(ctx.getAssets(), path);
        } catch (Exception e) {
            return null;
        }
    }

    /** فونت وزیرمتن را روی همهٔ متن‌های این نما اعمال می‌کند */
    public static void applyFonts(View view) {
        if (view == null || fontRegular == null) return;
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            Typeface current = tv.getTypeface();
            boolean bold = current != null && current.isBold();
            if (view instanceof EditText) {
                tv.setTypeface(fontRegular);
            } else {
                tv.setTypeface(bold ? fontBold : fontRegular);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) applyFonts(vg.getChildAt(i));
        }
    }

    public static int dp(Context ctx, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, ctx.getResources().getDisplayMetrics()));
    }

    /* =====================================================================
       گوشهٔ بریدهٔ امضای ARAL — همان clip-path سایت:
       polygon(9px 0, 100% 0, 100% calc(100%-9px), calc(100%-9px) 100%, 0 100%, 0 9px)
       یعنی: برش در گوشهٔ بالا-چپ و پایین-راست.
       ===================================================================== */
    public static Drawable cutDrawable(Context ctx, int fill, int stroke, float cutDp, float strokeDp) {
        return new CutDrawable(fill, stroke, dp(ctx, cutDp), dp(ctx, strokeDp));
    }

    /** دکمهٔ اصلی سایت (کلاس .btn): زمینهٔ fire + حالت فشردهٔ تیره‌تر */
    public static StateListDrawable btnDrawable(Context ctx, final float cutDp) {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed},
                cutDrawable(ctx, C_FIRE_DK, 0, cutDp, 0));
        sld.addState(new int[0], cutDrawable(ctx, C_FIRE, 0, cutDp, 0));
        return sld;
    }

    /** دکمهٔ ثانویه (کلاس .btn-ghost): شفاف با قاب؛ فشرده ← سوسوی fire */
    public static StateListDrawable btnGhostDrawable(Context ctx, final float cutDp) {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed},
                cutDrawable(ctx, 0x21FF4D00, C_FIRE, cutDp, 1));
        sld.addState(new int[0], cutDrawable(ctx, Color.TRANSPARENT, C_BORDER2, cutDp, 1));
        return sld;
    }

    /** فیلد ورودی سایت (کلاس .input): قاب معمول؛ فوکوس ← قاب fire */
    public static StateListDrawable inputDrawable(Context ctx, final float cutDp) {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused},
                cutDrawable(ctx, C_FIELD, C_FIRE, cutDp, 1));
        sld.addState(new int[0], cutDrawable(ctx, C_FIELD, C_BORDER2, cutDp, 1));
        return sld;
    }

    /** یک Drawable سادهٔ تک‌رنگ (برای جاهایی که رنگ لازم است) */
    public static Drawable solid(int color) {
        return new ColorDrawable(color);
    }

    /* ===================================================================== */

    /** CutDrawable — مسیر با دو گوشهٔ بریده + fill + stroke */
    static final class CutDrawable extends Drawable {
        private final int fill, stroke;
        private final int cut, strokeW;
        private final Path path = new Path();
        private final Rect rect = new Rect();
        private final android.graphics.Paint fillPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint strokePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        CutDrawable(int fill, int stroke, int cut, int strokeW) {
            this.fill = fill;
            this.stroke = stroke;
            this.cut = cut;
            this.strokeW = strokeW;
            fillPaint.setStyle(android.graphics.Paint.Style.FILL);
            strokePaint.setStyle(android.graphics.Paint.Style.STROKE);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            rect.set(bounds);
            float w = rect.width(), h = rect.height();
            float c = Math.min(cut, Math.min(w, h) / 2f);
            path.reset();
            path.moveTo(rect.left + c, rect.top);                    // بالا-چپ بریده
            path.lineTo(rect.right, rect.top);
            path.lineTo(rect.right, rect.bottom - c);
            path.lineTo(rect.right - c, rect.bottom);                // پایین-راست بریده
            path.lineTo(rect.left, rect.bottom);
            path.lineTo(rect.left, rect.top + c);
            path.close();
        }

        @Override
        public void draw(Canvas canvas) {
            if (!rect.isEmpty()) {
                fillPaint.setColor(fill);
                canvas.drawPath(path, fillPaint);
                if (strokeW > 0 && stroke != 0) {
                    strokePaint.setColor(stroke);
                    strokePaint.setStrokeWidth(strokeW);
                    canvas.drawPath(path, strokePaint);
                }
            }
        }

        @Override public void setAlpha(int alpha) { fillPaint.setAlpha(alpha); strokePaint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            fillPaint.setColorFilter(cf); strokePaint.setColorFilter(cf);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
