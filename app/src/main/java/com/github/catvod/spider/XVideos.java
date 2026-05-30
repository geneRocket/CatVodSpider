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

    // 升级为完整的现代浏览器指纹头，包含 Client Hints，可绕过大多数 WAF 签名检测
    private HashMap<String, String> getHeaders(String referer) {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        headers.put("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"");
        headers.put("Sec-Ch-Ua-Mobile", "?0");
        headers.put("Sec-Ch-Ua-Platform", "\"Windows\"");
        headers.put("Sec-Fetch-Dest", "document");
        headers.put("Sec-Fetch-Mode", "navigate");
        headers.put("Sec-Fetch-Site", "none");
        headers.put("Sec-Fetch-User", "?1");
        headers.put("Upgrade-Insecure-Requests", "1");
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", referer);
        }
        return headers;
    }

    private String fetch(String url) {
        return OkHttp.string(url, getHeaders(url));
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        // 【关键防爬绕过】：发起一次性会话切换握手，获取并保存合法的 Orientation 和 Session Cookie 到 OkHttp 的全局 CookieJar
        try {
            fetch(siteUrl + "/switch-sexual-orientation/straight/straight");
        } catch (Exception ignored) {
        }

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
        int page = parsePage(pg);
        String url = getCategoryUrl(tid, pg);
        String html = fetch(url);

        if (html == null || html.isEmpty()) {
            return Result.get().page(page, page, 24, page * 24).vod(new ArrayList<>()).string();
        }

        Document doc = Jsoup.parse(html);
        int pageCount = parsePageCount(doc, page);
        return Result.get().page(page, pageCount, 24, pageCount * 24).vod(parseList(doc)).string();
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
        Document doc = Jsoup.parse(fetch(url));
        return Result.get().vod(parseList(doc)).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String webUrl = fixUrl(id);
        String html = fetch(webUrl);
        String videoUrl = getPlayerUrl(html);
        if (videoUrl.isEmpty()) return "";
        HashMap<String, String> headers = getHeaders(webUrl);
        return Result.get().url(videoUrl).header(headers).string();
    }

    private List<Vod> parseList(Document doc) {
        List<Vod> list = new ArrayList<>();
        for (Element div : doc.select("div.thumb-block")) {
            Element a = div.selectFirst("p.title a[href^='/video']");
            if (a == null) a = div.selectFirst(".title a[href^='/video']");
            if (a == null) a = div.selectFirst("div.thumb a[href^='/video']");
            if (a == null) a = div.selectFirst("a.thumb-link[href^='/video']");
            if (a == null) continue;
            String id = fixUrl(a.attr("href").replace("/THUMBNUM/", "/0/"));
            String name = a.hasAttr("title") ? a.attr("title") : a.ownText();
            if (name.isEmpty()) name = a.text();
            name = name.trim();
            if (name.isEmpty()) continue;

            Element img = div.selectFirst("img[data-src], img[src]");
            String pic = "";
            if (img != null) {
                pic = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
            }
            if (pic != null && !pic.isEmpty()) {
                pic = pic.replace("THUMBNUM", "1");
            } else {
                pic = getDataVideoThumb(div.attr("data-video"));
            }

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
        Matcher matcher = Pattern.compile("html5player\\." + method + "\\(['\"]([^'\"]+)['\"]\\)").matcher(html);
        return matcher.find() ? decodeJsString(matcher.group(1)) : "";
    }

    private String getActor(Document doc) {
        String uploader = "";
        String url = "";
        Element a = doc.selectFirst("a[href^=/profiles/], a[href^=/channels/], a[href^=/pornstars/], a[href^=/][class*=profile]");
        if (a != null) {
            uploader = a.text().trim();
            url = fixUrl(a.attr("href"));
        } else {
            uploader = match(doc.html(), "html5player\\.setUploaderName\\(['\"]([^'\"]+)['\"]\\)");
            if (!uploader.isEmpty()) {
                url = fixUrl("/profiles/" + uploader);
            }
        }
        if (uploader.isEmpty()) return "";
        return "[a=cr:" + new Gson().toJson(new Class(url, uploader)) + "/]" + uploader + "[/a]";
    }

    private String getCategoryUrl(String tid, String pg) {
        int page = parsePage(pg);
        int index = Math.max(0, page - 1);
        try {
            if (tid.startsWith("http")) {
                if (tid.contains("p=")) {
                    return tid.replaceAll("([?&]p=)\\d+", "$1" + index);
                } else {
                    return tid + (tid.contains("?") ? "&" : "?") + "p=" + index;
                }
            }

            String encodedTid = URLEncoder.encode(tid, "UTF-8");
            if ("new".equals(tid)) return page <= 1 ? siteUrl + "/" : siteUrl + "/new/" + index;
            if ("best".equals(tid)) return page <= 1 ? siteUrl + "/best" : siteUrl + "/best/" + getLastMonth() + "/" + index;
            return siteUrl + "/tags/" + encodedTid + "/" + index;
        } catch (Exception e) {
            return siteUrl + "/tags/" + tid + "/" + index;
        }
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

    private int parsePageCount(Document doc, int page) {
        int count = page;
        Elements links = doc.select("div.pagination a[href], ul.pagination a[href], .pagination a[href]");
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
            value = value.replace("\\/", "/");
            Matcher matcher = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(value);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                matcher.appendReplacement(sb, Character.toString((char) Integer.parseInt(matcher.group(1), 16)));
            }
            matcher.appendTail(sb);
            value = sb.toString();
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