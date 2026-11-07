package com.araldev.araltv;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CrashGuard — تور ایمنی ضد کرش.
 *
 * اگر روزی خطایی رخ دهد، گزارش کامل آن (stack trace) پیش از بسته شدن اپ
 * در فایل last_crash.txt (داخل پوشهٔ اختصاصی اپ در حافظهٔ داخلی و بیرونی)
 * نوشته می‌شود تا مشکل از راه دور قابل تشخیص باشد.
 *
 * نصب: CrashGuard.install(this) در onCreate هر اکتیویتی (اول از همه Splash).
 */
public final class CrashGuard {

    public static final String FILE_NAME = "last_crash.txt";

    private CrashGuard() {}

    public static void install(Context ctx) {
        try {
            final Context app = ctx.getApplicationContext();
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try { write(app, t, e); } catch (Throwable ignored) {}
                    // رفتار پیش‌فرض سیستم (بستن اپ) حفظ شود
                    if (previous != null) previous.uncaughtException(t, e);
                }
            });
        } catch (Throwable ignored) {}
    }

    /** گزارش کرش را در هر دو محل ممکن می‌نویسد (بیرونی برای دسترسی آسان‌تر). */
    private static void write(Context app, Thread t, Throwable e) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String report = "Aral TV Android " + BuildConfigLike.VERSION
                + "  |  " + stamp
                + "  |  thread=" + t.getName()
                + "\n----------------------------------------\n"
                + sw.toString()
                + "\n";

        // ۱) حافظهٔ داخلی — همیشه موجود
        try {
            File f = new File(app.getFilesDir(), FILE_NAME);
            FileOutputStream fos = new FileOutputStream(f, false);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(report);
            w.close();
        } catch (Throwable ignored) {}

        // ۲) پوشهٔ اختصاصی بیرونی — Android/data/com.araldev.araltv/files/
        try {
            File dir = app.getExternalFilesDir(null);
            if (dir != null) {
                File f = new File(dir, FILE_NAME);
                FileOutputStream fos = new FileOutputStream(f, false);
                OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
                w.write(report);
                w.close();
            }
        } catch (Throwable ignored) {}
    }
}
