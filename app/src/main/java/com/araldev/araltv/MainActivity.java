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
    private TextView errTitle, errSub;
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
    private boolean restarting = false;

    /** اگر اپ ۸ ثانیه زنده ماند، اجرا «سالم» حساب می‌شود (BootGuard) */
    private final Runnable healthyRunnable = new Runnable() {
        @Override public void run() { BootGuard.markHealthy(MainActivity.this); }
    };

    // ---------- چرخهٔ حیات ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(getApplicationContext());
        BootGuard.markStart(this);
        try {
            initScreen();
        } catch (Throwable t) {
            // هر خطای راه‌اندازی: ثبت در last_crash.txt و رفتن به صفحهٔ تنظیم (نه کرش)
            CrashGuard.record(this, t);
            fallbackToSetup();
        }
    }

    private void initScreen() {
        try { Ui.init(getApplicationContext()); } catch (Throwable ignored) {}

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        panelUrl = prefs.getString(KEY_URL, null);
        if (panelUrl == null || panelUrl.trim().isEmpty()) {
            BootGuard.markHealthy(this);
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        panelUrl = panelUrl.trim();
        try {
            Uri pu = Uri.parse(panelUrl);
            if (pu.getHost() != null) panelHost = pu.getHost().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {}

        // تم آخرین بار → پیش از بارگذاری صفحه، نوارها هماهنگ باشند (بدون فلش سفید)
        String savedTheme = prefs.getString(KEY_THEME, "dark");
        webMode = "light".equals(savedTheme) ? "light" : "dark";
        webBg = "light".equals(webMode) ? Ui.C_BG_L : Ui.C_BG;

        setContentView(R.layout.activity_main);
        bindViews();

        // بخش‌های ظاهری: خطایشان هرگز نباید اپ را ببندد
        try { styleBrand(); } catch (Throwable t) { CrashGuard.record(this, t); }
        try { applyThemeChrome(webBg, webMode); } catch (Throwable t) { CrashGuard.record(this, t); }

        setupWebView();

        try { handleIntent(getIntent()); } catch (Throwable t) { CrashGuard.record(this, t); }
        web.loadUrl(panelUrl);

        if (pendingPath != null) toast(getString(R.string.opening_video));

        handler.postDelayed(healthyRunnable, 8000);
    }

    /** وقتی راه‌اندازی شکست بخورد: صفحهٔ تنظیم آدرس (با پیام) به‌جای کرش */
    private void fallbackToSetup() {
        try {
            Intent i = new Intent(this, SetupActivity.class);
            i.putExtra(SetupActivity.EXTRA_SAFE, true);
            startActivity(i);
        } catch (Throwable ignored) {}
        finish();
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
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(healthyRunnable);
        // خروج عادی کاربر = اجرای سالم (ولی نه در بازآغاز بعد از کرش رندر)
        if (isFinishing() && !restarting) BootGuard.markHealthy(this);
        try {
            if (web != null) {
                web.loadUrl("about:blank");
                android.view.ViewParent parent = web.getParent();
                if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(web);
                web.destroy();
            }
        } catch (Throwable ignored) {}
        web = null;
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

        btnRetry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                errorOverlay.setVisibility(View.GONE);
                pageReady = false;
                web.reload();
            }
        });
        btnChangePanel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gotoSetup(); }
        });
    }

    /** برند صفحهٔ خطا با فونت‌ها و رنگ‌های خود سایت */
    private void styleBrand() {
        Ui.applyFonts(errorOverlay);

        TextView brand = findViewById(R.id.errBrand);
        SpannableString s = SpannableString.valueOf("Aral TV");
        s.setSpan(new ForegroundColorSpan(Ui.C_FIRE), 5, 8, 0); // " TV" نارنجی مثل سایت
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
        try {
            String ua = s.getUserAgentString();
            if (ua != null) s.setUserAgentString(ua.replace("; wv", "").trim());
        } catch (Throwable ignored) {}

        web.setBackgroundColor(webBg);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(web, true);
        } catch (Throwable ignored) {}

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
                BootGuard.markHealthy(MainActivity.this); // صفحه بارگذاری شد → اجرا سالم است
                if (errorOverlay != null) errorOverlay.setVisibility(View.GONE);
                injectBridge();
                pushPendingToPage();
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
                // رندرر وب‌ویو مرد (کرش یا کمبود حافظه). اگر false برگردانیم سیستم کل اپ را
                // می‌کُشد؛ پس همیشه true و بازآغاز تمیز اپ (BootGuard جلوی حلقه را می‌گیرد).
                restarting = true;
                try {
                    Intent i = new Intent(MainActivity.this, SplashActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                } catch (Throwable ignored) {}
                finish();
                return true;
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
        if (web == null) return;
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
        if (web == null || pendingPath == null || !pageReady) return;
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
                try {
                    int color = parseColor(colorHex, "light".equals(mode) ? Ui.C_BG_L : Ui.C_BG);
                    webMode = "light".equals(mode) ? "light" : "dark";
                    webBg = color;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_THEME, webMode).apply();
                    applyThemeChrome(color, webMode);
                } catch (Throwable ignored) {}
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
        findViewById(R.id.errMono).setVisibility(View.VISIBLE);
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
        progress.setVisibility(View.GONE);
        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.addView(customView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((ViewGroup) getWindow().getDecorView()).addView(fullscreenContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
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
    }

    private void showSystemBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c != null) c.show(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
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
            if (ua == null || ua.isEmpty()) ua = web.getSettings().getUserAgentString();
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
