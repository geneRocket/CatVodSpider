package com.github.catvod.spider;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.net.OkHttp;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class WebViewVIdeoUrlSpider {

    private static final String TAG = "WebViewVideoUrlSpider";
    private static final long MIN_VIDEO_DURATION_SECONDS = 60; // 过滤掉小于60秒的视频（通常为广告或短预览）

    private final Context context;
    private WebView webView;
    private CountDownLatch latch;
    private String jsScript;
    private String webUrl;

    private Pattern snifferPattern;

    private String videoUrl;
    private final Map<String, String> videoHeaders = new HashMap<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler delayHandler = new Handler(Looper.getMainLooper());

    private final List<VideoCandidate> candidates = new ArrayList<>();

    private final Runnable countDownRunnable = () -> {
        if (latch != null) {
            latch.countDown();
        }
    };

    public static class VideoCandidate {
        private final String url;
        private final Map<String, String> headers;
        private final long contentLength;
        private long duration = -1; // 视频时长，单位：秒。-1表示未检测或检测失败

        public VideoCandidate(String url, Map<String, String> headers, long contentLength) {
            this.url = url;
            this.headers = headers;
            this.contentLength = contentLength;
        }

        public String getUrl() { return url; }
        public Map<String, String> getHeaders() { return headers; }
        public long getContentLength() { return contentLength; }
        public long getDuration() { return duration; }
        public void setDuration(long duration) { this.duration = duration; }
    }

    public WebViewVIdeoUrlSpider(Context context) {
        this.context = context.getApplicationContext();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createInitWebView(Context context) {
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
                Response response = null;
                try {
                    if (url.matches(".*\\.(css|woff|woff2|ttf|otf|eot|svg|mp4|webm|avi|gif)$")) {
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                    }

                    Request.Builder builder = new Request.Builder().url(url);
                    for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                        builder.addHeader(header.getKey(), header.getValue());
                    }

                    response = OkHttp.client().newCall(builder.build()).execute();

                    if (!response.isSuccessful()) {
                        response.close();
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                    }

                    if (snifferPattern != null && snifferPattern.matcher(url).find()) {
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
                        response.close();
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                    }

                    String contentType = response.header("Content-Type", "text/plain");
                    String mimeType = getMimeTypeFromContentType(contentType);
                    String encoding = getEncodingFromContentType(contentType);

                    InputStream inputStream = body.byteStream();
                    return new WebResourceResponse(mimeType, encoding, inputStream);
                } catch (Exception e) {
                    Log.e(TAG, "shouldInterceptRequest error for url: " + url, e);
                    if (response != null) {
                        try {
                            response.close();
                        } catch (Exception ignored) {}
                    }
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                }
            }

            private String getMimeTypeFromContentType(String contentType) {
                if (contentType == null) return "text/plain";
                int semiColonPos = contentType.indexOf(';');
                return semiColonPos != -1 ? contentType.substring(0, semiColonPos).trim() : contentType.trim();
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
                    if (webView != null && jsScript != null) {
                        webView.evaluateJavascript(jsScript, null);
                    }
                });
            }
        });
    }

    private void addCandidate(String url, Map<String, String> requestHeaders, long contentLength) {
        synchronized (candidates) {
            for (VideoCandidate c : candidates) {
                if (c.getUrl().equals(url)) {
                    return;
                }
            }
            Map<String, String> headers = new HashMap<>(requestHeaders);
            putCookie(url, headers);
            putCookie(webUrl, headers);
            candidates.add(new VideoCandidate(url, headers, contentLength));
        }

        triggerDelayedCountDown();
    }

    private void triggerDelayedCountDown() {
        delayHandler.removeCallbacks(countDownRunnable);
        delayHandler.postDelayed(countDownRunnable, 4000);
    }

    public String getVideoUrl(String webUrl, Map<String, String> header, String jsScript, Pattern snifferPattern) throws Exception {
        this.webUrl = webUrl;
        this.jsScript = jsScript;
        this.snifferPattern = snifferPattern;

        synchronized (candidates) {
            this.candidates.clear();
        }

        latch = new CountDownLatch(1);

        mainHandler.post(() -> {
            try {
                createInitWebView(context);
                if (webView != null) {
                    webView.loadUrl(webUrl, header);
                }
            } catch (Exception e) {
                Log.e(TAG, "WebView initialization or loading failed", e);
                if (latch != null) {
                    latch.countDown();
                }
            }
        });

        latch.await(20, TimeUnit.SECONDS);

        mainHandler.post(() -> {
            delayHandler.removeCallbacks(countDownRunnable);
            if (webView != null) {
                try {
                    webView.stopLoading();
                    webView.clearHistory();
                    webView.removeAllViews();
                    webView.destroy();
                } catch (Exception e) {
                    Log.e(TAG, "Error destroying WebView", e);
                } finally {
                    webView = null;
                }
            }
        });

        // 在选择候选前，检测这些候选链接的实际视频时长
        resolveCandidatesDuration();

        VideoCandidate bestCandidate = selectBestCandidate();
        if (bestCandidate != null) {
            this.videoUrl = bestCandidate.getUrl();
            synchronized (this.videoHeaders) {
                this.videoHeaders.clear();
                this.videoHeaders.putAll(bestCandidate.getHeaders());
            }
            return this.videoUrl;
        }

        return null;
    }

    /**
     * 解析候选视频列表的时长（在当前工作线程中同步依次解析）
     */
    private void resolveCandidatesDuration() {
        List<VideoCandidate> localCandidates;
        synchronized (candidates) {
            if (candidates.isEmpty()) return;
            localCandidates = new ArrayList<>(candidates);
        }

        for (VideoCandidate candidate : localCandidates) {
            String url = candidate.getUrl();
            long duration = -1;
            if (url.contains(".m3u8") || url.contains("m3u8")) {
                duration = getM3u8Duration(url, candidate.getHeaders());
            } else {
                duration = getMp4Duration(url, candidate.getHeaders());
            }
            candidate.setDuration(duration);
            Log.d(TAG, "Resolved duration for " + url + " : " + duration + "s");
        }
    }

    /**
     * 获取 M3U8 视频的时长
     */
    private long getM3u8Duration(String url, Map<String, String> headers) {
        try {
            Request.Builder builder = new Request.Builder().url(url);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    builder.addHeader(entry.getKey(), entry.getValue());
                }
            }
            try (Response response = OkHttp.client().newCall(builder.build()).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String content = response.body().string();
                    if (content.contains("#EXT-X-STREAM-INF")) {
                        // 如果是多码率嵌套的 Master M3U8，解析并获取第一个子轨道的实际 M3U8 地址
                        String subUrl = parseFirstM3u8Url(url, content);
                        if (subUrl != null) {
                            return getM3u8Duration(subUrl, headers);
                        }
                    }
                    return sumM3u8Duration(content);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse M3U8 duration for: " + url, e);
        }
        return -1;
    }

    private long sumM3u8Duration(String content) {
        double totalDuration = 0;
        Pattern pattern = Pattern.compile("#EXTINF:(\\d+\\.?\\d*)");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            try {
                totalDuration += Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return totalDuration > 0 ? (long) totalDuration : -1;
    }

    private String parseFirstM3u8Url(String baseUrl, String content) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.contains(".m3u8") || line.contains("m3u8")) {
                if (line.startsWith("http")) {
                    return line;
                } else {
                    try {
                        URI base = new URI(baseUrl);
                        return base.resolve(line).toString();
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    /**
     * 使用 MediaMetadataRetriever 获取 MP4 等单文件视频的时长
     */
    private long getMp4Duration(String url, Map<String, String> headers) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(url, headers != null ? headers : new HashMap<>());
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                return durationMs / 1000; // 转换为秒
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse MP4 duration for: " + url, e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
        return -1;
    }

    /**
     * 筛选策略：
     * 1. 优先排除含有 "preview"、"short" 等已知预览标记的链接。
     * 2. 在剩余候选中，如果检测到了视频实际时长，则优先过滤掉时长小于指定阈值（MIN_VIDEO_DURATION_SECONDS）的广告/试看片段。
     * 3. 在满足条件的时长视频中，选择时长最长的视频。
     * 4. 如果时长检测都失败（返回 -1），则降级为选择 Content-Length 最大的链接。
     */
    private VideoCandidate selectBestCandidate() {
        synchronized (candidates) {
            if (candidates.isEmpty()) {
                return null;
            }

            List<VideoCandidate> filtered = new ArrayList<>();
            for (VideoCandidate candidate : candidates) {
                String url = candidate.getUrl().toLowerCase();
                if (url.contains("preview") || url.contains("short")) {
                    continue; // 排除含有预览关键词的链接
                }
                filtered.add(candidate);
            }

            // 如果排除预览关键词后没有剩余，则不使用过滤，回退到全部候选中选择
            if (filtered.isEmpty()) {
                filtered.addAll(candidates);
            }

            // 1. 尝试基于视频实际时长进行筛选
            VideoCandidate bestByDuration = null;
            for (VideoCandidate candidate : filtered) {
                long duration = candidate.getDuration();
                if (duration >= MIN_VIDEO_DURATION_SECONDS) {
                    if (bestByDuration == null || duration > bestByDuration.getDuration()) {
                        bestByDuration = candidate;
                    }
                }
            }

            if (bestByDuration != null) {
                return bestByDuration;
            }

            // 2. 如果没有候选视频大于阈值，但存在检测到时长的视频，则直接选时长最长的一个（即使它比较短）
            for (VideoCandidate candidate : filtered) {
                long duration = candidate.getDuration();
                if (duration > 0) {
                    if (bestByDuration == null || duration > bestByDuration.getDuration()) {
                        bestByDuration = candidate;
                    }
                }
            }

            if (bestByDuration != null) {
                return bestByDuration;
            }

            // 3. 兜底策略：如果所有视频的时长获取均失败，则退回到按 Content-Length 最大的进行筛选
            VideoCandidate bestByLength = null;
            for (VideoCandidate candidate : filtered) {
                if (bestByLength == null || candidate.getContentLength() > bestByLength.getContentLength()) {
                    bestByLength = candidate;
                }
            }

            return bestByLength;
        }
    }

    private void putCookie(String url, Map<String, String> headers) {
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie == null || cookie.isEmpty()) return;
            String old = headers.get("Cookie");
            headers.put("Cookie", old == null || old.isEmpty() ? cookie : old + "; " + cookie);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get cookie for url: " + url, e);
        }
    }

    public Map<String, String> getVideoHeaders() {
        synchronized (this.videoHeaders) {
            return new HashMap<>(videoHeaders);
        }
    }
}