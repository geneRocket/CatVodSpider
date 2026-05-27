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
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class WebViewVIdeoUrlSpider {

    Context context;
    WebView webView;
    CountDownLatch latch;
    String jsScript;
    String webUrl;
    String domId;

    Pattern SNIFFER;

    String videoUrl;
    Map<String, String> videoHeaders = new HashMap<>();

    Handler mainHandler;


    public WebViewVIdeoUrlSpider(Context context) {
        this.context = context;
    }

    public void createInitWebView(Context context) {

        webView = new WebView(context);
        // 启用 JS
        WebSettings settings = webView.getSettings();
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


        webView.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                try {
                    if (url.matches(".*\\.(css|woff|woff2|ttf|otf|eot|svg|mp4|webm|avi|gif)$")) {
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                    }

                    if (SNIFFER != null && SNIFFER.matcher(url).find()) {
                        videoUrl = url;
                        videoHeaders.clear();
                        videoHeaders.putAll(request.getRequestHeaders());
                        putCookie(url);
                        putCookie(webUrl);
                        latch.countDown();
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
                if (domId == null) {
                    mainHandler.post(() -> webView.evaluateJavascript(jsScript, null));
                } else {
                    evaluateVideoUrlByDomId(0);
                }
            }
        });

    }


    public String getVideoUrl(String webUrl, Map<String, String> header, String jsScript, Pattern SNIFFER) throws Exception {
        this.webUrl = webUrl;
        this.jsScript = jsScript;
        this.SNIFFER = SNIFFER;
        this.domId = null;

        mainHandler = new Handler(Looper.getMainLooper());
        latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            createInitWebView(context);
            webView.loadUrl(webUrl, header);
        });
        // 加载目标网页
        latch.await(20, TimeUnit.SECONDS);

        mainHandler.post(() -> {
            webView.destroy();
        });

        return videoUrl;
    }

    public String getVideoUrlByDomId(String webUrl, Map<String, String> header, String domId) throws Exception {
        this.webUrl = webUrl;
        this.jsScript = null;
        this.SNIFFER = null;
        this.domId = domId;

        mainHandler = new Handler(Looper.getMainLooper());
        latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            createInitWebView(context);
            webView.loadUrl(webUrl, header);
        });
        latch.await(20, TimeUnit.SECONDS);

        if (videoUrl != null) {
            putCookie(videoUrl);
            putCookie(webUrl);
        }

        mainHandler.post(() -> {
            webView.destroy();
        });

        return videoUrl;
    }

    private void evaluateVideoUrlByDomId(int retry) {
        mainHandler.post(() -> webView.evaluateJavascript(buildDomVideoUrlScript(domId), result -> {
            String url = decodeJsString(result);
            if (url != null && !url.isEmpty()) {
                videoUrl = url;
                latch.countDown();
                return;
            }
            if (retry < 19) mainHandler.postDelayed(() -> evaluateVideoUrlByDomId(retry + 1), 1000);
        }));
    }

    private String buildDomVideoUrlScript(String domId) {
        String safeDomId = domId.replace("\\", "\\\\").replace("'", "\\'");
        return "(function(){"
                + "var el=document.getElementById('" + safeDomId + "');"
                + "if(!el)return '';"
                + "var url=el.currentSrc||el.src||el.href||el.getAttribute('src')||el.getAttribute('data-src')||el.getAttribute('data-original')||'';"
                + "if(!url&&el.querySelector){var source=el.querySelector('source[src],video[src],a[href]');if(source)url=source.currentSrc||source.src||source.href||source.getAttribute('src')||source.getAttribute('href')||'';}"
                + "if(!url&&el.parentElement&&el.parentElement.querySelector){var peer=el.parentElement.querySelector('video source[src],video[src],source[src]');if(peer)url=peer.currentSrc||peer.src||peer.getAttribute('src')||'';}"
                + "if(!url)return '';"
                + "var a=document.createElement('a');a.href=url;return a.href;"
                + "})();";
    }

    private String decodeJsString(String value) {
        if (value == null || value.equals("null")) return "";
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return StringEscapeUtils.unescapeEcmaScript(value.substring(1, value.length() - 1));
        }
        return value;
    }

    private void putCookie(String url) {
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie == null || cookie.isEmpty()) return;
        String old = videoHeaders.get("Cookie");
        videoHeaders.put("Cookie", old == null || old.isEmpty() ? cookie : old + "; " + cookie);
    }

    public Map<String, String> getVideoHeaders() {
        return videoHeaders;
    }
}
