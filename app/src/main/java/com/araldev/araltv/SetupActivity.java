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
 *
 * اصل تازه در ۱.۷.۲: این صفحه هرگز نباید باعث بسته شدن اپ شود —
 * اگر لی‌اوت XML روی دستگاهی شکست بخورد، همین صفحه با ویجت‌های خالص
 * فریم‌ورک ساخته می‌شود (بدون BlobsView، بدون فونت، بدون drawable).
 */
public class SetupActivity extends Activity {

    private EditText inputUrl;
    private Button btnConnect, btnSkip;
    private ProgressBar progress;
    private TextView status;
    private LinearLayout helpBox;
    private TextView btnHelpToggle, btnGithub;
    private boolean checking = false;

    /** true = صفحه با ویجت برنامه‌ای ساخته شده (نه XML) */
    private boolean builtFallback = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(getApplicationContext());

        try {
            Ui.init(getApplicationContext());
            setContentView(R.layout.activity_setup);
        } catch (Throwable t) {
            // اگر لی‌اوت روی این دستگاه شکست خورد، صفحه را کاملاً برنامه‌ای بساز.
            // (هرگز همان setContentView را دوباره صدا نزن — همان کار، همان خطا)
            buildFallbackUi();
        }

        if (!builtFallback) {
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

                // حداکثر پهنای کارت مثل سایت — min(420dp, عرض صفحه)
                int screenW = getResources().getDisplayMetrics().widthPixels;
                card.getLayoutParams().width = Math.min(Ui.dp(this, 420), screenW - Ui.dp(this, 28));

                // تیتر برند — «Aral TV» با « TV» نارنجی، فونت Orbitron مثل سایت
                TextView title = findViewById(R.id.title);
                SpannableString s = SpannableString.valueOf("Aral TV");
                s.setSpan(new ForegroundColorSpan(Ui.C_FIRE), 4, 7, 0);
                title.setText(s);
                title.setLetterSpacing(0.16f);
                if (Ui.fontDisplay != null) title.setTypeface(Ui.fontDisplay);

                TextView mono = findViewById(R.id.monoFooter);
                if (Ui.fontMono != null) mono.setTypeface(Ui.fontMono);
                TextView example = findViewById(R.id.urlExample);
                if (Ui.fontMono != null) example.setTypeface(Ui.fontMono);

                Ui.applyFonts(findViewById(R.id.root));
            } catch (Throwable ignored) {}
        }

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String existing = prefs.getString(MainActivity.KEY_URL, null);
        if (existing != null && !existing.trim().isEmpty()) {
            inputUrl.setText(existing);
        } else if (inputUrl != null) {
            inputUrl.requestFocus();
        }

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
                if (helpBox != null) {
                    helpBox.setVisibility(helpBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                }
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

    /* =====================================================================
       صفحهٔ اتصالِ بدون XML — دو لایه:
       ۱) ویجت‌های خالص با همان متن‌ها و رنگ‌های سایت (بدون فونت/drawable)
       ۲) اگر باز هم نشد: فقط ورودی و دکمه‌ها — مهم این است که اپ باز بماند
       ===================================================================== */
    private void buildFallbackUi() {
        try {
            builtFallback = true;

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            box.setPadding(Ui.dp(this, 24), Ui.dp(this, 56), Ui.dp(this, 24), Ui.dp(this, 48));
            box.setBackgroundColor(0xFF070707);

            TextView title = new TextView(this);
            title.setText("Aral TV");
            title.setTextColor(0xFFFF4D00);
            title.setTextSize(28);
            title.setPadding(0, 0, 0, Ui.dp(this, 4));

            TextView sub = new TextView(this);
            sub.setText(getString(R.string.setup_sub));
            sub.setTextColor(0xFF8F8A83);
            sub.setTextSize(13);
            sub.setPadding(0, 0, 0, Ui.dp(this, 22));

            inputUrl = new EditText(this);
            inputUrl.setHint(getString(R.string.setup_url_hint));
            inputUrl.setSingleLine(true);
            inputUrl.setTextColor(0xFFE8E6E1);
            inputUrl.setHintTextColor(0xFF57534C);
            inputUrl.setTextSize(14);

            btnConnect = new Button(this);
            btnConnect.setText(R.string.setup_connect);
            btnConnect.setTextColor(0xFF120500);

            btnSkip = new Button(this);
            btnSkip.setText(R.string.setup_skip);
            btnSkip.setTextColor(0xFF8F8A83);

            progress = new ProgressBar(this);
            progress.setVisibility(View.GONE);

            status = new TextView(this);
            status.setVisibility(View.GONE);
            status.setTextSize(12.5f);
            status.setTextColor(0xFF8F8A83);
            status.setGravity(android.view.Gravity.CENTER);

            helpBox = new LinearLayout(this);
            helpBox.setOrientation(LinearLayout.VERTICAL);
            helpBox.setVisibility(View.GONE);

            btnHelpToggle = new TextView(this);
            btnHelpToggle.setText(getString(R.string.setup_help_title));
            btnHelpToggle.setTextColor(0xFF8F8A83);
            btnHelpToggle.setTextSize(12.5f);
            btnHelpToggle.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));

            btnGithub = new TextView(this);
            btnGithub.setText(getString(R.string.setup_help_link));
            btnGithub.setTextColor(0xFFFF4D00);
            btnGithub.setTextSize(12.5f);
            btnGithub.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));

            box.addView(title);
            box.addView(sub);
            box.addView(inputUrl, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            box.addView(btnConnect, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            box.addView(btnSkip, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            box.addView(progress, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            box.addView(status, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            box.addView(btnHelpToggle);
            box.addView(helpBox);
            box.addView(btnGithub);

            setContentView(new android.widget.ScrollView(this) {{
                addView(box, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                setFillViewport(true);
            }});
        } catch (Throwable fatal) {
            // لایهٔ آخر — بدون هیچ چیز تزئینی، فقط ورودی و دکمه‌ها
            try {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setBackgroundColor(0xFF070707);
                box.setPadding(24, 64, 24, 48);

                inputUrl = new EditText(this);
                btnConnect = new Button(this);
                btnConnect.setText(R.string.setup_connect);
                btnSkip = new Button(this);
                btnSkip.setText(R.string.setup_skip);
                progress = new ProgressBar(this);
                progress.setVisibility(View.GONE);
                status = new TextView(this);
                status.setVisibility(View.GONE);
                helpBox = new LinearLayout(this);
                helpBox.setVisibility(View.GONE);
                btnHelpToggle = new TextView(this);
                btnGithub = new TextView(this);

                box.addView(inputUrl, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                box.addView(btnConnect, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                box.addView(btnSkip, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                box.addView(progress);
                box.addView(status);
                box.addView(btnHelpToggle);
                box.addView(helpBox);
                box.addView(btnGithub);
                setContentView(box);
            } catch (Throwable ignored) {}
        }
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
        try {
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(MainActivity.KEY_URL, url)
                    .apply();
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        } catch (Throwable t) {
            showStatus(getString(R.string.setup_error_unreachable), 0xFFF36D6D);
        }
    }

    private void showStatus(String msg, int color) {
        if (status == null) return;
        status.setVisibility(View.VISIBLE);
        status.setTextColor(color);
        status.setText(msg);
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && inputUrl != null) imm.hideSoftInputFromWindow(inputUrl.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }
}

/** ثابت‌های نسخه (جایگزین BuildConfig در بیلد دستی) */
final class BuildConfigLike {
    static final String VERSION = "1.7.2";
    private BuildConfigLike() {}
}
