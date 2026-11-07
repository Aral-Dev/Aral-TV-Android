package com.araldev.araltv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import android.graphics.Typeface;

/**
 * اپ اندروید Aral TV v1.7 — پوستهٔ تمیزِ خودِ سایت.
 *
 * فلسفهٔ این نسخه: سایت کاربر (Aral TV v1.7.0) خودش یک اپ موبایل کامل است —
 * نوار پایین، جستجو، تم و همه‌چیز. این پوسته فقط یک قاب نامرئی است:
 *   • ظاهر سایت یک‌به‌یک نمایش داده می‌شود (هیچ CSS تزریق نمی‌شود، هیچ نوار نیتیو نیست)
 *   • رنگ نوارهای سیستم اندروید همیشه با تم لحظه‌ای سایت هماهنگ می‌شود
 *   • تمام‌صفحهٔ ویدیو، دانلودها، انتخاب فایل، اشتراک لینک یوتیوب/آپارات
 */
public class MainActivity extends Activity {

    public static final String PREFS = "araltv_prefs";
    public static final String KEY_URL = "panel_url";
    public static final String KEY_THEME = "site_theme"; // آخرین تم سایت

    private static final int REQ_FILE = 41;

    /** مسیر در انتظار (از اشتراک لینک یوتیوب/آپارات یا باز کردن لینک) */
    public static String pendingPath = null;

    private String panelUrl;
    private String panelHost = "";

    private WebView web;
    private ProgressBar progress;
    private View errorOverlay;
    private TextView errTitle, errSub, errMono;
    private Button btnRetry, btnChangePanel;

    private String webMode = "dark";      // دارک/لایت سایت
    private int webBg = Ui.C_BG;          // رنگ لحظه‌ای تم سایت
    private boolean pageReady = false;

    // ویدیوی تمام‌صفحه
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private FrameLayout fullscreenContainer;

    // انتخاب فایل (بازیابی بکاپ)
    private ValueCallback<Uri[]> filePathCallback;

    private long lastBackPress = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Crash of the web render — loop prevention
    private int rendererCrashes = 0;
    private long lastRendererCrashAt = 0;

    // ---------- چرخهٔ حیات ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(getApplicationContext());
        Ui.init(getApplicationContext());

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        panelUrl = prefs.getString(KEY_URL, null);
        if (panelUrl == null || panelUrl.trim().isEmpty()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        Uri pu = Uri.parse(panelUrl);
        if (pu.getHost() != null) panelHost = pu.getHost().toLowerCase(Locale.ROOT);

        // تم آخرین بار → پیش از بارگذاری صفحه، نوارها هماهنگ باشند (بدون فلش سفید)
        String savedTheme = prefs.getString(KEY_THEME, "dark");
        webMode = "light".equals(savedTheme) ? "light" : "dark";
        webBg = "light".equals(webMode) ? Ui.C_BG_L : Ui.C_BG;
        applyThemeChrome(webBg, webMode);

        try {
            setContentView(R.layout.activity_main);
            bindViews();
            styleBrand();
        } catch (Throwable t) {
            // If the layout failed on this device → build the same frame with plain widgets
            buildFallbackUi();
        }

        try {
            setupWebView();
            handleIntent(getIntent());
            web.loadUrl(panelUrl);
        } catch (Throwable t) {
            // Even the WebView failed → text screen with usable buttons
            showFatalError();
        }

        if (pendingPath != null) toast(getString(R.string.opening_video));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { exitFullscreen(); return; }
        if (errorOverlay != null && errorOverlay.getVisibility() == View.VISIBLE) {
            if (System.currentTimeMillis() - lastBackPress < 2200) { super.onBackPressed(); return; }
            lastBackPress = System.currentTimeMillis();
            toast(getString(R.string.exit_press_again));
            return;
        }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        if (System.currentTimeMillis() - lastBackPress < 2200) { super.onBackPressed(); return; }
        lastBackPress = System.currentTimeMillis();
        toast(getString(R.string.exit_press_again));
    }

    // ---------- نماها ----------

    private void bindViews() {
        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        errorOverlay = findViewById(R.id.errorOverlay);
        errTitle = findViewById(R.id.errTitle);
        errSub = findViewById(R.id.errSub);
        btnRetry = findViewById(R.id.btnRetry);
        btnChangePanel = findViewById(R.id.btnChangePanel);
        errMono = findViewById(R.id.errMono);

        wireErrorButtons();
    }

    /** Error-page buttons — for both the XML path and the programmatic frame */
    private void wireErrorButtons() {
        if (btnRetry == null || btnChangePanel == null) return;
        btnRetry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (errorOverlay != null) errorOverlay.setVisibility(View.GONE);
                pageReady = false;
                if (web != null) {
                    web.reload();
                } else {
                    relaunchApp(); // no webview → let the whole app restart cleanly
                }
            }
        });
        btnChangePanel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gotoSetup(); }
        });
    }

    /** Clean full-app restart from splash */
    private void relaunchApp() {
        try {
            Intent i = new Intent(MainActivity.this, SplashActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        } catch (Throwable ignored) {}
    }

    /* =====================================================================
       Frame without XML — same structure as activity_main, plain widgets:
       full-screen WebView + progress bar + error page
       ===================================================================== */
    private void buildFallbackUi() {
        try {
            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(webBg);

            web = new WebView(this);
            root.addView(web, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setVisibility(View.GONE);
            root.addView(progress, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 2), android.view.Gravity.TOP));

            errorOverlay = new FrameLayout(this);
            FrameLayout overlayF = (FrameLayout) errorOverlay;
            overlayF.setBackgroundColor(webBg);
            overlayF.setVisibility(View.GONE);
            overlayF.setClickable(true);
            overlayF.setFocusable(true);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            card.setPadding(Ui.dp(this, 28), Ui.dp(this, 64), Ui.dp(this, 28), Ui.dp(this, 48));

            errTitle = new TextView(this);
            errTitle.setText(getString(R.string.error_title));
            errTitle.setTextColor(Ui.C_TEXT);
            errTitle.setTextSize(16);
            errTitle.setGravity(android.view.Gravity.CENTER);
            errTitle.setPadding(0, 0, 0, Ui.dp(this, 6));

            errSub = new TextView(this);
            errSub.setText(getString(R.string.error_sub));
            errSub.setTextColor(Ui.C_MUTED);
            errSub.setTextSize(13);
            errSub.setGravity(android.view.Gravity.CENTER);

            btnRetry = new Button(this);
            btnRetry.setText(R.string.error_retry);
            btnRetry.setTextColor(Ui.C_INK);

            btnChangePanel = new Button(this);
            btnChangePanel.setText(R.string.error_change);
            btnChangePanel.setTextColor(Ui.C_TEXT2);

            errMono = new TextView(this);
            errMono.setText("ARAL TV · ANDROID");
            errMono.setTextColor(Ui.C_DIM);
            errMono.setTextSize(10.5f);
            errMono.setPadding(0, Ui.dp(this, 26), 0, 0);

            card.addView(errTitle);
            card.addView(errSub);
            card.addView(btnRetry, new LinearLayout.LayoutParams(
                    Ui.dp(this, 230), Ui.dp(this, 50)));
            card.addView(btnChangePanel, new LinearLayout.LayoutParams(
                    Ui.dp(this, 230), Ui.dp(this, 46)));
            card.addView(errMono);
            overlayF.addView(card, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER));

            root.addView(overlayF, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            setContentView(root);
            wireErrorButtons();
            themeErrorOverlay();
        } catch (Throwable t) {
            // even the webview frame failed → final text-only screen
            web = null;
            buildTextOnlyUi();
        }
    }

    /** Last safety layer: no webview — text + three working buttons */
    private void buildTextOnlyUi() {
        try {
            errorOverlay = null;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(android.view.Gravity.CENTER);
            box.setPadding(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);
            box.setBackgroundColor(Ui.C_BG);

            errTitle = new TextView(this);
            errTitle.setText(getString(R.string.error_renderer_title));
            errTitle.setTextColor(Ui.C_TEXT);
            errTitle.setTextSize(16);
            errTitle.setGravity(android.view.Gravity.CENTER);
            errTitle.setPadding(0, 0, 0, Ui.dp(this, 8));

            errSub = new TextView(this);
            errSub.setText(getString(R.string.error_sub));
            errSub.setTextColor(Ui.C_MUTED);
            errSub.setTextSize(13);
            errSub.setGravity(android.view.Gravity.CENTER);
            errSub.setPadding(0, 0, 0, Ui.dp(this, 24));

            btnRetry = new Button(this);
            btnRetry.setText(R.string.error_retry);
            btnRetry.setTextColor(Ui.C_INK);

            btnChangePanel = new Button(this);
            btnChangePanel.setText(R.string.error_change);
            btnChangePanel.setTextColor(Ui.C_TEXT2);

            Button btnBrowser = new Button(this);
            btnBrowser.setText(R.string.error_open_browser);
            btnBrowser.setTextColor(Ui.C_TEXT2);

            errMono = new TextView(this);
            errMono.setText("ARAL TV · ANDROID");
            errMono.setTextColor(Ui.C_DIM);
            errMono.setTextSize(10.5f);
            errMono.setPadding(0, Ui.dp(this, 26), 0, 0);

            btnRetry.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { relaunchApp(); }
            });
            btnChangePanel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { gotoSetup(); }
            });
            btnBrowser.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openExternal(panelUrl); }
            });

            box.addView(errTitle);
            box.addView(errSub);
            box.addView(btnRetry, new LinearLayout.LayoutParams(
                    Ui.dp(this, 230), Ui.dp(this, 50)));
            box.addView(btnChangePanel, new LinearLayout.LayoutParams(
                    Ui.dp(this, 230), Ui.dp(this, 46)));
            box.addView(btnBrowser, new LinearLayout.LayoutParams(
                    Ui.dp(this, 230), Ui.dp(this, 46)));
            box.addView(errMono);
            setContentView(box);
        } catch (Throwable ignored) {}
    }

    /** Final error screen — no crash, with real solutions */
    private void showFatalError() {
        try {
            if (progress != null) progress.setVisibility(View.GONE);
            if (errorOverlay != null && errTitle != null) {
                errorOverlay.setVisibility(View.VISIBLE);
                errTitle.setText(R.string.error_renderer_title);
                if (errSub != null) errSub.setText(getString(R.string.error_sub));
                return;
            }
            buildTextOnlyUi();
        } catch (Throwable ignored) {}
    }

    /** web render crash — first: clean restart; repeat under 30s: stop and show solutions */
    private void onRendererCrash() {
        try {
            if (web != null) {
                try { ((ViewGroup) web.getParent()).removeView(web); } catch (Throwable ignored) {}
                try { web.destroy(); } catch (Throwable ignored) {}
                web = null;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastRendererCrashAt > 30000) rendererCrashes = 0;
            lastRendererCrashAt = now;
            rendererCrashes++;

            if (rendererCrashes <= 1) {
                relaunchApp();
            } else {
                // do not restart again (no infinite loop) — offer solutions instead
                showFatalError();
            }
        } catch (Throwable ignored) {
            try { finish(); } catch (Throwable ignored2) {}
        }
    }

    /** برند صفحهٔ خطا با فونت‌ها و رنگ‌های خود سایت */
    private void styleBrand() {
        Ui.applyFonts(errorOverlay);

        TextView brand = findViewById(R.id.errBrand);
        SpannableString s = SpannableString.valueOf("Aral TV");
        s.setSpan(new ForegroundColorSpan(Ui.C_FIRE), 4, 7, 0); // " TV" نارنجی مثل سایت
        brand.setText(s);
        brand.setLetterSpacing(0.14f);
        if (Ui.fontDisplay != null) brand.setTypeface(Ui.fontDisplay);

        TextView mono = findViewById(R.id.errMono);
        if (Ui.fontMono != null) mono.setTypeface(Ui.fontMono);

        themeErrorOverlay();
    }

    // ---------- وب‌ویو ----------

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // حذف نشانگر «wv» از UA برای سازگاری بهتر (از جمله ورود گوگل)
        String ua = s.getUserAgentString();
        if (ua != null) s.setUserAgentString(ua.replace("; wv", "").trim());

        web.setBackgroundColor(webBg);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new JsBridge(this), "AralBridge");

        web.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return routeUrl(request.getUrl(), request.isForMainFrame());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return routeUrl(Uri.parse(url), true);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                if (errorOverlay != null) errorOverlay.setVisibility(View.GONE);
                injectBridge();
                try { pushPendingToPage(); } catch (Throwable ignored) {}
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) showError();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // امنیت: گواهی نامعتبر هرگز پذیرفته نمی‌شود
                handler.cancel();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // کرش رندر وب‌ویو — با محافظ ضد حلقه
                if (detail != null && detail.didCrash()) {
                    onRendererCrash();
                    return true;
                }
                return false;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    progress.setVisibility(View.GONE);
                } else {
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(newProgress);
                }
            }

            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                enterFullscreen();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                startDownload(url, userAgent, contentDisposition, mimeType);
            }
        });
    }

    /** مسیریابی نشانی‌ها: پنل و ورود گوگل داخل اپ، دانلودها به DownloadManager، بقیه بیرون */
    private boolean routeUrl(Uri uri, boolean isMainFrame) {
        if (uri == null || uri.getScheme() == null) return false;
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            if (isMainFrame) openExternal(uri.toString());
            return true;
        }
        if (!isMainFrame) return false; // آی‌فریم‌ها (پلیر آپارات و …) عادی لود شوند
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

        // پنل خودمان
        if (host.equals(panelHost)) return false;

        // جریان ورود گوگل (اتصال اکانت یوتیوب) باید داخل اپ کامل شود
        if (host.equals("accounts.google.com") || host.equals("accounts.youtube.com")
                || host.equals("myaccount.google.com") || host.equals("consent.google.com")
                || host.endsWith(".googleusercontent.com")) {
            return false;
        }

        // دانلود مستقیم فایل‌ها (worker.js از گیت‌هاب)
        if (host.equals("raw.githubusercontent.com") || host.equals("github.com")
                || host.equals("codeload.github.com")) {
            startDownload(uri.toString(), null, null, null);
            return true;
        }

        // هر چیز دیگر (شبکه‌های اجتماعی، لینک‌های بیرونی) → مرورگر بیرونی
        openExternal(uri.toString());
        return true;
    }

    // ---------- پل ----------

    private void injectBridge() {
        try {
            InputStream is = getAssets().open("bridge.js");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            final String js = bos.toString("UTF-8");
            web.evaluateJavascript(js, null);
        } catch (Exception ignored) {}
    }

    /** مسیر در انتظار را به صفحه می‌سپارد؛ bridge.js بعد از ورود آن را با navigate() خود سایت باز می‌کند */
    private void pushPendingToPage() {
        if (pendingPath == null || !pageReady) return;
        final String p = pendingPath;
        pendingPath = null;
        web.evaluateJavascript("window.__aralPending=" + jsString(p) + ";undefined", null);
    }

    /** ساخت رشتهٔ JSON از یک مسیر (برای evaluateJavascript) */
    static String jsString(String s) {
        if (s == null) return "''";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** از JsBridge صدا زده می‌شود (روی UI thread) — تم سایت عوض شده */
    void bridgeOnTheme(final String colorHex, final String mode) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                int color = parseColor(colorHex, "light".equals(mode) ? Ui.C_BG_L : Ui.C_BG);
                webMode = "light".equals(mode) ? "light" : "dark";
                webBg = color;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_THEME, webMode).apply();
                applyThemeChrome(color, webMode);
            }
        });
    }

    private static int parseColor(String hex, int fallback) {
        try {
            if (hex != null && hex.length() >= 4 && hex.charAt(0) == '#') {
                return Color.parseColor(hex.length() == 4 ? "#F" + hex.substring(1) : hex);
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    // ---------- رنگ‌های سیستم — همیشه هماهنگ با تم سایت ----------

    private void applyThemeChrome(int bg, String mode) {
        try {
            boolean light = "light".equals(mode);
            Window w = getWindow();

            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
            if (web != null) web.setBackgroundColor(bg);

            int flags = w.getDecorView().getSystemUiVisibility();
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(flags);

            // نوار پیشرفت و صفحهٔ خطا هم با تم سایت
            int fire = light ? Ui.C_FIRE_L : Ui.C_FIRE;
            if (progress != null) {
                progress.setProgressTintList(ColorStateList.valueOf(fire));
            }
            themeErrorOverlay();
        } catch (Throwable ignored) {
            // رنگ نوارها هرگز نباید اپ را ببندد
        }
    }

    private void themeErrorOverlay() {
        if (errTitle == null) return;
        boolean light = "light".equals(webMode);
        errorOverlay.setBackgroundColor(webBg);
        errTitle.setTextColor(light ? Ui.C_TEXT_L : Ui.C_TEXT);
        errSub.setTextColor(light ? Ui.C_MUTED_L : Ui.C_MUTED);

        /* دکمه‌ها مثل سایت: در دارک fire نارنجی (#FF4D00) و در لایت سبز (#00B34D) */
        int fire = light ? Ui.C_FIRE_L : Ui.C_FIRE;
        int firePressed = light ? 0xFF008A38 : Ui.C_FIRE_DK;
        int ink = light ? Ui.C_INK_L : Ui.C_INK;

        android.graphics.drawable.StateListDrawable prim =
                new android.graphics.drawable.StateListDrawable();
        prim.addState(new int[]{android.R.attr.state_pressed},
                Ui.cutDrawable(this, firePressed, 0, 10, 0));
        prim.addState(new int[0], Ui.cutDrawable(this, fire, 0, 10, 0));
        btnRetry.setBackground(prim);
        btnRetry.setTextColor(ink);

        int border2 = light ? 0xFFBCDCCB : Ui.C_BORDER2;
        int pressedFill = light ? 0x2100B34D : 0x21FF4D00;
        android.graphics.drawable.StateListDrawable ghost =
                new android.graphics.drawable.StateListDrawable();
        ghost.addState(new int[]{android.R.attr.state_pressed},
                Ui.cutDrawable(this, pressedFill, fire, 10, 1));
        ghost.addState(new int[0], Ui.cutDrawable(this, 0x00000000, border2, 10, 1));
        btnChangePanel.setBackground(ghost);
        btnChangePanel.setTextColor(light ? 0xFF335146 : Ui.C_TEXT2);
        if (errMono != null) errMono.setVisibility(View.VISIBLE);
    }

    // ---------- اینتنت‌ها (اشتراک لینک یوتیوب/آپارات) ----------

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            CharSequence ext = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String text = ext != null ? ext.toString() : "";
            String link = extractUrl(text);
            if (link != null) {
                String path = pathForUrl(Uri.parse(link));
                if (path != null) pendingPath = path;
                else toast(getString(R.string.unsupported_link));
            } else {
                toast(getString(R.string.unsupported_link));
            }
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            String path = pathForUrl(intent.getData());
            if (path != null) pendingPath = path;
            else openExternal(intent.getData().toString());
        }
    }

    /** اولین نشانی http(s) داخل متن */
    static String extractUrl(String text) {
        if (text == null) return null;
        String[] parts = text.split("\\s+");
        for (String p : parts) {
            if (p.startsWith("http://") || p.startsWith("https://")) return p;
            if (p.matches("^[\\w.-]*(youtube\\.com|youtu\\.be|aparat\\.com)/.*")) return "https://" + p;
        }
        return null;
    }

    /** لینک یوتیوب/آپارات → مسیر داخلی پنل */
    static String pathForUrl(Uri u) {
        try {
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.") || host.startsWith("m.")) host = host.substring(host.indexOf('.') + 1);
            List<String> seg = u.getPathSegments();
            if (host.equals("youtu.be")) {
                String id = seg.isEmpty() ? null : seg.get(0);
                return isValidYtId(id) ? "/watch/youtube/" + id : null;
            }
            if (host.endsWith("youtube.com")) {
                if (seg.isEmpty()) return null;
                String head = seg.get(0);
                if (head.equals("watch")) {
                    String id = u.getQueryParameter("v");
                    if (isValidYtId(id)) return "/watch/youtube/" + id;
                    if (seg.size() > 1 && isValidYtId(seg.get(1))) return "/watch/youtube/" + seg.get(1);
                    return null;
                }
                if ((head.equals("shorts") || head.equals("live") || head.equals("embed") || head.equals("v"))
                        && seg.size() > 1) {
                    String id = seg.get(1);
                    return isValidYtId(id) ? "/watch/youtube/" + id : null;
                }
                if (head.equals("channel") && seg.size() > 1) {
                    return "/channel/youtube/" + seg.get(1);
                }
            }
            if (host.endsWith("aparat.com")) {
                if (!seg.isEmpty() && seg.get(0).equals("v") && seg.size() > 1) {
                    String id = seg.get(1);
                    if (id.matches("[A-Za-z0-9_-]{3,24}")) return "/watch/aparat/" + id;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isValidYtId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{6,20}");
    }

    // ---------- تمام‌صفحه (پلیر) ----------

    private void enterFullscreen() {
        try {
            progress.setVisibility(View.GONE);
            fullscreenContainer = new FrameLayout(this);
            fullscreenContainer.setBackgroundColor(Color.BLACK);
            fullscreenContainer.addView(customView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ((ViewGroup) getWindow().getDecorView()).addView(fullscreenContainer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } catch (Throwable ignored) {
            // روی بعضی رام‌ها چرخش قفل است — ویدیو باید در هر حال پخش شود
        }
        hideSystemBars();
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private void exitFullscreen() {
        if (customView == null) {
            if (customCallback != null) {
                customCallback.onCustomViewHidden();
                customCallback = null;
            }
            return;
        }
        try {
            ((ViewGroup) fullscreenContainer).removeView(customView);
            ((ViewGroup) getWindow().getDecorView()).removeView(fullscreenContainer);
        } catch (Exception ignored) {}
        fullscreenContainer = null;
        customView = null;
        if (customCallback != null) {
            customCallback.onCustomViewHidden();
            customCallback = null;
        }
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        showSystemBars();
    }

    private void hideSystemBars() {
        try {
            Window w = getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                w.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Throwable ignored) {}
    }

    private void showSystemBars() {
        try {
            Window w = getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null) c.show(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
            } else {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable ignored) {}
        applyThemeChrome(webBg, webMode);
    }

    // ---------- دانلود ----------

    private void startDownload(String url, String ua, String contentDisposition, String mime) {
        try {
            android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(Uri.parse(url));
            req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String name = URLUtil.guessFileName(url, contentDisposition, mime);
            req.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, name);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) req.addRequestHeader("Cookie", cookies);
            if ((ua == null || ua.isEmpty()) && web != null) ua = web.getSettings().getUserAgentString();
            if (ua != null) req.addRequestHeader("User-Agent", ua);
            if (mime != null && mime.contains("/")) req.setMimeType(mime);
            android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                toast(getString(R.string.download_started));
                return;
            }
        } catch (Exception ignored) {}
        openExternal(url);
    }

    // ---------- متفرقه ----------

    void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    void openExternal(String url) {
        if (url == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast(getString(R.string.no_app_found));
        }
    }

    private void gotoSetup() {
        startActivity(new Intent(this, SetupActivity.class));
        finish();
    }

    private void showError() {
        if (errorOverlay != null) errorOverlay.setVisibility(View.VISIBLE);
    }

    // ---------- انتخاب فایل (بازیابی بکاپ) ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
