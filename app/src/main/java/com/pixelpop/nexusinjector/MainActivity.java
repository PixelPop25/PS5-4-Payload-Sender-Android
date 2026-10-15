package com.pixelpop.nexusinjector;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> mFilePathCallback;
    private final static int FILECHOOSER_RESULTCODE = 1;
    private final static int SAVE_LOG_REQUEST = 2;
    private String pendingSaveText = null;
    private String pendingSaveName = null;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Allow free rotation on all devices (phones + tablets of any size)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        try { s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); } catch (Throwable ignored) {}

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (mFilePathCallback != null) mFilePathCallback.onReceiveValue(null);
                mFilePathCallback = cb;
                Intent intent = params.createIntent();
                try {
                    startActivityForResult(intent, FILECHOOSER_RESULTCODE);
                } catch (ActivityNotFoundException e) {
                    mFilePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new AndroidTCPInterface(), "AndroidTCP");
        webView.addJavascriptInterface(new NativeStorage(), "AndroidNativeStorage");
        webView.addJavascriptInterface(new NativeHttp(), "AndroidNativeHttp");

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SAVE_LOG_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && pendingSaveText != null) {
                Uri uri = data.getData();
                try {
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(pendingSaveText.getBytes(StandardCharsets.UTF_8));
                        os.close();
                        final String toastMsg = "Saved " + (pendingSaveName != null ? pendingSaveName : "log");
                        if (webView != null) {
                            webView.post(() -> webView.evaluateJavascript(
                                "(function(){try{showToast(" + org.json.JSONObject.quote(toastMsg) + ");}catch(e){}})();",
                                null));
                        }
                    }
                } catch (Exception e) {
                    if (webView != null) {
                        webView.post(() -> webView.evaluateJavascript(
                            "(function(){try{showToast('Save failed','error');}catch(e){}})();",
                            null));
                    }
                }
            } else if (resultCode != Activity.RESULT_OK) {
                // user cancelled — silent
            }
            pendingSaveText = null;
            pendingSaveName = null;
            return;
        }
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (mFilePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) results = new Uri[]{Uri.parse(dataString)};
                else if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
            mFilePathCallback.onReceiveValue(results);
            mFilePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    public class AndroidTCPInterface {
        private volatile String lastCapture = "";

        private String errTag(Exception e) {
            String m = e.getMessage() != null ? e.getMessage() : "";
            String low = m.toLowerCase();
            if (low.contains("econnrefused") || low.contains("connection refused") || low.contains("failed to connect"))
                return "ERR:CONN_REFUSED";
            if (low.contains("etimedout") || low.contains("timed out"))
                return "ERR:TIMEOUT";
            if (low.contains("enetunreach") || low.contains("ehostunreach") || low.contains("network is unreachable") || low.contains("no route"))
                return "ERR:UNREACHABLE";
            return "ERR:SEND_FAIL";
        }

        /** Upload only (legacy). */
        @JavascriptInterface
        public String sendBase64(String host, int port, String b64) {
            Socket sock = null;
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                sock = new Socket();
                sock.connect(new InetSocketAddress(host, port), 8000);
                sock.setSoTimeout(30000);
                sock.getOutputStream().write(data);
                sock.getOutputStream().flush();
                try { sock.shutdownOutput(); } catch (Exception ignored) {}
                return "ok";
            } catch (Exception e) {
                return errTag(e);
            } finally {
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
            }
        }

        /**
         * Upload ELF/payload then read stdout/stderr that elfldr wires to this socket
         * (same model as send_elf_capture.py). idleMs: max wait between recv chunks.
         */
        @JavascriptInterface
        public String sendAndCaptureBase64(String host, int port, String b64, int idleMs) {
            Socket sock = null;
            StringBuilder log = new StringBuilder();
            try {
                if (idleMs < 1000) idleMs = 1000;
                if (idleMs > 300000) idleMs = 300000;
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                sock = new Socket();
                sock.connect(new InetSocketAddress(host, port), 8000);
                sock.setSoTimeout(idleMs);
                sock.getOutputStream().write(data);
                sock.getOutputStream().flush();
                try { sock.shutdownOutput(); } catch (Exception ignored) {}

                byte[] buf = new byte[65536];
                int n;
                try {
                    while ((n = sock.getInputStream().read(buf)) > 0) {
                        log.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                } catch (java.net.SocketTimeoutException ste) {
                    if (log.length() == 0)
                        log.append("[send] no socket output for ").append(idleMs).append("ms\n");
                    else
                        log.append("\n[send] recv idle timeout after ").append(log.length()).append(" bytes\n");
                }

                lastCapture = log.toString();
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("bytes", lastCapture.length());
                o.put("log", lastCapture);
                return o.toString();
            } catch (Exception e) {
                lastCapture = log.toString();
                try {
                    JSONObject o = new JSONObject();
                    o.put("ok", false);
                    o.put("error", errTag(e));
                    o.put("log", lastCapture);
                    return o.toString();
                } catch (Exception je) {
                    return "{\"ok\":false,\"error\":\"ERR:SEND_FAIL\",\"log\":\"\"}";
                }
            } finally {
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
            }
        }

        @JavascriptInterface
        public String getLastCapture() {
            return lastCapture != null ? lastCapture : "";
        }

        @JavascriptInterface
        public void clearLastCapture() {
            lastCapture = "";
        }

        @JavascriptInterface
        public String copyToClipboard(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return "ERR:CLIP";
                cm.setPrimaryClip(ClipData.newPlainText("nexus-log", text != null ? text : ""));
                return "ok";
            } catch (Exception e) {
                return "ERR:CLIP";
            }
        }

        /**
         * Opens the system Save As dialog (ACTION_CREATE_DOCUMENT).
         * Actual write happens in onActivityResult when the user picks a location.
         */
        @JavascriptInterface
        public String saveLogFile(String text, String filename) {
            try {
                if (text == null) text = "";
                if (filename == null || filename.length() == 0)
                    filename = "nexus-socket-log.txt";
                if (!filename.toLowerCase().endsWith(".txt"))
                    filename = filename + ".txt";
                // sanitize for EXTRA_TITLE only
                String safe = filename.replaceAll("[\\/:*?\"<>|]", "_");
                pendingSaveText = text;
                pendingSaveName = safe;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, safe);
                runOnUiThread(() -> {
                    try {
                        startActivityForResult(intent, SAVE_LOG_REQUEST);
                    } catch (ActivityNotFoundException e) {
                        // fallback: write into app documents
                        try {
                            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                            if (dir == null) dir = getFilesDir();
                            if (!dir.exists()) dir.mkdirs();
                            File out = new File(dir, safe);
                            FileOutputStream fos = new FileOutputStream(out);
                            fos.write(pendingSaveText.getBytes(StandardCharsets.UTF_8));
                            fos.close();
                            pendingSaveText = null;
                            if (webView != null) {
                                final String path = out.getAbsolutePath();
                                webView.post(() -> webView.evaluateJavascript(
                                    "(function(){try{showToast('Saved: " + path.replace("'", "\\'") + "');}catch(e){}})();",
                                    null));
                            }
                        } catch (Exception ex) {
                            pendingSaveText = null;
                        }
                    }
                });
                return "ok";
            } catch (Exception e) {
                pendingSaveText = null;
                return "ERR:SAVE";
            }
        }

        @JavascriptInterface public String probe(String host, int port) {
            Socket sock = null;
            try {
                sock = new Socket();
                sock.connect(new InetSocketAddress(host, port), 1500);
                return "ok";
            } catch (Exception e) {
                return "ERR:port closed";
            } finally {
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
            }
        }
    }

    public class NativeStorage {
        private android.content.SharedPreferences prefs() {
            return getSharedPreferences("nexus_store", MODE_PRIVATE);
        }

        @JavascriptInterface public String getItem(String key) {
            return prefs().getString(key, null);
        }

        @JavascriptInterface public void setItem(String key, String value) {
            prefs().edit().putString(key, value).apply();
        }

        @JavascriptInterface public void removeItem(String key) {
            prefs().edit().remove(key).apply();
        }

        /** Batch read — one bridge call for many keys. JSON object key→value|null */
        @JavascriptInterface public String getItems(String keysJsonArray) {
            try {
                JSONArray keys = new JSONArray(keysJsonArray);
                JSONObject out = new JSONObject();
                android.content.SharedPreferences p = prefs();
                for (int i = 0; i < keys.length(); i++) {
                    String k = keys.getString(i);
                    out.put(k, p.contains(k) ? p.getString(k, null) : JSONObject.NULL);
                }
                return out.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    public class NativeHttp {
        private String fetchB64(String url) {
            try {
                if (!url.startsWith("https://") && !url.startsWith("http://")) return "ERR:bad url";
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Nexus-Android");
                int code = c.getResponseCode();
                if (code != 200) return "ERR:HTTP " + code;
                InputStream in = c.getInputStream();
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int k, total = 0;
                while ((k = in.read(b)) > 0) {
                    total += k;
                    if (total > 64 * 1024 * 1024) return "ERR:file too large";
                    o.write(b, 0, k);
                }
                in.close();
                return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "ERR:" + (e.getMessage() != null ? e.getMessage() : "network");
            }
        }

        @JavascriptInterface public String getB64(String url) { return fetchB64(url); }

        @JavascriptInterface public void getB64Async(final String url, final String callbackId) {
            new Thread(new Runnable() {
                @Override public void run() {
                    final String result = fetchB64(url);
                    webView.post(new Runnable() {
                        @Override public void run() {
                            String payload = JSONObject.quote(result);
                            String idJson = JSONObject.quote(callbackId);
                            webView.evaluateJavascript(
                                "(function(){try{window.__nativeHttpCb&&window.__nativeHttpCb(" + idJson + "," + payload + ");}catch(e){}})();",
                                null);
                        }
                    });
                }
            }, "nexus-http").start();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
