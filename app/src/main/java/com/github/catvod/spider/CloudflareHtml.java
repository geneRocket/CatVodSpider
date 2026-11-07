package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Per-spider browser cookies; only use WebView when the HTTP response is blocked. */
final class CloudflareHtml {
    private final Map<String, String> session = new HashMap<>();

    static boolean isChallenge(String html) {
        if (html == null) return false;
        String lower = html.toLowerCase(Locale.ROOT);
        return lower.contains("cf-mitigated") || lower.contains("_cf_chl_opt")
                || lower.contains("just a moment") || lower.contains("attention required! | cloudflare")
                || lower.contains("/cdn-cgi/challenge-platform/");
    }

    synchronized Document fetch(Context context, String url, Map<String, String> headers) {
        HashMap<String, String> request = new HashMap<>(headers);
        request.putAll(session);
        String html = OkHttp.string(url, request);
        if (context != null && (html.isEmpty() || isChallenge(html))) {
            try {
                WebViewSpider browser = new WebViewSpider(context);
                String rendered = browser.getHtmlSource(url, request, true);
                if (!rendered.isEmpty() && !isChallenge(rendered)) {
                    html = rendered;
                    String cookie = browser.getHeaders().get("Cookie");
                    if (cookie != null && !cookie.isEmpty()) session.put("Cookie", cookie);
                }
            } catch (Exception e) { SpiderDebug.log(e); }
        }
        return Jsoup.parse(html, url);
    }
}
