package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XVideos extends Spider {

    private static final String siteUrl = "https://www.xvideos.com";
    private static final Pattern PAGE_PATH = Pattern.compile("/(\\d+)(?:$|[?#])");
    private static final Pattern PAGE_QUERY = Pattern.compile("[?&]p=(\\d+)(?:$|&)");

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Util.CHROME);
        headers.put("Accept-Language", "en-US,en;q=0.9");
        return headers;
    }

    private String fetch(String url) {
        return OkHttp.string(url, getHeaders());
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("new", "Latest"));
        classes.add(new Class("best", "Best"));
        classes.add(new Class("hd", "HD"));
        classes.add(new Class("milf", "MILF"));
        classes.add(new Class("amateur", "Amateur"));
        classes.add(new Class("teen", "Teen"));
        classes.add(new Class("asian", "Asian"));
        classes.add(new Class("latina", "Latina"));
        classes.add(new Class("blonde", "Blonde"));
        classes.add(new Class("long", "Long"));
        return Result.string(classes);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = getCategoryUrl(tid, pg);
        String html = fetch(url);
        int page = parsePage(pg);
        int pageCount = parsePageCount(html, page);
        return Result.get().page(page, pageCount, 24, pageCount * 24).vod(parseList(html)).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String webUrl = fixUrl(ids.get(0));
        Document doc = Jsoup.parse(fetch(webUrl));
        String name = firstAttr(doc, "meta[property=og:title]", "content");
        String pic = firstAttr(doc, "meta[property=og:image]", "content");
        String duration = formatDuration(firstAttr(doc, "meta[property=og:duration]", "content"));
        String content = firstAttr(doc, "meta[name=description]", "content");
        String actor = getActor(doc);

        Vod vod = new Vod();
        vod.setVodId(webUrl);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodRemarks(duration);
        vod.setVodActor(actor);
        vod.setVodContent(content);
        vod.setVodPlayFrom("XVideos");
        vod.setVodPlayUrl("播放$" + webUrl);
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = parsePage(pg);
        String url = siteUrl + "/?k=" + URLEncoder.encode(key, "UTF-8") + "&p=" + Math.max(0, page - 1);
        return Result.get().vod(parseList(fetch(url))).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String webUrl = fixUrl(id);
        String html = fetch(webUrl);
        String videoUrl = getPlayerUrl(html);
        if (videoUrl.isEmpty()) return "";
        HashMap<String, String> headers = getHeaders();
        headers.put("Referer", webUrl);
        return Result.get().url(videoUrl).header(headers).string();
    }

    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        for (Element div : doc.select("div.thumb-block")) {
            Element a = div.selectFirst("p.title a[href^='/video']");
            if (a == null) a = div.selectFirst(".title a[href^='/video']");
            if (a == null) a = div.selectFirst("div.thumb a[href^='/video']");
            if (a == null) a = div.selectFirst("a.thumb-link[href^='/video']");
            if (a == null) continue;
            String id = fixUrl(a.attr("href").replace("/THUMBNUM/", "/0/"));
            String name = a.hasAttr("title") ? a.attr("title") : a.ownText();
            if (name.isEmpty()) name = a.text();
            name = Jsoup.parse(name).text().trim();
            if (name.isEmpty()) continue;

            Element img = div.selectFirst("img[data-src], img[src]");
            String pic = img == null ? "" : (img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src"));
            pic = pic.replace("THUMBNUM", "1");
            if (pic.isEmpty()) pic = getDataVideoThumb(div.attr("data-video"));

            String remark = div.selectFirst("span.duration") == null ? "" : div.selectFirst("span.duration").text();
            if (remark.isEmpty() && div.selectFirst(".duration") != null) remark = div.selectFirst(".duration").text();
            String views = div.select("p.metadata span").text();
            if (views.isEmpty()) views = div.select(".video-metadata").text();
            if (!views.isEmpty() && views.contains("Views")) remark = remark.isEmpty() ? views : remark + " " + views.replaceAll("\\s+", " ");
            list.add(new Vod(id, name, pic, remark));
        }
        return list;
    }

    private String getDataVideoThumb(String data) {
        if (data == null || data.isEmpty()) return "";
        try {
            JsonObject object = JsonParser.parseString(data).getAsJsonObject();
            if (object.has("sfwThumbUrl")) return fixUrl(object.get("sfwThumbUrl").getAsString());
            if (object.has("mozaiqueListing")) return fixUrl(object.get("mozaiqueListing").getAsString());
        } catch (Exception ignored) {
        }
        return "";
    }

    private String getPlayerUrl(String html) {
        String hls = getPlayerVar(html, "setVideoHLS");
        if (!hls.isEmpty()) return hls;
        String high = getPlayerVar(html, "setVideoUrlHigh");
        if (!high.isEmpty()) return high;
        return getPlayerVar(html, "setVideoUrlLow");
    }

    private String getPlayerVar(String html, String method) {
        Matcher matcher = Pattern.compile("html5player\\." + method + "\\('([^']+)'\\)").matcher(html);
        return matcher.find() ? decodeJsString(matcher.group(1)) : "";
    }

    private String getActor(Document doc) {
        String uploader = match(doc.html(), "html5player\\.setUploaderName\\('([^']+)'\\)");
        if (uploader.isEmpty()) {
            Element a = doc.selectFirst("a[href^=/profiles/], a[href^=/channels/], a[href^=/pornstars/], a[href^=/][class*=profile]");
            if (a != null) uploader = a.text().trim();
        }
        if (uploader.isEmpty()) return "";
        String url = fixUrl("/" + uploader);
        return "[a=cr:" + new Gson().toJson(new Class(url, uploader)) + "/]" + uploader + "[/a]";
    }

    private String getCategoryUrl(String tid, String pg) {
        int page = parsePage(pg);
        int index = Math.max(0, page - 1);
        if (tid.startsWith("http")) return tid + (tid.contains("?") ? "&" : "?") + "p=" + index;
        if ("new".equals(tid)) return page <= 1 ? siteUrl + "/" : siteUrl + "/new/" + index;
        if ("best".equals(tid)) return page <= 1 ? siteUrl + "/best" : siteUrl + "/best/" + getLastMonth() + "/" + index;
        return siteUrl + "/tags/" + tid + "/" + index;
    }

    private String getLastMonth() {
        Calendar calendar = Calendar.getInstance(Locale.US);
        calendar.add(Calendar.MONTH, -1);
        return String.format(Locale.US, "%04d-%02d", calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1);
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
        Elements links = Jsoup.parse(html).select("div.pagination a[href], ul.pagination a[href], .pagination a[href]");
        for (Element link : links) {
            String href = link.attr("href");
            Matcher matcher = PAGE_QUERY.matcher(href);
            if (matcher.find()) {
                count = Math.max(count, parsePage(matcher.group(1)) + 1);
                continue;
            }
            matcher = PAGE_PATH.matcher(href);
            if (matcher.find()) count = Math.max(count, parsePage(matcher.group(1)) + 1);
        }
        return count;
    }

    private String firstAttr(Document doc, String selector, String attr) {
        Element element = doc.selectFirst(selector);
        return element == null ? "" : element.attr(attr).trim();
    }

    private String match(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? decodeJsString(matcher.group(1)) : "";
    }

    private String decodeJsString(String value) {
        try {
            value = value.replace("\\/", "/").replace("\\u0026", "&");
            return value.contains("%") ? URLDecoder.decode(value, "UTF-8") : value;
        } catch (Exception e) {
            return value;
        }
    }

    private String formatDuration(String seconds) {
        try {
            int value = Integer.parseInt(seconds);
            int min = value / 60;
            int sec = value % 60;
            return sec == 0 ? min + " min" : min + ":" + (sec < 10 ? "0" : "") + sec;
        } catch (Exception e) {
            return "";
        }
    }

    private String fixUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("http")) return url;
        if (url.startsWith("/")) return siteUrl + url;
        return siteUrl + "/" + url;
    }
}
