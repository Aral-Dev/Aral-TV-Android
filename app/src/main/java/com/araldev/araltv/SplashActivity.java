package com.araldev.araltv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

/**
 * اسپلش — برندینگ خودِ سایت (لوگوی PWA + «Aral TV» با Orbitron و « TV» نارنجی)
 * روی همان زمینهٔ مشکی با لکه‌های fire/neon مثل صفحهٔ ورود؛ سپس مسیریابی.
 *
 * اصل ایمنی: برندینگ هرگز نباید مانع ورود به اپ شود؛ همهٔ بخش‌های تزئینی
 * داخل try-catch هستند و مسیریابی تضمین‌شده اجرا می‌شود.
 */
public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // تور ایمنی: ثبت هر خطای احتمالی آینده در فایل برای دیباگ
        CrashGuard.install(getApplicationContext());

        // برندینگ اسپلش — اگر به هر دلیلی شکست خورد، نادیده گرفته می‌شود
        try {
            Ui.init(getApplicationContext());
            setContentView(R.layout.activity_splash);

            TextView title = findViewById(R.id.splashTitle);
            SpannableString s = SpannableString.valueOf("Aral TV");
            s.setSpan(new ForegroundColorSpan(Ui.C_FIRE), 4, 7, 0); // " TV" نارنجی
            title.setText(s);
            title.setLetterSpacing(0.16f);
            if (Ui.fontDisplay != null) title.setTypeface(Ui.fontDisplay);

            TextView mono = findViewById(R.id.splashMono);
            if (Ui.fontMono != null) mono.setTypeface(Ui.fontMono);

            Ui.applyFonts(findViewById(R.id.root));
        } catch (Throwable t) {
            routeNow(); // مستقیم برو داخل اپ — بدون مانده در اسپلش
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                routeNow();
            }
        }, 550);
    }

    /** مسیریابی قطعی: بدون تنظیم → SetupActivity، وگرنه MainActivity */
    private void routeNow() {
        try {
            SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
            String url = prefs.getString(MainActivity.KEY_URL, null);
            Intent i;
            if (url == null || url.trim().isEmpty()) {
                i = new Intent(SplashActivity.this, SetupActivity.class);
            } else {
                i = new Intent(SplashActivity.this, MainActivity.class);
            }
            startActivity(i);
        } catch (Throwable t) {
            // آخرین راه نجات: حداقل صفحهٔ تنظیم را باز کن
            try {
                startActivity(new Intent(SplashActivity.this, SetupActivity.class));
            } catch (Throwable ignored) {}
        } finally {
            finish();
        }
    }
}
