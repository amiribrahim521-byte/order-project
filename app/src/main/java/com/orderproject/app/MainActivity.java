package com.orderproject.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Order Project - Android app. It is the same web app, shown full screen in its own app (own icon, no Chrome, no address bar).
 * The server address is asked once and remembered. Image upload and camera capture work like in the browser.
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 1;
    private WebView web;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;
    private boolean errorShown = false;

    /** Lets the web app's Settings page call "Change server address". */
    public class Bridge {
        @JavascriptInterface
        public void changeServer() {
            runOnUiThread(new Runnable() { public void run() { askAddress(); } });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0A2F73);
        prefs = getSharedPreferences("op", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // localStorage + IndexedDB (the app keeps its local copy there)
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "OPAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String base = prefs.getString("url", "");
                if (base.isEmpty()) return false;
                String host = r.getUrl().getHost(), baseHost = Uri.parse(base).getHost();
                if (host != null && host.equals(baseHost)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception e) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showError();
            }

            @Override
            public void onPageFinished(WebView v, String url) { errorShown = false; }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;

                Intent cam = null;
                try {
                    File dir = new File(getCacheDir(), "images");
                    dir.mkdirs();
                    File f = File.createTempFile("cap", ".jpg", dir);
                    cameraUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", f);
                    cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                    cam.setClipData(ClipData.newRawUri("", cameraUri));
                    cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    cam = null;
                    cameraUri = null;
                }

                Intent chooser;
                if (p.isCaptureEnabled() && cam != null) {
                    chooser = cam; // "Capture Image": straight to the camera
                } else {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("image/*");
                    chooser = Intent.createChooser(pick, "Choose image");
                    if (cam != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                }
                try {
                    startActivityForResult(chooser, REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }
        });

        String url = prefs.getString("url", "");
        if (url.isEmpty()) askAddress(); else web.loadUrl(url);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_FILE || filePathCallback == null) {
            super.onActivityResult(req, res, data);
            return;
        }
        Uri[] results = null;
        if (res == RESULT_OK) {
            if (data != null && (data.getData() != null || data.getClipData() != null)) {
                results = WebChromeClient.FileChooserParams.parseResult(res, data);
            } else if (cameraUri != null) {
                results = new Uri[]{cameraUri};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
        cameraUri = null;
    }

    private String normalize(String s) {
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void askAddress() {
        final String saved = prefs.getString("url", "");
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint("http://192.168.25.104:3000");
        in.setText(saved);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Server address")
                .setMessage("Type the address shown in the PC app: Settings > PC Server > \"Open on phone\".")
                .setView(in)
                .setCancelable(!saved.isEmpty())
                .setPositiveButton("Save", (d, w) -> {
                    String u = normalize(in.getText().toString());
                    if (u.isEmpty()) { askAddress(); return; }
                    prefs.edit().putString("url", u).apply();
                    errorShown = false;
                    web.loadUrl(u);
                });
        if (!saved.isEmpty()) b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void showError() {
        if (errorShown) return;
        errorShown = true;
        new AlertDialog.Builder(this)
                .setTitle("Cannot reach the server")
                .setMessage("Check that the PC is on, the Order Project server is running, and the phone is on the same Wi-Fi (or connected to the internet address).")
                .setCancelable(false)
                .setPositiveButton("Retry", (d, w) -> { errorShown = false; web.loadUrl(prefs.getString("url", "")); })
                .setNeutralButton("Change address", (d, w) -> { errorShown = false; askAddress(); })
                .show();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
