/* Aral TV Android Bridge v1.7 — پل نامرئی بین سایت و پوستهٔ اندروید.
   اصل مهم: «هیچ چیز از ظاهر سایت عوض نمی‌شود» — نه CSS، نه چیدمان.
   فقط سه کارِ پشت‌صحنه:
     ۱) همگام‌سازی تم سایت با رنگ نوارهای سیستم اندروید
     ۲) باز کردن مسیر در انتظار (لینک یوتیوب/آپارات که از بیرون آمده) بعد از ورود
     ۳) دانلود blob (بکاپ و …) — چون CSP صفحه اجازهٔ fetch(blob:) نمی‌دهد */
(function () {
  'use strict';

  if (window.__aralAppBridge) {
    try { window.__aralAppBridge.boot(); } catch (e) {}
    return;
  }

  var api = { boot: boot };
  window.__aralAppBridge = api;

  /* ---------- ۱) تم ----------
     سایت خودش <meta id="metaTheme"> را با هر تم به‌روز می‌کند
     (دارک ‎#070707 / لایت ‎#f4faf6). ما همان رنگ را به نیتیو می‌دهیم
     تا نوار وضعیت و نوار پایین اندروید همیشه با سایت یکدست باشند. */
  var FALLBACK = { dark: '#070707', light: '#f4faf6' };

  function metaColor() {
    var m = document.getElementById('metaTheme');
    if (m) {
      var c = m.getAttribute('content');
      if (c && c.charAt(0) === '#') return c;
    }
    return FALLBACK[mode()];
  }

  function mode() {
    return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
  }

  function notifyTheme() {
    try { AralBridge.onTheme(metaColor(), mode()); } catch (e) { /* نیتیو آماده نیست */ }
  }

  function watchTheme() {
    var m = document.getElementById('metaTheme');
    if (m) {
      try {
        new MutationObserver(notifyTheme)
          .observe(m, { attributes: true, attributeFilter: ['content'] });
      } catch (e) {}
    }
    try {
      new MutationObserver(notifyTheme)
        .observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    } catch (e) {}
  }

  /* ---------- ۲) مسیر در انتظار ----------
     نیتیو آن را به window.__aralPending می‌گذارد (مثلاً /watch/youtube/xyz).
     تا وقتی کاربر وارد نشده (پوستهٔ سایت رندر نشده) صبر می‌کنیم؛
     نشانهٔ ورود: وجود .bnav — نوار پایین خودِ سایت. */
  var pendingTries = 0;

  function consumePending() {
    var p = window.__aralPending;
    if (!p) return;
    if (pendingTries++ > 150) { window.__aralPending = null; return; } // ~۹۰ ثانیه بعد رها کن
    if (!document.querySelector('.bnav')) return;   // هنوز در صفحهٔ ورودیم
    if (typeof navigate !== 'function') return;
    window.__aralPending = null;
    try { navigate(p); }
    catch (e) { try { location.assign(p); } catch (e2) {} }
  }

  /* ---------- ۳) دانلود blob → نیتیو (base64) ----------
     CSP صفحه fetch(blob:) را می‌بندد؛ به‌جای آن Blob های ساخته‌شده را
     با پُچ createObjectURL نگه می‌داریم و مستقیم می‌خوانیم. */
  var blobMap = {};
  try {
    var origCreate = URL.createObjectURL;
    URL.createObjectURL = function (obj) {
      var u = origCreate.call(URL, obj);
      try { blobMap[u] = obj; } catch (e) {}
      return u;
    };
  } catch (e) {}

  function readBlob(blob, name) {
    try {
      var fr = new FileReader();
      fr.onload = function () {
        try {
          var b64 = String(fr.result).split(',')[1] || '';
          AralBridge.saveFile(name, b64);
        } catch (err) {}
      };
      fr.readAsDataURL(blob);
    } catch (e) {}
  }

  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    if (href.indexOf('blob:') !== 0) return; /* لینک‌های واقعی را DownloadManager می‌گیرد */
    e.preventDefault();
    e.stopPropagation();
    var name = a.getAttribute('download') || 'aral-download.bin';
    var blob = blobMap[href];
    if (blob) { readBlob(blob, name); return; }
    try {
      fetch(href)
        .then(function (r) { return r.blob(); })
        .then(function (b) { readBlob(b, name); })
        .catch(function () {});
    } catch (err) {}
  }, true);

  /* ---------- شروع ---------- */
  function boot() {
    notifyTheme();
    consumePending();
  }

  setInterval(consumePending, 600);
  watchTheme();
  boot();
})();
