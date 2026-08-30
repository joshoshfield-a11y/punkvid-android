package com.punkvid.studio;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;

/**
 * PunkVid Studio: Native Edition — WebView shell (Phase 0/1 per spec v2.0).
 * Loads the JS visual engine from assets and provides the native bridge:
 *  - PunkVidNative.saveVideo(base64, filename)  → MediaStore Movies/PunkVid
 *  - PunkVidNative.shareLast()                  → Android share sheet (TikTok/IG/YT/...)
 */
public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri lastVideoUri = null;
    private static final int FILE_CHOOSER_REQ = 1001;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String[] types = params.getAcceptTypes();
                i.setType(types != null && types.length > 0 && !types[0].isEmpty()
                        ? types[0].split(";")[0] : "*/*");
                startActivityForResult(Intent.createChooser(i, "Select file"), FILE_CHOOSER_REQ);
                return true;
            }
        });

        webView.addJavascriptInterface(new PunkVidBridge(), "PunkVidNative");
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQ && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    /** JS ↔ native bridge. Methods run on a background binder thread. */
    class PunkVidBridge {

        @JavascriptInterface
        public void saveVideo(String base64, String filename) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String mime = filename != null && filename.endsWith(".webm")
                        ? "video/webm" : "video/mp4";

                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
                v.put(MediaStore.Video.Media.MIME_TYPE, mime);
                if (Build.VERSION.SDK_INT >= 29) {
                    v.put(MediaStore.Video.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MOVIES + "/PunkVid");
                    v.put(MediaStore.Video.Media.IS_PENDING, 1);
                }
                Uri uri = getContentResolver().insert(
                        MediaStore.Video.Media.getContentUri(
                                MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                if (uri == null) throw new Exception("MediaStore insert failed");

                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new Exception("Cannot open output stream");
                    out.write(bytes);
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, done, null, null);
                }
                lastVideoUri = uri;
                final String name = filename;
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Saved " + name + " to Movies/PunkVid", Toast.LENGTH_LONG).show());
            } catch (final Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void shareLast() {
            if (lastVideoUri == null) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Export a video first", Toast.LENGTH_SHORT).show());
                return;
            }
            runOnUiThread(() -> {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("video/*");
                share.putExtra(Intent.EXTRA_STREAM, lastVideoUri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(share, "Share PunkVid"));
            });
        }
    }
}
