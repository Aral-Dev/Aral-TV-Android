package com.araldev.araltv;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * پل جاوااسکریپت ↔ نیتیو — فقط دو چیز، هیچ ظاهری از سایت را تغییر نمی‌دهد:
 *  onTheme : رنگ تم سایت (از metaTheme خود سایت) → رنگ نوارهای سیستم
 *  saveFile: دانلودهای blob (بکاپ و …) → ذخیره در Downloads
 */
public class JsBridge {

    private final MainActivity act;

    public JsBridge(MainActivity act) {
        this.act = act;
    }

    /** رنگ تم فعلی سایت + حالت دارک/لایت — از bridge.js صدا زده می‌شود */
    @JavascriptInterface
    public void onTheme(String colorHex, String mode) {
        act.bridgeOnTheme(colorHex, mode);
    }

    /** ذخیرهٔ فایل base64 (دانلودهای blob از جمله بکاپ) */
    @JavascriptInterface
    public void saveFile(final String name, final String b64) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String safe = sanitizeName(name);
                    byte[] data = Base64.decode(b64 != null ? b64 : "", Base64.DEFAULT);
                    final String where;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                        v.put(MediaStore.Downloads.MIME_TYPE, guessMime(safe));
                        v.put(MediaStore.Downloads.IS_PENDING, 1);
                        Uri uri = act.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        if (uri == null) throw new IllegalStateException("insert failed");
                        OutputStream os = act.getContentResolver().openOutputStream(uri);
                        os.write(data);
                        os.flush();
                        os.close();
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Downloads.IS_PENDING, 0);
                        act.getContentResolver().update(uri, done, null, null);
                        where = "downloads";
                    } else {
                        File dir = act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        if (dir == null) dir = act.getFilesDir();
                        File f = new File(dir, safe);
                        FileOutputStream fos = new FileOutputStream(f);
                        fos.write(data);
                        fos.flush();
                        fos.close();
                        where = f.getAbsolutePath();
                    }
                    final String loc = where;
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if ("downloads".equals(loc)) {
                                act.toast(act.getString(R.string.saved_to_downloads));
                            } else {
                                act.toast(act.getString(R.string.saved_to_app, loc));
                            }
                        }
                    });
                } catch (Exception e) {
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            act.toast(act.getString(R.string.save_failed));
                        }
                    });
                }
            }
        }).start();
    }

    static String sanitizeName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "aral-download.bin";
        String s = raw.replace('/', '-').replace('\\', '-').trim();
        if (s.length() > 120) s = s.substring(s.length() - 120);
        return s;
    }

    static String guessMime(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }
}
