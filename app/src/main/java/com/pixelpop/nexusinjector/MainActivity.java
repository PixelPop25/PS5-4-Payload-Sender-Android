package com.pixelpop.nexusinjector;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.*;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> mFilePathCallback;
    private final static int FILECHOOSER_RESULTCODE = 1;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Phones stay locked to portrait (the UI is designed for it).
        // Tablets (smallest width >= 600dp) are free to rotate / follow the sensor.
        if (getResources().getConfiguration().smallestScreenWidthDp >= 600) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // Keep status + navigation bars visible so Android gestures / 3-button nav stay usable
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); // video wallpaper autoplay
        
        webView.setWebViewClient(new WebViewClient());

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                if (fileChooserParams != null && fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.setType("*/*"); // CRUCIAL: Accepts ALL file extensions universally (.jar, .bin, etc.)

                try {
                    startActivityForResult(Intent.createChooser(intent, "Select Payload"), FILECHOOSER_RESULTCODE);
                } catch (ActivityNotFoundException e) {
                    mFilePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new AndroidTCPInterface(), "AndroidNativeTCP");
        webView.addJavascriptInterface(new WebHost(), "AndroidWebHost");
        webView.addJavascriptInterface(new NativeHttp(), "AndroidNativeHttp");
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (mFilePathCallback == null) return;
            Uri[] results = (resultCode == Activity.RESULT_OK) ? WebChromeClient.FileChooserParams.parseResult(resultCode, intent) : null;
            mFilePathCallback.onReceiveValue(results);
            mFilePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, intent);
        }
    }

    public class AndroidTCPInterface {
        @JavascriptInterface
        public void performRawStream(final String targetIp, final int targetPort, final String base64PayloadData) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Socket tcpSocket = null;
                    OutputStream rawOutputStream = null;
                    try {
                        byte[] rawPayloadBytes = Base64.decode(base64PayloadData, Base64.DEFAULT);
                        tcpSocket = new Socket();
                        tcpSocket.connect(new InetSocketAddress(targetIp, targetPort), 4000);
                        rawOutputStream = tcpSocket.getOutputStream();
                        rawOutputStream.write(rawPayloadBytes);
                        rawOutputStream.flush();
                        rawOutputStream.close();
                        tcpSocket.close();
                        sendCallbackToWebfront(true, "Payload injected successfully over raw native TCP.");
                    } catch (final Exception socketError) {
                        try {
                            if (rawOutputStream != null) rawOutputStream.close();
                            if (tcpSocket != null) tcpSocket.close();
                        } catch (Exception ignored) {}
                        sendCallbackToWebfront(false, "TCP Stream Interrupted: " + socketError.getMessage());
                    }
                }
            }).start();
        }

        private void sendCallbackToWebfront(final boolean outcomeSuccess, final String outcomeMessage) {
            webView.post(new Runnable() {
                @Override
                public void run() {
                    webView.loadUrl("javascript:onNativeResponse(" + outcomeSuccess + ", '" + outcomeMessage.replace("'", "\\'") + "')");
                }
            });
        }
    }


    // ── User-supplied file host: serves files the user added (e.g. their own WebKit page) over HTTP on the LAN ──
    public class WebHost {
        private ServerSocket server;
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private File root() { File d = new File(getFilesDir(), "webhost"); d.mkdirs(); return d; }
        private String safe(String n) { n = new File(n == null ? "" : n).getName(); return n.startsWith(".") ? "" : n; }

        @JavascriptInterface public String saveFile(String name, String b64) {
            try { name = safe(name); if (name.isEmpty()) return "bad name";
                FileOutputStream o = new FileOutputStream(new File(root(), name));
                o.write(Base64.decode(b64, Base64.DEFAULT)); o.close(); return "ok";
            } catch (Exception e) { return String.valueOf(e.getMessage()); }
        }
        @JavascriptInterface public void deleteFile(String name) { new File(root(), safe(name)).delete(); }
        @JavascriptInterface public String list() {
            JSONArray a = new JSONArray(); File[] fs = root().listFiles();
            if (fs != null) for (File f : fs) a.put(f.getName());
            return a.toString();
        }
        @JavascriptInterface public String ip() {
            try { for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            } catch (Exception ignored) {}
            return "";
        }
        @JavascriptInterface public synchronized String start(int port) {
            try { stop(); server = new ServerSocket(port); final ServerSocket ss = server;
                new Thread(new Runnable() { public void run() {
                    while (!ss.isClosed()) try { final Socket c = ss.accept(); pool.execute(new Runnable() { public void run() { serve(c); } }); } catch (Exception e) { break; }
                } }).start();
                return "ok";
            } catch (Exception e) { return String.valueOf(e.getMessage()); }
        }
        @JavascriptInterface public synchronized void stop() { try { if (server != null) server.close(); } catch (Exception ignored) {} server = null; }

        /**
         * Copy bundled WebKit autoloader pages from assets/webkit/ into the host root.
         * Offline — no network. hostPyUrl is ignored (kept for JS compatibility).
         */
        @JavascriptInterface public String seedWebkitAutoloader(String hostPyUrl) {
            try {
                File dir = root();
                String[] names = getAssets().list("webkit");
                JSONArray files = new JSONArray();
                if (names == null || names.length == 0) {
                    return "{\"ok\":false,\"error\":\"no bundled webkit assets\"}";
                }
                byte[] buf = new byte[32768];
                int k;
                for (String name : names) {
                    if (name == null || name.endsWith(".txt") || name.endsWith(".md")) continue;
                    String safeName = safe(name);
                    if (safeName.isEmpty()) continue;
                    InputStream in = getAssets().open("webkit/" + name);
                    FileOutputStream fos = new FileOutputStream(new File(dir, safeName));
                    while ((k = in.read(buf)) > 0) fos.write(buf, 0, k);
                    fos.close();
                    in.close();
                    files.put(safeName);
                }
                if (files.length() == 0) {
                    return "{\"ok\":false,\"error\":\"no webkit html bundled\"}";
                }
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("files", files);
                res.put("source", "assets");
                return res.toString();
            } catch (Exception e) {
                try {
                    JSONObject res = new JSONObject();
                    res.put("ok", false);
                    res.put("error", e.getMessage() != null ? e.getMessage() : "seed failed");
                    return res.toString();
                } catch (Exception e2) {
                    return "{\"ok\":false,\"error\":\"seed failed\"}";
                }
            }
        }


        private void serve(Socket c) {
            try { c.setSoTimeout(5000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                String[] req = String.valueOf(r.readLine()).split(" ");
                OutputStream o = c.getOutputStream();
                String path = req.length > 1 ? URLDecoder.decode(req[1].split("\\?")[0], "UTF-8") : "/";
                if (path.equals("/")) path = "/index.html";
                File f = new File(root(), safe(path)); boolean head = req[0].equals("HEAD");
                if (!(req[0].equals("GET") || head) || !f.isFile()) {
                    o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
                } else {
                    String n = f.getName().toLowerCase(), t = "application/octet-stream";
                    if (n.endsWith(".html") || n.endsWith(".htm")) t = "text/html; charset=utf-8";
                    else if (n.endsWith(".js") || n.endsWith(".mjs")) t = "application/javascript; charset=utf-8";
                    else if (n.endsWith(".css")) t = "text/css"; else if (n.endsWith(".json")) t = "application/json";
                    else if (n.endsWith(".txt")) t = "text/plain"; else if (n.endsWith(".png")) t = "image/png";
                    else if (n.endsWith(".jpg") || n.endsWith(".jpeg")) t = "image/jpeg"; else if (n.endsWith(".wasm")) t = "application/wasm";
                    o.write(("HTTP/1.1 200 OK\r\nContent-Type: " + t + "\r\nContent-Length: " + f.length() + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes());
                    if (!head) { FileInputStream in = new FileInputStream(f); byte[] b = new byte[16384]; int k; while ((k = in.read(b)) > 0) o.write(b, 0, k); in.close(); }
                }
                o.flush();
            } catch (Exception ignored) {} finally { try { c.close(); } catch (Exception ignored) {} }
        }
    }

    @Override protected void onDestroy() { super.onDestroy(); }

    // ── HTTPS GET for release checks / downloads.
    // getB64 is sync (legacy). Prefer getB64Async — runs off the UI thread and
    // delivers the result via window.__nativeHttpCb(callbackId, result).
    public class NativeHttp {
        private String fetchB64(String url) {
            try {
                if (!url.startsWith("https://")) return "ERR:https only";
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Nexus-Android");
                int code = c.getResponseCode();
                if (code != 200) return "ERR:HTTP " + code;
                InputStream in = c.getInputStream(); ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[16384]; int k, total = 0;
                while ((k = in.read(b)) > 0) { total += k; if (total > 64 * 1024 * 1024) return "ERR:file too large"; o.write(b, 0, k); }
                in.close();
                return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) { return "ERR:" + (e.getMessage() != null ? e.getMessage() : "network"); }
        }

        @JavascriptInterface public String getB64(String url) {
            return fetchB64(url);
        }

        @JavascriptInterface public void getB64Async(final String url, final String callbackId) {
            new Thread(new Runnable() {
                @Override public void run() {
                    final String result = fetchB64(url);
                    // Must touch WebView on the main thread
                    webView.post(new Runnable() {
                        @Override public void run() {
                            // JSON-stringify via org.json to escape safely
                            String payload = org.json.JSONObject.quote(result);
                            String idJson = org.json.JSONObject.quote(callbackId);
                            webView.evaluateJavascript(
                                "(function(){try{window.__nativeHttpCb&&window.__nativeHttpCb("+idJson+","+payload+");}catch(e){}})();",
                                null);
                        }
                    });
                }
            }, "nexus-http").start();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
