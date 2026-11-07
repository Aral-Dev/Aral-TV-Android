package com.araldev.araltv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * صفحهٔ اتصال به پنل — با همان زبان طراحی صفحهٔ ورود سایت خودش:
 * زمینهٔ مشکی + لکه‌های fire/neon، کارتِ شیشه‌ای با گوشهٔ بریدهٔ ARAL،
 * تیتر Orbitron با « TV» نارنجی، دکمهٔ fire و فوتر مونو.
 * منطق مثل قبل: آدرس ورکر را می‌گیرد، /api/status را بررسی و ذخیره می‌کند.
 */
public class SetupActivity extends Activity {

    /** اگر true باشد یعنی اجرای قبلی اپ با خطا بسته شده است */
    public static final String EXTRA_SAFE = "safe_mode";

    private EditText inputUrl;
    private Button btnConnect, btnSkip;
    private ProgressBar progress;
    private TextView status;
    private LinearLayout helpBox;
    private TextView btnHelpToggle, btnGithub;
    private boolean checking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(getApplicationContext());

        try {
            Ui.init(getApplicationContext());
            setContentView(R.layout.activity_setup);
        } catch (Throwable t) {
            // اگر لی‌اوت شکست خورد، بدون استایل ادامه بده — اتصال به پنل مهم‌تر است
            setContentView(R.layout.activity_setup);
        }

        inputUrl = findViewById(R.id.inputUrl);
        btnConnect = findViewById(R.id.btnConnect);
        btnSkip = findViewById(R.id.btnSkip);
        progress = findViewById(R.id.checkProgress);
        status = findViewById(R.id.checkStatus);
        helpBox = findViewById(R.id.helpBox);
        btnHelpToggle = findViewById(R.id.btnHelpToggle);
        btnGithub = findViewById(R.id.btnGithub);

        // برندینگ به سبک سایت — تزئینی است؛ شکستش هرگز اپ را نبندد
        try {
            // پس‌زمینهٔ سایت — همان login-card: شیشهٔ تیره با قاب و گوشهٔ بریده
            View card = findViewById(R.id.card);
            card.setBackground(Ui.cutDrawable(this, 0xF914120F, Ui.C_BORDER2, 18, 1));
            // خط تاکیدی نارنجی بالای کارت — مثل login-card::before سایت
            findViewById(R.id.cardAccent).setBackgroundResource(R.drawable.bg_accent_fire);

            inputUrl.setBackground(Ui.inputDrawable(this, 9));
            btnConnect.setBackground(Ui.btnDrawable(this, 10));
            btnSkip.setBackground(Ui.btnGhostDrawable(this, 10));
            helpBox.setBackground(Ui.cutDrawable(this, 0xF90D0C0A, Ui.C_BORDER, 9, 1));

            // حداکثر پهنای کارت مثل سایت — min(420dp, عرض صفحه) — همان min(420px,100%) لاگین
            int screenW = getResources().getDisplayMetrics().widthPixels;
            card.getLayoutParams().width = Math.min(Ui.dp(this, 420), screenW - Ui.dp(this, 28));

            // تیتر برند — «Aral TV» با « TV» نارنجی، فونت Orbitron مثل سایت
            TextView title = findViewById(R.id.title);
            SpannableString s = SpannableString.valueOf("Aral TV");
            s.setSpan(new ForegroundColorSpan(Ui.C_FIRE), 5, 8, 0);
            title.setText(s);
            title.setLetterSpacing(0.16f);
            if (Ui.fontDisplay != null) title.setTypeface(Ui.fontDisplay);

            TextView mono = findViewById(R.id.monoFooter);
            if (Ui.fontMono != null) mono.setTypeface(Ui.fontMono);
            TextView example = findViewById(R.id.urlExample);
            if (Ui.fontMono != null) example.setTypeface(Ui.fontMono);

            Ui.applyFonts(findViewById(R.id.root));
        } catch (Throwable ignored) {}

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String existing = prefs.getString(MainActivity.KEY_URL, null);
        if (existing != null && !existing.trim().isEmpty()) {
            inputUrl.setText(existing);
        } else {
            inputUrl.requestFocus();
        }

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_SAFE, false)) {
            showStatus(getString(R.string.safe_mode_notice), 0xFFFFB020);
        }

        // کلید «Go» کیبورد هم اتصال را اجرا کند
        inputUrl.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                connect();
                return true;
            }
        });

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { connect(); }
        });

        btnSkip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String url = normalize(inputUrl.getText().toString());
                if (url != null) saveAndGo(url);
            }
        });

        btnHelpToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                helpBox.setVisibility(helpBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });

        btnGithub.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/Aral-Dev/Aral-TV")));
                } catch (Exception ignored) {}
            }
        });
    }

    /** نرمال‌سازی آدرس: افزودن https، حذف اسلش انتهایی */
    static String normalize(String raw) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.isEmpty()) return null;
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "https://" + raw;
        }
        Uri u = Uri.parse(raw);
        String host = u.getHost();
        if (host == null || host.trim().isEmpty()) return null;
        String scheme = u.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        return raw;
    }

    private void connect() {
        if (checking) return;
        final String url = normalize(inputUrl.getText().toString());
        if (url == null) {
            showStatus(getString(R.string.setup_error_invalid), 0xFFF36D6D);
            return;
        }
        checking = true;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                btnConnect.setEnabled(false);
                progress.setVisibility(View.VISIBLE);
                status.setVisibility(View.VISIBLE);
                status.setTextColor(Ui.C_MUTED);
                status.setText(R.string.setup_checking);
                btnSkip.setVisibility(View.GONE);
                hideKeyboard();
            }
        });

        new Thread(new Runnable() {
            @Override public void run() {
                final String[] result = checkPanel(url); // {errorRes or ok msg}
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        checking = false;
                        btnConnect.setEnabled(true);
                        progress.setVisibility(View.GONE);
                        if (result[0] == null) {
                            status.setTextColor(Ui.C_NEON);
                            status.setText(result[1]);
                            saveAndGo(url);
                        } else {
                            status.setTextColor(0xFFF36D6D);
                            status.setText(result[0]);
                            btnSkip.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }).start();
    }

    /** GET {url}/api/status — باید JSON با app/ok برگرداند */
    private String[] checkPanel(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url + "/api/status").openConnection();
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(9000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "AralTV-Android/" + BuildConfigLike.VERSION);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                return new String[]{getString(R.string.setup_error_unreachable), null};
            }
            InputStream is = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String body = new String(bos.toByteArray(), Charset.forName("UTF-8"));
            JSONObject json = new JSONObject(body);
            if (json.has("ok") || json.has("app")) {
                String version = json.optString("version", "");
                return new String[]{null, getString(R.string.setup_ok, version.isEmpty() ? "?" : version)};
            }
            return new String[]{getString(R.string.setup_error_notpanel), null};
        } catch (Exception e) {
            return new String[]{getString(R.string.setup_error_unreachable), null};
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private void saveAndGo(String url) {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .edit()
                .putString(MainActivity.KEY_URL, url)
                .putInt(BootGuard.KEY_FAILS, 0)
                .commit();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void showStatus(String msg, int color) {
        status.setVisibility(View.VISIBLE);
        status.setTextColor(color);
        status.setText(msg);
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(inputUrl.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }
}

/** ثابت‌های نسخه (جایگزین BuildConfig در بیلد دستی) */
final class BuildConfigLike {
    static final String VERSION = "1.7.2";
    private BuildConfigLike() {}
}
