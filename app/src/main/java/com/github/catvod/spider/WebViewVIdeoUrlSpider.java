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
    String resultScript;
    String webUrl;

    Pattern SNIFFER;
    boolean useLargestCandidate;

    String videoUrl;
    Map<String, String> videoHeaders = new HashMap<>();
    List<VideoCandidate> videoCandidates = new ArrayList<>();

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
        // 开启额外的渲染兼容参数
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                try {
                    if (SNIFFER != null && SNIFFER.matcher(url).find()) {
                        Map<String, String> headers = new HashMap<>(request.getRequestHeaders());
                        putCookie(headers, url);
                        putCookie(headers, webUrl);
                        if (useLargestCandidate) {
                            videoCandidates.add(new VideoCandidate(url, headers, getContentLength(url, headers)));
                        } else {
                            videoUrl = url;
                            videoHeaders.clear();
                            videoHeaders.putAll(headers);
                            latch.countDown();
                        }
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
                if (resultScript == null) {
                    mainHandler.post(() -> webView.evaluateJavascript(jsScript, null));
                    if (useLargestCandidate) mainHandler.postDelayed(() -> latch.countDown(), 8000);
                } else {
                    if (jsScript != null && !jsScript.isEmpty()) {
                        mainHandler.post(() -> webView.evaluateJavascript(jsScript, value -> evaluateVideoUrlByScript(0)));
                    } else {
                        evaluateVideoUrlByScript(0);
                    }
                }
            }
        });

    }


    public String getVideoUrl(String webUrl, Map<String, String> header, String jsScript, Pattern SNIFFER) throws Exception {
        return getVideoUrl(webUrl, header, jsScript, SNIFFER, false);
    }

    public String getLargestVideoUrl(String webUrl, Map<String, String> header, String jsScript, Pattern SNIFFER) throws Exception {
        return getVideoUrl(webUrl, header, jsScript, SNIFFER, true);
    }

    private String getVideoUrl(String webUrl, Map<String, String> header, String jsScript, Pattern SNIFFER, boolean useLargestCandidate) throws Exception {
        this.webUrl = webUrl;
        this.jsScript = jsScript;
        this.resultScript = null;
        this.SNIFFER = SNIFFER;
        this.useLargestCandidate = useLargestCandidate;
        this.videoUrl = null;
        this.videoHeaders.clear();
        this.videoCandidates.clear();

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

        if (useLargestCandidate) useLargestCandidate();
        return videoUrl;
    }

    public String getVideoUrlByScript(String webUrl, Map<String, String> header, String jsScript, String resultScript) throws Exception {
        this.webUrl = webUrl;
        this.jsScript = jsScript;
        this.resultScript = resultScript;
        this.SNIFFER = null;
        this.useLargestCandidate = false;
        this.videoUrl = null;
        this.videoHeaders.clear();
        this.videoCandidates.clear();

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

    private void evaluateVideoUrlByScript(int retry) {
        mainHandler.post(() -> webView.evaluateJavascript(resultScript, result -> {
            String url = decodeJsString(result);
            if (url != null && !url.isEmpty()) {
                videoUrl = url;
                latch.countDown();
                return;
            }
            if (retry < 19) {
                mainHandler.postDelayed(() -> evaluateVideoUrlByScript(retry + 1), 1000);
            } else {
                latch.countDown();
            }
        }));
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

    private void putCookie(Map<String, String> headers, String url) {
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie == null || cookie.isEmpty()) return;
        String old = headers.get("Cookie");
        headers.put("Cookie", old == null || old.isEmpty() ? cookie : old + "; " + cookie);
    }

    private long getContentLength(String url, Map<String, String> headers) {
        try (Response response = OkHttp.client().newCall(buildHeadRequest(url, headers)).execute()) {
            long length = response.body() == null ? -1 : response.body().contentLength();
            String contentLength = response.header("Content-Length");
            if (contentLength != null && !contentLength.isEmpty()) length = Long.parseLong(contentLength);
            if (length > 0) return length;
        } catch (Exception e) {
        }
        try (Response response = OkHttp.client().newCall(buildRangeRequest(url, headers)).execute()) {
            long length = parseContentRange(response.header("Content-Range"));
            if (length > 0) return length;
            String contentLength = response.header("Content-Length");
            return contentLength == null || contentLength.isEmpty() ? -1 : Long.parseLong(contentLength);
        } catch (Exception e) {
            return -1;
        }
    }

    private Request buildHeadRequest(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url).head();
        for (Map.Entry<String, String> header : headers.entrySet()) builder.addHeader(header.getKey(), header.getValue());
        return builder.build();
    }

    private Request buildRangeRequest(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url).get().header("Range", "bytes=0-0");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!header.getKey().equalsIgnoreCase("Range")) builder.addHeader(header.getKey(), header.getValue());
        }
        return builder.build();
    }

    private long parseContentRange(String contentRange) {
        if (contentRange == null) return -1;
        int slash = contentRange.lastIndexOf('/');
        if (slash == -1 || slash == contentRange.length() - 1) return -1;
        try {
            return Long.parseLong(contentRange.substring(slash + 1));
        } catch (Exception e) {
            return -1;
        }
    }

    private void useLargestCandidate() {
        VideoCandidate best = null;
        for (VideoCandidate candidate : videoCandidates) {
            if (best == null || candidate.length > best.length || (candidate.length == best.length && candidate.length < 0)) best = candidate;
        }
        if (best == null) return;
        videoUrl = best.url;
        videoHeaders.clear();
        videoHeaders.putAll(best.headers);
    }

    public Map<String, String> getVideoHeaders() {
        return videoHeaders;
    }

    private static class VideoCandidate {
        private final String url;
        private final Map<String, String> headers;
        private final long length;

        private VideoCandidate(String url, Map<String, String> headers, long length) {
            this.url = url;
            this.headers = new HashMap<>(headers);
            this.length = length;
        }
    }
}
