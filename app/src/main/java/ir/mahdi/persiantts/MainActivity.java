package ir.mahdi.persiantts;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;


public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.parseColor("#4A3AD8"));
        }

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setBackgroundColor(Color.parseColor("#F2F2F6"));
        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.handleBack?window.handleBack():false)", value -> {
            final boolean handled = "true".equals(value);
            runOnUiThread(() -> {
                if (!handled) MainActivity.super.onBackPressed();
            });
        });
    }

    private File audioDir() {
        File d = new File(getFilesDir(), "audio");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public class Bridge {

        @JavascriptInterface
        public String loadPref(String key) {
            return getSharedPreferences("tts", Context.MODE_PRIVATE).getString(key, null);
        }

        /** App version shown in Settings; comes from build.gradle. */
        @JavascriptInterface
        public String appVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void savePref(String key, String value) {
            getSharedPreferences("tts", Context.MODE_PRIVATE).edit().putString(key, value).apply();
        }

        /** Save into app-private audio dir (for history playback). Returns the file name or "". */
        @JavascriptInterface
        public String saveAudio(String name, String b64) {
            try {
                File f = new File(audioDir(), name);
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                try (OutputStream os = new FileOutputStream(f)) {
                    os.write(data);
                }
                return f.getName();
            } catch (Exception e) {
                return "";
            }
        }

        /** Resolve a stored audio name (absolute or relative) for playback. */
        @JavascriptInterface
        public String audioUrl(String name) {
            if (name == null || name.isEmpty()) return "";
            try {
                File f = new File(name);
                if (!f.isAbsolute()) f = new File(audioDir(), name);
                return f.exists() ? "file://" + f.getAbsolutePath() : "";
            } catch (Exception e) {
                return "";
            }
        }

        /** Read stored audio as base64 (reliable playback in WebView). "" if missing. */
        @JavascriptInterface
        public String readAudio(String name) {
            try {
                if (name == null || name.isEmpty()) return "";
                File f = new File(name);
                if (!f.isAbsolute()) f = new File(audioDir(), name);
                if (!f.exists() || f.length() == 0) return "";
                byte[] b = new byte[(int) f.length()];
                try (InputStream in = new FileInputStream(f)) {
                    int off = 0, r;
                    while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
                }
                return Base64.encodeToString(b, Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void clearAudio() {
            File[] fs = audioDir().listFiles();
            if (fs != null) for (File f : fs) f.delete();
        }

        /** Delete one stored audio file (used by the per-item history delete). True on success. */
        @JavascriptInterface
        public boolean deleteAudio(String name) {
            try {
                if (name == null || name.isEmpty()) return false;
                File f = new File(name);
                if (!f.isAbsolute()) f = new File(audioDir(), name);
                // never touch anything outside the app-private audio dir
                String base = audioDir().getCanonicalPath();
                String path = f.getCanonicalPath();
                if (!path.startsWith(base + File.separator)) return false;
                return f.isFile() ? f.delete() : true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Save into public Downloads. Returns full path or "" on failure. */
        @JavascriptInterface
        public String saveFile(String name, String mime, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = getContentResolver();
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/PersianTTS");
                    cv.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "";
                    try (OutputStream os = cr.openOutputStream(uri)) {
                        if (os == null) return "";
                        os.write(data);
                    }
                    cv.clear();
                    cv.put(MediaStore.Downloads.IS_PENDING, 0);
                    cr.update(uri, cv, null, null);
                    return "Downloads/" + name;
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), "PersianTTS");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, name);
                    try (OutputStream os = new FileOutputStream(f)) {
                        os.write(data);
                    }
                    return f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }
}
