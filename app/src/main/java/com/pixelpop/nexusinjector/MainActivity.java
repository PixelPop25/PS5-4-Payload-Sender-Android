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
import java.util.Iterator;

public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> mFilePathCallback;
    private final static int FILECHOOSER_RESULTCODE = 1;

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
                String m = e.getMessage() != null ? e.getMessage() : "";
                String low = m.toLowerCase();
                if (low.contains("econnrefused") || low.contains("connection refused") || low.contains("failed to connect"))
                    return "ERR:CONN_REFUSED";
                if (low.contains("etimedout") || low.contains("timed out"))
                    return "ERR:TIMEOUT";
                if (low.contains("enetunreach") || low.contains("ehostunreach") || low.contains("network is unreachable") || low.contains("no route"))
                    return "ERR:UNREACHABLE";
                return "ERR:SEND_FAIL";
            } finally {
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
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
