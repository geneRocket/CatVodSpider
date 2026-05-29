package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;

public class NinetyOnePorn extends Spider {

    private static final String siteUrl = "https://91porn.com";
    private static final String[] VIDEO_DOM_IDS = {"player_one_html5_api", "player_one"};
    private static final Pattern VIDEO_SNIFFER = Pattern.compile("^(?!.*(?:kwai\\.net|ad-i18n-dsp|preroll)).*\\.(?:mp4|m3u8)(?:[?#].*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("(?:添加时间|Added)[:：]?\\s*(\\d{4}-\\d{2}-\\d{2})");
    private Context context;

    @Override
    public void init(Context context) throws Exception {
        this.context = context;
    }

    /**
     * 随机生成国内段 IP 防止每日访问限制
     */
    private String getRandomIp() {
        Random random = new Random();
        int[] prefixes = {112, 113, 116, 117, 120, 123, 180, 218, 221, 222};
        int prefix = prefixes[random.nextInt(prefixes.length)];
        return prefix + "." +
                random.nextInt(254) + "." +
                random.nextInt(254) + "." +
                (random.nextInt(253) + 1);
    }

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Cache-Control", "max-age=0");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        headers.put("X-Forwarded-For", getRandomIp());
        // 强制中文语言，防止返回英文页面导致解析错误
        headers.put("Cookie", "session_language=cn_CN; language=cn_CN;");
        return headers;
    }

    private FetchResult fetch(String url) {
        HashMap<String, String> headers = getHeaders();
        Request request = new Request.Builder().url(url).headers(Headers.of(headers)).build();
        try (Response response = OkHttp.client().newBuilder()
                .callTimeout(15, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()) {
            String html = response.body() == null ? "" : response.body().string();
            return isChallenge(html) ? fetchByWebView(url, headers) : new FetchResult(html, headers);
        } catch (IOException e) {
            return new FetchResult("", headers);
        }
    }

    private String fetchHtml(String url) {
        return fetch(url).html;
    }

    private boolean isChallenge(String html) {
        return html != null && (html.contains("cf-mitigated") || html.contains("_cf_chl_opt") || html.contains("Just a moment"));
    }

    private FetchResult fetchByWebView(String url, HashMap<String, String> headers) {
        if (context == null) return new FetchResult("", headers);
        try {
            WebViewSpider webViewSpider = new WebViewSpider(context);
            String html = webViewSpider.getHtmlSource(url, headers);
            HashMap<String, String> webHeaders = new HashMap<>(headers);
            webHeaders.putAll(webViewSpider.getHeaders());
            return new FetchResult(html, webHeaders);
        } catch (Exception e) {
            return new FetchResult("", headers);
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("ori", "91原创"));
        classes.add(new Class("hot", "当前最热"));
        classes.add(new Class("top", "本月最热"));
        classes.add(new Class("long", "10分钟以上"));
        classes.add(new Class("longer", "20分钟以上"));
        classes.add(new Class("tf", "本月收藏"));
        classes.add(new Class("rf", "最近加精"));
        classes.add(new Class("hd", "高清"));
        classes.add(new Class("category=top&m=-1&viewtype=basic", "每月最热"));
        classes.add(new Class("md", "本月讨论"));
        classes.add(new Class("mf", "收藏最多"));
        return Result.string(classes);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = getCategoryUrl(tid, pg);
        String html = fetchHtml(url);
        List<Vod> list = parseList(html);
        int page = parsePage(pg);
        int pageCount = parsePageCount(html, page);
        return Result.get().page(page, pageCount, 24, pageCount * 24).vod(list).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0);
        String htmlSource = fetchHtml(url);
        Document doc = Jsoup.parse(htmlSource);

        String name = firstText(doc, "meta[property=og:title], h4.login_register_header, #viewvideo-title, .video-title, h4, title")
                .replace("收藏", "")
                .replace("- 91Porn", "")
                .trim();

        String pic = firstAttr(doc, "meta[property=og:image]", "content");
        if (pic.isEmpty()) pic = firstAttr(doc, "video[poster]", "poster");
        if (pic.isEmpty()) pic = firstAttr(doc, "link[rel=image_src]", "href");
        pic = fixUrl(pic);

        String date = parseDate(doc.text());

        Element actorEl = doc.selectFirst("a[href*=uprofile.php], a[href^=author.php], .author");
        String actor = actorEl != null ? actorEl.text().trim() : "";

        String content = firstText(doc, "#v_desc, #v_desc_more, .video-desc, .description");

        Vod vod = new Vod();
        vod.setVodId(url);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodYear(date);
        vod.setVodActor(actor);
        vod.setVodContent(content);
        vod.setVodPlayFrom("91Porn");
        vod.setVodPlayUrl(url);
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        String url = siteUrl + "/search_result.php?search_id=" + URLEncoder.encode(key, "UTF-8") + "&search_type=search_videos&page=" + pg;
        return Result.string(parseList(fetchHtml(url)));
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String webUrl = id;
        FetchResult result = fetch(webUrl);
        HashMap<String, String> headers = result.headers;

        // 1. 尝试直接通过 Java 正则表达式解码网页 HTML 中的 strencode2
//        String videoUrl = parseVideoUrlFromHtml(result.html);
        String videoUrl = null;

        // 2. 如果直接解析失败，则回退调用 WebView
        if (TextUtils.isEmpty(videoUrl) && context != null) {
            WebViewVIdeoUrlSpider webViewVIdeoUrlSpider = new WebViewVIdeoUrlSpider(context);
            videoUrl = webViewVIdeoUrlSpider.getVideoUrlByScript(webUrl, headers, getPlayScript(), getVideoUrlScript());
            if (TextUtils.isEmpty(videoUrl)) {
                videoUrl = webViewVIdeoUrlSpider.getLargestVideoUrl(webUrl, headers, getPlayScript(), VIDEO_SNIFFER);
            }
            if (!TextUtils.isEmpty(videoUrl)) {
                headers.putAll(webViewVIdeoUrlSpider.getVideoHeaders());
            }
        }

        if (TextUtils.isEmpty(videoUrl)) return "";
        headers.put("Referer", webUrl);
        headers.put("Origin", siteUrl);
        return Result.get().url(videoUrl).header(headers).string();
    }

    private String getPlayScript() {
        return "(function(){\n" +
                "    var limit = 10; // 最大重试 10 次\n" +
                "    var interval = setInterval(function() {\n" +
                "        var btn = document.querySelector('.vjs-big-play-button');\n" +
                "        if (btn) {\n" +
                "            btn.click();\n" +
                "        }\n" +
                "        var video = document.getElementById('player_one_html5_api') || document.getElementById('player_one') || document.querySelector('video');\n" +
                "        if (video) {\n" +
                "            video.muted = true;\n" +
                "            var p = video.play && video.play();\n" +
                "            if (p && p.catch) p.catch(function(){});\n" +
                "        }\n" +
                "        try {\n" +
                "            var player = window.videojs && window.videojs('player_one');\n" +
                "            if (player) {\n" +
                "                player.muted(true);\n" +
                "                player.play();\n" +
                "            }\n" +
                "        } catch(e) {}\n" +
                "        limit--;\n" +
                "        if (limit <= 0) {\n" +
                "            clearInterval(interval);\n" +
                "        }\n" +
                "    }, 300);\n" +
                "})();";
    }

    private String getVideoUrlScript() {
        return "(function(){\n" +
                "    var bad = /kwai\\.net|ad-i18n-dsp|preroll/i;\n" +
                "    function abs(url){\n" +
                "        if(!url) return '';\n" +
                "        var a = document.createElement('a');\n" +
                "        a.href = url;\n" +
                "        return a.href;\n" +
                "    }\n" +
                "    function valid(url){\n" +
                "        url = (url || '').replace(/&amp;/g, '&');\n" +
                "        url = abs(url);\n" +
                "        return /\\.(mp4|m3u8)(\\?|#|$)/i.test(url) && !bad.test(url) ? url : '';\n" +
                "    }\n" +
                "    function pick(list){\n" +
                "        if(!list) return '';\n" +
                "        for(var i = 0; i < list.length; i++){\n" +
                "            var item = list[i];\n" +
                "            var url = valid(typeof item === 'string' ? item : item && item.src);\n" +
                "            if(url) return url;\n" +
                "        }\n" +
                "        return '';\n" +
                "    }\n" +
                "    function fromVideo(video){\n" +
                "        if(!video) return '';\n" +
                "        var url = valid(video.currentSrc) || valid(video.src);\n" +
                "        if(url) return url;\n" +
                "        var sources = video.querySelectorAll ? video.querySelectorAll('source[src]') : video.getElementsByTagName('source');\n" +
                "        for(var i = 0; i < sources.length; i++){\n" +
                "            url = valid(sources[i].getAttribute('src') || sources[i].src);\n" +
                "            if(url) return url;\n" +
                "        }\n" +
                "        return '';\n" +
                "    }\n" +
                "    try {\n" +
                "        var player = window.videojs && window.videojs('player_one');\n" +
                "        if (player) {\n" +
                "            var current = player.currentSource && player.currentSource();\n" +
                "            var url = valid(player.currentSrc && player.currentSrc()) || valid(current && current.src) || pick(player.currentSources && player.currentSources()) || pick(player.options_ && player.options_.sources) || fromVideo(player.tech_ && player.tech_.el_);\n" +
                "            if (url) return url;\n" +
                "        }\n" +
                "    } catch(e) {}\n" +
                "    var videos = [document.getElementById('player_one_html5_api'), document.getElementById('player_one'), document.querySelector('video')];\n" +
                "    for (var n = 0; n < videos.length; n++) {\n" +
                "        var url = fromVideo(videos[n]);\n" +
                "        if (url) return url;\n" +
                "    }\n" +
                "    var sources = document.querySelectorAll('source[src], video[src]');\n" +
                "    for (var i = 0; i < sources.length; i++) {\n" +
                "        var url = valid(sources[i].getAttribute('src') || sources[i].src);\n" +
                "        if (url) return url;\n" +
                "    }\n" +
                "    return '';\n" +
                "})();";
    }

    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        HashSet<String> parsedUrls = new HashSet<>();
        Document doc = Jsoup.parse(html);
        boolean hideLargeItems = hidesClass(html, "col-lg-8");
        for (Element element : getListItems(doc)) {
            if (isSidebarItem(element)) continue;
            if (hideLargeItems && hasAncestorClass(element, "col-lg-8")) continue;
            Element a = element.selectFirst("a[href*=view_video]");
            if (a == null) continue;

            String url = fixUrl(a.attr("href"));
            if (parsedUrls.contains(url)) continue;
            parsedUrls.add(url);

            String name = "";
            Element titleEl = element.selectFirst("span.video-title, .video-title, .title");
            if (titleEl != null) {
                name = titleEl.text().trim();
            } else {
                name = a.text().trim();
            }

            // 处理图片的懒加载和防盗链
            Element img = element.selectFirst("img");
            String pic = "";
            if (img != null) {
                pic = img.attr("src");
                if (pic.contains("blank.gif") || pic.isEmpty() || pic.contains("loading")) {
                    if (img.hasAttr("data-src")) {
                        pic = img.attr("data-src");
                    } else if (img.hasAttr("data-original")) {
                        pic = img.attr("data-original");
                    } else if (img.hasAttr("dynamic-src")) {
                        pic = img.attr("dynamic-src");
                    }
                }
            }
            pic = fixUrl(pic);

            String remark = "";
            Element durationEl = element.selectFirst("span.duration, .duration");
            if (durationEl != null) {
                remark = durationEl.text().trim();
            }

            if (url.isEmpty() || name.isEmpty()) continue;
            list.add(new Vod(url, name, pic, remark));
        }
        return list;
    }

    private Elements getListItems(Document doc) {
        Element root = getListRoot(doc);
        Elements items = root.select("div.well.well-sm:has(a[href*=view_video])");
        return items.isEmpty() ? root.select("div.list-channel:has(a[href*=view_video]), div.video-box:has(a[href*=view_video])") : items;
    }

    private Element getListRoot(Document doc) {
        Element root = doc.selectFirst(".container-minheight > .row");
        return root == null ? doc : root;
    }

    private boolean isSidebarItem(Element element) {
        Element col = element.parent();
        while (col != null && !col.tagName().equals("body")) {
            if (col.hasClass("col-md-8") || col.hasClass("col-lg-8") || col.hasClass("col-ms-8")) return false;
            if (col.hasClass("col-md-4") || col.hasClass("col-lg-4") || col.hasClass("col-ms-4")) return true;
            col = col.parent();
        }
        return false;
    }

    private boolean hidesClass(String html, String className) {
        return Pattern.compile("\\." + Pattern.quote(className) + "\\s*\\{[^}]*display\\s*:\\s*none", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html).find();
    }

    private boolean hasAncestorClass(Element element, String className) {
        Element parent = element.parent();
        while (parent != null && !parent.tagName().equals("body")) {
            if (parent.hasClass(className)) return true;
            parent = parent.parent();
        }
        return false;
    }

    private String parseDate(String text) {
        Matcher matcher = DATE.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String firstText(Document doc, String selector) {
        Element element = doc.selectFirst(selector);
        if (element == null) return "";
        String text = element.hasAttr("content") ? element.attr("content") : element.text();
        return text == null ? "" : text.trim();
    }

    private String firstAttr(Document doc, String selector, String attr) {
        Element element = doc.selectFirst(selector);
        if (element == null) return "";
        String value = element.attr(attr);
        return value == null ? "" : value.trim();
    }

    private int parsePage(String pg) {
        try {
            return Math.max(1, Integer.parseInt(pg));
        } catch (Exception e) {
            return 1;
        }
    }

    private int parsePageCount(String html, int page) {
        int count = page;
        Matcher matcher = Pattern.compile("[?&]page=(\\d+)").matcher(html);
        while (matcher.find()) count = Math.max(count, parsePage(matcher.group(1)));
        return count;
    }

    private String getCategoryUrl(String tid, String pg) {
        if (tid.startsWith("http")) return tid + (tid.contains("?") ? "&" : "?") + "page=" + pg;
        if (tid.contains("=")) return siteUrl + "/v.php?" + tid + "&page=" + pg;
        return siteUrl + "/v.php?category=" + tid + "&viewtype=basic&page=" + pg;
    }

    private String fixUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("http")) return url;
        if (url.startsWith("/")) return siteUrl + url;
        return siteUrl + "/" + url;
    }

    private static class FetchResult {
        private final String html;
        private final HashMap<String, String> headers;

        private FetchResult(String html, HashMap<String, String> headers) {
            this.html = html == null ? "" : html;
            this.headers = new HashMap<>(headers);
        }
    }
}
