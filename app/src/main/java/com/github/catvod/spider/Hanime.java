package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.net.URLEncoder;

public class Hanime extends Spider {

    private static final String siteUrl = "https://hanime1.me";
    private static final String UNAVAILABLE = "Hanime1 暂时无法访问或被 Cloudflare 拦截";
    private Context context;
    private final CloudflareHtml browser = new CloudflareHtml();

    @Override
    public void init(Context context) { this.context = context; }

    private Document fetch(String url) {
        return browser.fetch(context, url, getHeaders());
    }

    private boolean isUnavailable(Document doc) {
        return doc.body().childrenSize() == 0 || CloudflareHtml.isChallenge(doc.html());
    }

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Util.CHROME);
        return headers;
    }

    private Filter getFilter(String name, String key, List<String> texts) {
        List<Filter.Value> values = new ArrayList<>();
        if (!key.equals("by")) values.add(new Filter.Value("全部", ""));
        for (String text : texts) {
            if (text.isEmpty()) continue;
            values.add(new Filter.Value(text));
        }
        return new Filter(key, name, values);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Vod> list = new ArrayList<>();
        List<Class> classes = new ArrayList<>();
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        Document doc2 = fetch(siteUrl);
        if (isUnavailable(doc2)) return Result.error(UNAVAILABLE);
        Document doc1 = fetch(siteUrl.concat("/search?genre=裏番"));
        List<String> sorts = doc1.select("div.hentai-sort-options-wrapper").eachText();
        List<String> years = doc1.select("#year option").eachAttr("value");
        for (Element element : doc2.select("a.nav-item")) {
            String text = element.text();
            if (text.equals("新番預告") || text.equals("H漫畫")) continue;
            classes.add(new Class(text));
            List<Filter> array = new ArrayList<>();
            array.add(getFilter("排序", "by", sorts));
            array.add(getFilter("年份", "year", years));
            filters.put(text, array);
        }
        for (Element element : doc2.select("a")) {
            if (element.attr("href").contains("watch")) {
                String pic = element.select("div > img").attr("src");
                String url = element.attr("href");
                String name = element.select("div > div").text();
                String id = url.split("=")[1];
                if (name.contains("smart_display") || name.isEmpty()) continue;
                list.add(new Vod(id, name, pic));
            }
        }
        return Result.string(classes, list, filters);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        List<Vod> list = new ArrayList<>();
        if (extend.get("by") == null) extend.put("by", "最新上市");
        if (extend.get("year") == null) extend.put("year", "");
        String target = siteUrl.concat("/search?genre=").concat(tid).concat("&page=").concat(pg).concat("&sort=").concat(extend.get("by")).concat("&year=").concat(extend.get("year"));
        Document doc = fetch(target);
        if (isUnavailable(doc)) return Result.error(UNAVAILABLE);
        for (Element element : doc.select("div.col-xs-6")) {
            String pic = element.select("img").attr("src");
            String url = element.select("a.overlay").attr("href");
            String name = element.select("div.card-mobile-title").text();
            if (!url.contains("=")) continue;
            String id = url.substring(url.lastIndexOf('=') + 1);
            list.add(new Vod(id, name, pic));
        }
        if (list.isEmpty()) {
            for (Element element : doc.select("a")) {
                if (element.attr("href").contains("watch")) {
                    String pic = element.select("div > img").attr("src");
                    String url = element.attr("href");
                    String name = element.select("div > div").text();
                    String id = url.split("=")[1];
                    if (name.contains("smart_display")) continue;
                    list.add(new Vod(id, name, pic));
                }
            }
        }
        return Result.get().vod(list).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        Document doc = fetch(siteUrl.concat("/watch?v=").concat(ids.get(0)));
        if (isUnavailable(doc)) return Result.error(UNAVAILABLE);
        String name = doc.select("#shareBtn-title").text();
        String metadata = doc.select("script[type=application/ld+json]").html().trim();
        if (metadata.isEmpty()) return Result.get().vod(new ArrayList<Vod>()).string();
        JSONObject json = new JSONObject(metadata);
        String content = json.optString("description");
        String pic = json.optJSONArray("thumbnailUrl") == null ? "" : json.optJSONArray("thumbnailUrl").optString(0);
        String url = json.optString("contentUrl");
        if (url.isEmpty()) return Result.error("Hanime1 播放地址无法解析");
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodPic(pic);
        vod.setVodName(name);
        vod.setVodContent(content);
        vod.setVodPlayFrom("Hanime1");
        vod.setVodPlayUrl("播放$" + url);
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        List<Vod> list = new ArrayList<>();
        String target = siteUrl.concat("/search?query=").concat(URLEncoder.encode(key, "UTF-8")).concat("&genre=&sort=&year=&month=&duration=");
        Document doc = fetch(target);
        if (isUnavailable(doc)) return Result.error(UNAVAILABLE);
        for (Element element : doc.select("div.col-xs-6")) {
            String pic = element.select("img").attr("src");
            String url = element.select("a.overlay").attr("href");
            String name = element.select("div.card-mobile-title").text();
            if (!url.contains("=")) continue;
            String id = url.substring(url.lastIndexOf('=') + 1);
            list.add(new Vod(id, name, pic));
        }
        return Result.get().vod(list).string();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "1".equals(pg) ? searchContent(key, quick) : Result.get().vod(new ArrayList<Vod>()).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        HashMap<String, String> headers = getHeaders();
        headers.put("Referer", siteUrl + "/");
        return Result.get().url(id).header(headers).string();
    }
}
