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
import java.net.URLDecoder;
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
    private static final String VIDEO_DOM_ID = "player_one_html5_api";
    private static final Pattern DATE = Pattern.compile("(?:添加时间|Added)[:：]?\\s*(\\d{4}-\\d{2}-\\d{2})");
    private Context context;

    @Override
    public void init(Context context) throws Exception {
        this.context = context;
    }

    /**
     * 随机生成国内段 IP 防止每日的限制
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

    private String fetch(String url) {
        Request request = new Request.Builder().url(url).headers(Headers.of(getHeaders())).build();
        try (Response response = OkHttp.client().newBuilder()
                .callTimeout(15, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()) {
            String html = response.body() == null ? "" : response.body().string();
            return isChallenge(html) ? fetchByWebView(url) : html;
        } catch (IOException e) {
            return "";
        }
    }

    private boolean isChallenge(String html) {
        return html != null && (html.contains("cf-mitigated") || html.contains("_cf_chl_opt") || html.contains("Just a moment"));
    }

    private String fetchByWebView(String url) {
        if (context == null) return "";
        try {
            return new WebViewSpider(context).getHtmlSource(url, getHeaders());
        } catch (Exception e) {
            return "";
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
        String html = fetch(url);
        List<Vod> list = parseList(html);
        int page = parsePage(pg);
        int pageCount = parsePageCount(html, page);
        return Result.get().page(page, pageCount, 24, pageCount * 24).vod(list).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0);
        String htmlSource = fetch(url);
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
        return Result.string(parseList(fetch(url)));
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String webUrl = id;
        HashMap<String, String> headers = getHeaders();
        String videoUrl = "";
        WebViewVIdeoUrlSpider webViewVIdeoUrlSpider = null;
        if (context != null) {
            webViewVIdeoUrlSpider = new WebViewVIdeoUrlSpider(context);
            videoUrl = webViewVIdeoUrlSpider.getVideoUrlByScript(webUrl, headers, getPlayScript(), getVideoUrlScript());
        }
        if (TextUtils.isEmpty(videoUrl)) videoUrl = parseVideoUrl(fetch(webUrl));
        if (TextUtils.isEmpty(videoUrl)) return "";
        if (webViewVIdeoUrlSpider != null) headers.putAll(webViewVIdeoUrlSpider.getVideoHeaders());
        headers.put("Referer", webUrl);
        headers.put("Origin", siteUrl);
        return Result.get().url(videoUrl).header(headers).string();
    }

    private String getPlayScript() {
        return "(function(){var btn=document.querySelector('.vjs-big-play-button');if(btn)btn.click();})();";
    }

    private String getVideoUrlScript() {
        return "(function(){"
                + "var el=document.getElementById('" + VIDEO_DOM_ID + "');"
                + "var roots=[];if(el)roots.push(el);roots.push(document);"
                + "function abs(url){if(!url)return '';var a=document.createElement('a');a.href=url;return a.href;}"
                + "function valid(url){url=abs(url);return /\\.(mp4|m3u8)(\\?|#|$)/i.test(url)&&!/kwai\\.net|ad-i18n-dsp|preroll/i.test(url)?url:'';}"
                + "for(var r=0;r<roots.length;r++){var root=roots[r];"
                + "if(root.querySelectorAll){"
                + "var sources=root.querySelectorAll('source[src]');for(var i=0;i<sources.length;i++){var url=valid(sources[i].getAttribute('src'));if(url)return url;}"
                + "var videos=root.querySelectorAll('video');for(var j=0;j<videos.length;j++){var url=valid(videos[j].currentSrc)||valid(videos[j].src)||valid(videos[j].getAttribute('src'))||valid(videos[j].getAttribute('data-src'));if(url)return url;}"
                + "}"
                + "var direct=valid(root.currentSrc)||valid(root.src)||valid(root.getAttribute&&root.getAttribute('src'))||valid(root.getAttribute&&root.getAttribute('data-src'))||valid(root.getAttribute&&root.getAttribute('data-original'));"
                + "if(direct)return direct;"
                + "}"
                + "return '';"
                + "})();";
    }

    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        HashSet<String> parsedUrls = new HashSet<>();
        Document doc = Jsoup.parse(html);
        for (Element element : getListItems(doc)) {
            if (isSidebarItem(element)) continue;
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

    private String parseDate(String text) {
        Matcher matcher = DATE.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String parseVideoUrl(String html) {
        Document doc = Jsoup.parse(html);
        String url = firstAttr(doc, "video source[src], video[src], source[src]", "src");
        if (url.isEmpty()) url = firstAttr(doc, "video[data-src], source[data-src]", "data-src");
        if (url.isEmpty()) url = parseEncodedSource(html);
        if (url.isEmpty()) {
            Matcher matcher = Pattern.compile("https?://[^\"'<>\\s]+?\\.(?:mp4|m3u8)(?:\\?[^\"'<>\\s]*)?", Pattern.CASE_INSENSITIVE).matcher(html);
            while (matcher.find()) {
                url = matcher.group();
                if (!isAdUrl(url)) break;
                url = "";
            }
        }
        return fixUrl(url);
    }

    private String parseEncodedSource(String html) {
        Matcher matcher = Pattern.compile("strencode2\\([\"']([^\"']+)[\"']\\)").matcher(html);
        while (matcher.find()) {
            String decoded = "";
            try {
                decoded = URLDecoder.decode(matcher.group(1), "UTF-8");
            } catch (Exception ignored) {
            }
            Document doc = Jsoup.parse(decoded);
            String url = firstAttr(doc, "source[src], video[src]", "src");
            if (!url.isEmpty() && !isAdUrl(url)) return url;
        }
        return "";
    }

    private boolean isAdUrl(String url) {
        return url.contains("kwai.net") || url.contains("ad-i18n-dsp") || url.contains("preroll");
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

}
