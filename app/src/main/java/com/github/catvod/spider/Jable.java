package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class Jable extends Spider {

    private static final String siteUrl = "https://jable.tv";
    private static final String cateUrl = siteUrl + "/categories/";
    private static final String detailUrl = siteUrl + "/videos/";
    private static final String searchUrl = siteUrl + "/search/";
    private Context context;
    private final CloudflareHtml browser = new CloudflareHtml();

    @Override
    public void init(Context context) {
        this.context = context;
    }

    private Document fetch(String url) {
        return browser.fetch(context, url, getHeaders());
    }

    private List<Vod> videos(Document doc) {
        List<Vod> list = new ArrayList<>();
        for (Element element : doc.select("div.video-img-box")) {
            Element link = element.selectFirst("a[href*=/videos/]");
            if (link == null) continue;
            String id = link.absUrl("href").replaceFirst(".*/videos/([^/?#]+).*", "$1");
            String name = element.select("div.detail > h6").text();
            Element img = element.selectFirst("img");
            String pic = img == null ? "" : img.hasAttr("data-src") ? img.absUrl("data-src") : img.absUrl("src");
            if (!id.isEmpty() && !name.isEmpty()) list.add(new Vod(id, name, pic));
        }
        return list;
    }

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Util.CHROME);
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Vod> list = new ArrayList<>();
        List<Class> classes = new ArrayList<>();
        Document doc = fetch(cateUrl);
        for (Element element : doc.select("div.img-box > a")) {
            String typeId = element.absUrl("href").replaceFirst(".*/categories/([^/?#]+).*", "$1");
            String typeName = element.select("div.absolute-center > h4").text();
            if (!typeId.isEmpty() && !typeName.isEmpty()) classes.add(new Class(typeId, typeName));
        }
        list.addAll(videos(fetch(siteUrl)));
        return Result.string(classes, list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        List<Vod> list = new ArrayList<>();
        String target = cateUrl + tid + "/?mode=async&function=get_block&block_id=list_videos_common_videos_list&sort_by=post_date&from=" + String.format(Locale.getDefault(), "%02d", Integer.parseInt(pg)) + "&_=" + System.currentTimeMillis();
        Document doc = fetch(target);
        list.addAll(videos(doc));
        return Result.get().vod(list).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        Document doc = fetch(detailUrl.concat(ids.get(0)).concat("/"));
        String hlsUrl = Util.getVar(doc.html(), "hlsUrl");
        if (hlsUrl.isEmpty()) return Result.error("播放地址无法解析");
        String name = doc.select("meta[property=og:title]").attr("content");
        String pic = doc.select("meta[property=og:image]").attr("content");
        String year = doc.select("span.inactive-color").text();
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodPic(pic);
        vod.setVodYear(year.replace("上市於 ", ""));
        vod.setVodName(name);
        vod.setVodPlayFrom("Jable");
        vod.setVodPlayUrl("播放$" + hlsUrl);
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        List<Vod> list = new ArrayList<>();
        Document doc = fetch(searchUrl.concat(URLEncoder.encode(key, "UTF-8")).concat("/"));
        list.addAll(videos(doc));
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
