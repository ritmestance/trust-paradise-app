package ir.trustparadise.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * The Trust Paradise management app: a thin window around https://app.trustparadise.ir.
 * Everything (pages, offline copies, the outbox) lives in the web app itself; this shell only
 * adds what a browser tab would: photo picking and the camera, downloads, the back button,
 * and opening phone / Telegram / Instagram / other links outside.
 */
public class MainActivity extends Activity {
    static final String HOST = "app.trustparadise.ir";
    static final String HOME = "https://" + HOST + "/";
    static final int PICK = 7;

    WebView web;
    ValueCallback<Uri[]> pending;
    Uri cameraUri;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF150E0A);   // no white flash before the first page draws
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " TrustParadiseApp/" + BuildConfig.VERSION_NAME);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("https".equals(u.getScheme()) && HOST.equals(u.getHost())) return false;
                openOutside(u);
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                // Only when even the saved copy is missing (first start with no internet).
                if (req.isForMainFrame()) {
                    v.loadDataWithBaseURL(HOME, OFFLINE_PAGE, "text/html", "utf-8", null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                Intent pick = p.createIntent();
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                Intent chooser = Intent.createChooser(pick, "انتخاب عکس");
                Intent camera = cameraIntent(p);
                if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                try {
                    startActivityForResult(chooser, PICK);
                } catch (ActivityNotFoundException e) {
                    pending = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                String name = URLUtil.guessFileName(url, disposition, mime);
                r.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                r.addRequestHeader("User-Agent", ua);
                r.setMimeType(mime);
                r.setTitle(name);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "در حال دانلود: " + name, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                openOutside(Uri.parse(url));
            }
        });

        if (state != null) web.restoreState(state);
        else web.loadUrl(startUrl(getIntent()));
    }

    String startUrl(Intent i) {
        Uri u = i == null ? null : i.getData();
        if (u != null && HOST.equals(u.getHost())) return u.toString();
        return HOME;
    }

    Intent cameraIntent(WebChromeClient.FileChooserParams p) {
        boolean images = false;
        for (String t : p.getAcceptTypes()) if (t.isEmpty() || t.startsWith("image")) images = true;
        if (!images) return null;
        Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (cam.resolveActivity(getPackageManager()) == null) return null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "trust-" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        } catch (Exception e) {
            cameraUri = null;
        }
        if (cameraUri == null) return null;
        cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
        cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        return cam;
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != PICK || pending == null) { super.onActivityResult(code, result, data); return; }
        Uri[] out = null;
        if (result == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                out = new Uri[n];
                for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) {
                out = new Uri[]{data.getData()};
            } else if (cameraUri != null) {
                out = new Uri[]{cameraUri};   // the camera wrote straight into cameraUri
            }
        } else if (cameraUri != null) {
            getContentResolver().delete(cameraUri, null, null);   // cancelled: don't leave an empty photo
        }
        pending.onReceiveValue(out);
        pending = null;
        cameraUri = null;
    }

    void openOutside(Uri u) {
        Intent i = new Intent(Intent.ACTION_VIEW, u);
        if ("tel".equals(u.getScheme())) i = new Intent(Intent.ACTION_DIAL, u);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "برنامه‌ای برای باز کردن این پیوند پیدا نشد", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getData() != null) web.loadUrl(startUrl(i));
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    static final String OFFLINE_PAGE =
        "<!doctype html><html lang='fa' dir='rtl'><meta name='viewport' content='width=device-width,initial-scale=1'>"
        + "<body style='font-family:sans-serif;background:radial-gradient(500px 360px at 50% 35%,rgba(214,168,101,.12),transparent 70%),#150e0a;color:#eee2cf;display:grid;place-items:center;min-height:90vh;margin:0;text-align:center;padding:20px;line-height:1.9'>"
        + "<div style='max-width:340px'><h2 style='font-weight:500;color:#f6ecdc'>اینترنت وصل نیست</h2><p style='color:#a39079;font-size:15px'>برای اولین بار باز کردن برنامه به اینترنت نیاز است.<br>بعد از یک بار ورود، صفحه‌هایی که باز کرده‌اید بدون اینترنت هم دیده می‌شوند.</p>"
        + "<p><a href='" + HOME + "' style='display:inline-block;padding:10px 24px;border-radius:10px;background:linear-gradient(135deg,#e0b574,#a8722f);color:#1a110a;text-decoration:none;font-weight:bold'>دوباره امتحان کن</a></p></div></body></html>";
}
