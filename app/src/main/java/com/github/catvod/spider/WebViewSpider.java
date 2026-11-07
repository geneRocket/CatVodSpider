package com.github.catvod.spider;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.SslErrorHandler;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.net.OkHttp;

import org.apache.commons.lang3.StringEscapeUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class WebViewSpider {

    Context context;
    WebView webView;
    CountDownLatch latch;
    String htmlSource;
    String webUrl;
    Map<String, String> webHeaders = new HashMap<>();

    Handler mainHandler;
    private boolean nativeNetwork;
    private long deadline;
    private boolean closed;


    public WebViewSpider(Context context) {
        this.context = context;
    }

    public void createInitWebView(Context context) {

        webView = new WebView(context);
        // 启用 JS
        WebSettings settings = webView.getSettings();
        if (webHeaders.containsKey("User-Agent")) settings.setUserAgentString(webHeaders.get("User-Agent"));
        settings.setSupportZoom(true);
        settings.setUseWideViewPort(true);
        settings.setDatabaseEnabled(false);
        settings.setBlockNetworkImage(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setLoadsImagesAutomatically(false);
        // 开启额外的渲染兼容参数
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (nativeNetwork) return null;
                try {
                    if (url.equals(webUrl)) {
                        webHeaders.clear();
                        webHeaders.putAll(request.getRequestHeaders());
                        putCookie(url);
                    }

                    if (url.matches(".*\\.(css|woff|woff2|ttf|otf|eot|svg|mp4|webm|avi|gif)$")) {
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                    }

                    Request.Builder builder = new Request.Builder().url(url);

                    // 设置请求头
                    for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                        builder.addHeader(header.getKey(), header.getValue());
                    }

                    Response response = OkHttp.client().newCall(builder.build()).execute();

                    if (!response.isSuccessful()) {
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                    }

                    ResponseBody body = response.body();
                    if (body == null) {
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                    }

                    // 获取Content-Type 和 encoding
                    String contentType = response.header("Content-Type", "text/plain");
                    String mimeType = getMimeTypeFromContentType(contentType);
                    String encoding = getEncodingFromContentType(contentType);

                    InputStream inputStream = body.byteStream();

                    return new WebResourceResponse(mimeType, encoding, inputStream);
                } catch (Exception e) {
                    Log.e("webview", "shouldInterceptRequest error", e);
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                }
            }

            private String getMimeTypeFromContentType(String contentType) {
                if (contentType == null) return "text/plain";
                String[] parts = contentType.split(";");
                return parts[0].trim();
            }

            private String getEncodingFromContentType(String contentType) {
                if (contentType == null) return "utf-8";
                String[] parts = contentType.split(";");
                for (String part : parts) {
                    part = part.trim().toLowerCase();
                    if (part.startsWith("charset=")) {
                        return part.substring(8);
                    }
                }
                return "utf-8";
            }


            @Override
            @SuppressLint("WebViewClientOnReceivedSslError")
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                putCookie(url);
                putCookie(webUrl);
                execJsGetSource(false, () -> {
                    if (nativeNetwork) waitForContent();
                    else latch.countDown();
                });
            }
        });

    }

    private void execJsGetSource(boolean sync, Runnable callBack) {
        CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(() -> {
            webView.evaluateJavascript(
                    "(function() { return document.documentElement.outerHTML; })();",
                    html -> {
                        if (closed) { latch.countDown(); return; }
                        htmlSource = StringEscapeUtils.unescapeEcmaScript(html);
                        htmlSource = htmlSource == null || htmlSource.length() < 2 || htmlSource.equals("null")
                                ? "" : htmlSource.substring(1, htmlSource.length() - 1);
                        latch.countDown();
                        if (callBack != null) {
                            callBack.run();
                        }
                    }
            );
        });
        if (sync) {
            try {
                latch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {

            }
        }
    }


    public String getHtmlSource(String webUrl, Map<String, String> header) throws Exception {
        return getHtmlSource(webUrl, header, false);
    }

    private void waitForContent() {
        if (closed || latch.getCount() == 0) return;
        if (!CloudflareHtml.isChallenge(htmlSource) && !htmlSource.isEmpty()) {
            putCookie(webUrl);
            latch.countDown();
        } else if (System.currentTimeMillis() < deadline) {
            mainHandler.postDelayed(() -> execJsGetSource(false, this::waitForContent), 500);
        } else {
            latch.countDown();
        }
    }

    public String getHtmlSource(String webUrl, Map<String, String> header, boolean nativeNetwork) throws Exception {
        if (context == null) return "";
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("WebView fetch requires a worker thread");
        this.nativeNetwork = nativeNetwork;
        this.closed = false;
        deadline = System.currentTimeMillis() + 25000;

        this.webUrl = webUrl;
        this.htmlSource = "";
        this.webHeaders.clear();
        this.webHeaders.putAll(header);
        mainHandler = new Handler(Looper.getMainLooper());
        latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            createInitWebView(context);
            webView.loadUrl(webUrl, header);
        });
        // 加载目标网页
        try {
            if (!latch.await(nativeNetwork ? 25 : 5, TimeUnit.SECONDS) && !nativeNetwork) {
                execJsGetSource(true, null);
            }
            return htmlSource;
        } finally {
            mainHandler.post(() -> {
                closed = true;
                mainHandler.removeCallbacksAndMessages(null);
                if (webView != null) webView.destroy();
            });
        }
    }

    private void putCookie(String url) {
        if (url == null || url.isEmpty()) return;
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie == null || cookie.isEmpty()) return;
        webHeaders.put("Cookie", cookie);
    }

    public Map<String, String> getHeaders() {
        return webHeaders;
    }
}
