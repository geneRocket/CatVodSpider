package com.github.catvod.spider;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class XHamster extends Spider {
    private static final String SITE = "https://xhamster.com";

    private HashMap<String, String> headers() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Util.CHROME);
        headers.put("Referer", SITE + "/");
        return headers;
    }

    private Document fetch(String url) {
        return Jsoup.parse(OkHttp.string(url, headers()), url);
    }

    private List<Vod> videos(Document doc) {
        List<Vod> list = new ArrayList<>();
        for (Element tile : doc.select("div.video-thumb[data-video-id]")) {
            Element link = tile.selectFirst("a[data-role=thumb-link][href*=/videos/]");
            if (link == null) continue;
            String id = link.absUrl("href");
            String title = link.attr("aria-label");
            Element img = link.selectFirst("img[src]");
            String pic = img == null ? "" : img.absUrl("src");
            if (!id.isEmpty() && !title.isEmpty()) list.add(new Vod(id, title, pic));
        }
        return list;
    }

    @Override
    public String homeContent(boolean filter) {
        Document doc = fetch(SITE + "/");
        List<Class> classes = new ArrayList<>();
        Set<String> categoryIds = new HashSet<>();
        for (Element link : doc.select("a[href*=/categories/]")) {
            String id = link.absUrl("href");
            String name = link.text().trim();
            if (!id.isEmpty() && !name.isEmpty() && categoryIds.add(id)) {
                classes.add(new Class(id, name));
            }
        }
        return Result.string(classes, videos(doc));
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        String url = tid + (tid.contains("?") ? "&" : "?") + "page=" + pg;
        return Result.get().vod(videos(fetch(url))).string();
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = ids.get(0);
        Document doc = fetch(id);
        String name = doc.select("meta[property=og:title]").attr("content");
        String pic = doc.select("meta[property=og:image]").attr("content");
        if (name.isEmpty() || doc.selectFirst("link[href*=m3u8]") == null) return Result.error("视频页无法解析");
        Vod vod = new Vod(id, name, pic);
        vod.setVodPlayFrom("XHamster");
        vod.setVodPlayUrl("播放$" + id);
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        String url = SITE + "/search/" + URLEncoder.encode(key, "UTF-8") + "?page=" + pg;
        return Result.get().vod(videos(fetch(url))).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // The signed playlist expires; resolve it when playback starts.
        Document doc = fetch(id);
        Element link = doc.selectFirst("link[href*=m3u8]");
        if (link == null) return Result.error("播放地址无法解析");
        return Result.get().url(link.absUrl("href")).header(headers()).string();
    }
}
