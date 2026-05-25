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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    Pattern SNIFFER;

    String videoUrl;
    Map<String, String> videoHeaders = new HashMap<>();

    Handler mainHandler;

    // 用于保存多个符合条件的视频候选
    private final List<VideoCandidate> candidates = new ArrayList<>();
    private final Handler delayHandler = new Handler(Looper.getMainLooper());
    private final Runnable countDownRunnable = () -> {
        if (latch != null) {
            latch.countDown();
        }
    };

    // 候选实体类
    public static class VideoCandidate {
        private final String url;
        private final Map<String, String> headers;
        private final long contentLength;

        public VideoCandidate(String url, Map<String, String> headers, long contentLength) {
            this.url = url;
            this.headers = headers;
            this.contentLength = contentLength;
        }

        public String getUrl() { return url; }
        public Map<String, String> getHeaders() { return headers; }
        public long getContentLength() { return contentLength; }
    }

    public WebViewVIdeoUrlSpider(Context context) {
        this.context = context;
    }

    public void createInitWebView(Context context) {
        webView = new WebView(context);
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

                    Request.Builder builder = new Request.Builder().url(url);
                    for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                        builder.addHeader(header.getKey(), header.getValue());
                    }

                    Response response = OkHttp.client().newCall(builder.build()).execute();

                    if (!response.isSuccessful()) {
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                    }

                    // 拦截符合规则的视频流请求
                    if (SNIFFER.matcher(url).find()) {
                        long contentLength = 0;
                        String lenStr = response.header("Content-Length");
                        if (lenStr != null) {
                            try {
                                contentLength = Long.parseLong(lenStr);
                            } catch (NumberFormatException ignored) {}
                        }
                        addCandidate(url, request.getRequestHeaders(), contentLength);
                    }

                    ResponseBody body = response.body();
                    if (body == null) {
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                    }

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
                mainHandler.post(() -> {
                    webView.evaluateJavascript(jsScript, null);
                });
            }
        });
    }

    private void addCandidate(String url, Map<String, String> requestHeaders, long contentLength) {
        synchronized (candidates) {
            for (VideoCandidate c : candidates) {
                if (c.getUrl().equals(url)) {
                    return; // 避免重复添加
                }
            }
            Map<String, String> headers = new HashMap<>(requestHeaders);
            putCookie(url, headers);
            putCookie(webUrl, headers);
            candidates.add(new VideoCandidate(url, headers, contentLength));
        }

        // 如果检测到文件大小大于 10MB，基本可以断定这是我们需要的完整视频，立即结束等待
        if (contentLength > 10 * 1024 * 1024) {
            if (latch != null) {
                latch.countDown();
            }
        } else {
            // 如果大小未知或较小（如可能为预览片、广告等），延时 1.5 秒再关闭，允许后续其他可能的视频请求进来
            triggerDelayedCountDown();
        }
    }

    private void triggerDelayedCountDown() {
        delayHandler.removeCallbacks(countDownRunnable);
        delayHandler.postDelayed(countDownRunnable, 1500);
    }

    public String getVideoUrl(String webUrl, Map<String, String> header, String jsScript, Pattern SNIFFER) throws Exception {
        this.webUrl = webUrl;
        this.jsScript = jsScript;
        this.SNIFFER = SNIFFER;
        this.candidates.clear();

        mainHandler = new Handler(Looper.getMainLooper());
        latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            createInitWebView(context);
            webView.loadUrl(webUrl, header);
        });

        latch.await(20, TimeUnit.SECONDS);

        mainHandler.post(() -> {
            delayHandler.removeCallbacks(countDownRunnable);
            if (webView != null) {
                webView.destroy();
            }
        });

        // 从收集到的候选中挑选出最合适的一个
        VideoCandidate bestCandidate = selectBestCandidate();
        if (bestCandidate != null) {
            this.videoUrl = bestCandidate.getUrl();
            this.videoHeaders.clear();
            this.videoHeaders.putAll(bestCandidate.getHeaders());
            return this.videoUrl;
        }

        return null;
    }

    // 筛选策略：优先排除带有预览字样的链接，在此基础上选择 Content-Length 最大的链接
    private VideoCandidate selectBestCandidate() {
        synchronized (candidates) {
            if (candidates.isEmpty()) {
                return null;
            }

            VideoCandidate best = null;
            for (VideoCandidate candidate : candidates) {
                String url = candidate.getUrl().toLowerCase();
                // 排除带有 preview、short 等可能是预览片或非目标片段的链接
                if (url.contains("preview") || url.contains("short")) {
                    continue;
                }

                if (best == null || candidate.getContentLength() > best.getContentLength()) {
                    best = candidate;
                }
            }

            // 如果排除之后没有剩余选项，则直接从所有候选里选取文件最大的一个
            if (best == null) {
                for (VideoCandidate candidate : candidates) {
                    if (best == null || candidate.getContentLength() > best.getContentLength()) {
                        best = candidate;
                    }
                }
            }

            return best;
        }
    }

    private void putCookie(String url, Map<String, String> headers) {
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie == null || cookie.isEmpty()) return;
        String old = headers.get("Cookie");
        headers.put("Cookie", old == null || old.isEmpty() ? cookie : old + "; " + cookie);
    }

    public Map<String, String> getVideoHeaders() {
        return videoHeaders;
    }
}