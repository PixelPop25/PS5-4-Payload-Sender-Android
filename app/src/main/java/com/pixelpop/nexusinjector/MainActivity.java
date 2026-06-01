package com.pixelpop.nexusinjector;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
import java.io.OutputStream;
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

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDomStorageEnabled(true);
        
        webView.setWebViewClient(new WebViewClient());

        // Enable File Chooser for the HTML <input type="file">
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILECHOOSER_RESULTCODE);
                } catch (ActivityNotFoundException e) {
                    mFilePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new AndroidTCPInterface(), "AndroidNativeTCP");
        webView.loadUrl("file:///android_asset/index.html");
    }

    // Capture the file selected by the user and send it back to the WebView
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (mFilePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && intent != null) {
                String dataString = intent.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
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

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
