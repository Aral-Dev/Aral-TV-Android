package com.araldev.araltv;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * BootGuard — شکستنِ حلقهٔ کرش.
 *
 * مشکل قبلی: آدرس پنل ذخیره می‌شد و اگر MainActivity کرش می‌کرد، اجرای بعدی
 * دوباره مستقیم به همان MainActivity می‌رفت و کاربر برای همیشه قفل می‌شد.
 *
 * راه‌حل: هر بار که MainActivity شروع می‌شود شمارنده +۱ می‌شود؛ وقتی صفحه
 * با موفقیت بارگذاری شد (یا اپ ۸ ثانیه زنده ماند / کاربر عادی خارج شد) صفر می‌شود.
 * اگر شمارنده به ۲ برسد یعنی دو اجرای پیاپی بدون موفقیت تمام شده؛ اسپلش به جای
 * MainActivity، صفحهٔ تنظیم آدرس را باز می‌کند تا کاربر هرگز قفل نشود.
 * از commit() استفاده می‌شود چون ممکن است پروسه بلافاصله بعد از آن بمیرد.
 */
final class BootGuard {

    static final String KEY_FAILS = "boot_fails";
    private static final int MAX_FAILS = 2;

    private BootGuard() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    static void markStart(Context c) {
        try {
            SharedPreferences p = prefs(c);
            p.edit().putInt(KEY_FAILS, p.getInt(KEY_FAILS, 0) + 1).commit();
        } catch (Throwable ignored) {}
    }

    static void markHealthy(Context c) {
        try {
            prefs(c).edit().putInt(KEY_FAILS, 0).commit();
        } catch (Throwable ignored) {}
    }

    static boolean tooManyFailures(Context c) {
        try {
            return prefs(c).getInt(KEY_FAILS, 0) >= MAX_FAILS;
        } catch (Throwable t) {
            return false;
        }
    }
}
