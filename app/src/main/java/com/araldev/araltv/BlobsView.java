package com.araldev.araltv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * BlobsView — همان لکه‌های رنگیِ محوِ صفحهٔ ورود سایت (login-blobs):
 * fire بالا-راست، neon پایین-چپ، fire2 کوچک وسط — بدون انیمیشن، تمیز و آرام.
 *
 * نکتهٔ حیاتی: این ویو از XML تزریق می‌شود، پس constructor دوتایی
 * (Context, AttributeSet) الزامی است — بدون آن LayoutInflater کرش می‌کند.
 */
public class BlobsView extends View {

    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    public BlobsView(Context c) {
        super(c);
    }

    /** لازم برای تزریق از XML (activity_splash / activity_setup) */
    public BlobsView(Context c, AttributeSet attrs) {
        super(c, attrs);
    }

    /** لازم برای تزریق از XML همراه با استایل */
    public BlobsView(Context c, AttributeSet attrs, int defStyleAttr) {
        super(c, attrs, defStyleAttr);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        /* مثل سایت (RTL): blob1 fire در بالا-راست، blob2 neon پایین-چپ، blob3 fire2 وسط-چپ‌پایین */
        blob(canvas, w * 0.86f, h * 0.07f, w * 0.36f, 0x52FF4D00);  // fire  ‏%32
        blob(canvas, w * 0.10f, h * 0.96f, w * 0.31f, 0x5200FF9D);   // neon  ‏%32
        blob(canvas, w * 0.30f, h * 0.44f, w * 0.20f, 0x33FF6A26);   // fire2 ‏%20
    }

    private void blob(Canvas c, float cx, float cy, float r, int argb) {
        paint.setShader(new RadialGradient(cx, cy, r,
                argb, 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, paint);
        paint.setShader(null);
    }
}
