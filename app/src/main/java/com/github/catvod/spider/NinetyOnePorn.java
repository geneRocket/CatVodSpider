package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NinetyOnePorn extends Spider {

    private static final String siteUrl = "https://91porn.com";
    private static final Pattern SOURCE = Pattern.compile("<source\\s+src=['\"]([^'\"]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED = Pattern.compile("strencode2\\(\"([^\"]+)\"");

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Encoding", "");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Cache-Control", "max-age=0");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/68.0.3440.106 Safari/537.36");
        headers.put("X-Forwarded-For", "8.8.8.8");
        return headers;
    }

    private String fetch(String url) {
        return OkHttp.string(url, getHeaders());
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
        classes.add(new Class("md", "本月讨论"));
        classes.add(new Class("mf", "收藏最多"));
        return Result.string(classes);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = siteUrl + "/v.php?category=" + tid + "&viewtype=basic&page=" + pg;
        return Result.string(parseList(fetch(url)));
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0);
        Document doc = Jsoup.parse(fetch(url));
        Element title = doc.selectFirst("h4.login_register_header");
        String name = title == null ? "" : title.ownText().trim();
        String pic = doc.select("video").attr("poster");
        String date = doc.select("span.info:contains(添加时间:) + span.title-yakov").text();
        String actor = doc.select("span.info:contains(作者:) ~ span.title-yakov a span.title").first() == null ? "" : doc.select("span.info:contains(作者:) ~ span.title-yakov a span.title").first().ownText().trim();
        String content = doc.select("#v_desc").text();
        String playUrl = parseSource(doc.html());

        Vod vod = new Vod();
        vod.setVodId(url);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodYear(date);
        vod.setVodActor(actor);
        vod.setVodContent(content);
        vod.setVodPlayFrom("91Porn");
        vod.setVodPlayUrl("播放$" + playUrl);
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
        HashMap<String, String> headers = getHeaders();
        headers.put("Referer", siteUrl + "/");
        return Result.get().url(id).header(headers).string();
    }

    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        for (Element element : doc.select("div.well.well-sm")) {
            Element a = element.selectFirst("a[href*=view_video]");
            if (a == null) continue;
            String url = fixUrl(a.attr("href"));
            String name = element.select("span.video-title").text();
            String pic = element.select("img.img-responsive").attr("src");
            String remark = element.select("span.duration").text();
            if (url.isEmpty() || name.isEmpty()) continue;
            list.add(new Vod(url, name, pic, remark));
        }
        return list;
    }

    private String fixUrl(String url) {
        if (url.startsWith("http")) return url;
        if (url.startsWith("/")) return siteUrl + url;
        return siteUrl + "/" + url;
    }

    private String parseSource(String html) throws Exception {
        Matcher matcher = ENCODED.matcher(html);
        if (matcher.find()) {
            String decoded = URLDecoder.decode(matcher.group(1), "UTF-8");
            matcher = SOURCE.matcher(decoded);
            if (matcher.find()) return matcher.group(1);
        }
        matcher = SOURCE.matcher(html.replaceAll("(?s)<!--.*?-->", ""));
        if (matcher.find()) return matcher.group(1);
        return "";
    }
}
