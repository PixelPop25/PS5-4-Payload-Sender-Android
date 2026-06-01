package com.pixelpop.nexusinjector;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Force Fullscreen - Eradicates the system status bar entirely for the game console aesthetic
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, 
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // Engage Immersive Sticky Navigation handling to keep standard phone system frames completely hidden
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        // 2. Initialize Core WebView components
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDomStorageEnabled(true);
        
        // Prevent generic outside browser app redirection routines
        webView.setWebViewClient(new WebViewClient());

        // 3. Inject the Pure Native Raw TCP Interface Module
        webView.addJavascriptInterface(new AndroidTCPInterface(), "AndroidNativeTCP");

        // Load your local UI build
        webView.loadUrl("file:///android_asset/index.html");
    }

    /**
     * Native Multi-Threaded Socket Streaming Engine Subsystem
     */
    public class AndroidTCPInterface {

        @JavascriptInterface
        public void performRawStream(final String targetIp, final int targetPort, final String base64PayloadData) {
            // Android prohibits raw socket creation on the Main Application UI Thread.
            // Spawning separate execution thread context to handle pure network packet streaming.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Socket tcpSocket = null;
                    OutputStream rawOutputStream = null;
                    try {
                        // Reassemble raw bytes out of the Base64 bridge string layer
                        byte[] rawPayloadBytes = Base64.decode(base64PayloadData, Base64.DEFAULT);

                        // Initialize high performance raw socket interface
                        tcpSocket = new Socket();
                        
                        // Enforce a strict 4-second network connection threshold timeout limits
                        tcpSocket.connect(new InetSocketAddress(targetIp, targetPort), 4000);
                        rawOutputStream = tcpSocket.getOutputStream();

                        // Directly dump the absolute raw binary frame into the connection buffer
                        rawOutputStream.write(rawPayloadBytes);
                        rawOutputStream.flush();

                        // Safely terminate channels
                        rawOutputStream.close();
                        tcpSocket.close();

                        sendCallbackToWebfront(true, "Payload injected successfully over raw native TCP.");

                    } catch (final Exception socketError) {
                        // Clean up hanging handles on exception
                        try {
                            if (rawOutputStream != null) rawOutputStream.close();
                            if (tcpSocket != null) tcpSocket.close();
                        } catch (Exception ignored) {}

                        sendCallbackToWebfront(false, "TCP Stream Interrupted: " + socketError.getMessage());
                    }
                }
            }).start();
        }

        // Asynchronously pushes feedback metrics straight back up to the rendering pipeline
        private void sendCallbackToWebfront(final boolean outcomeSuccess, final String outcomeMessage) {
            webView.post(new Runnable() {
                @Override
                public void run() {
                    webView.loadUrl("javascript:onNativeResponse(" + outcomeSuccess + ", '" + outcomeMessage.replace("'", "\\'") + "')");
                }
            });
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
