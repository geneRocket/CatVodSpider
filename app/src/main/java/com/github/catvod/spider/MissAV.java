package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.seimicrawler.xpath.JXDocument;
import org.seimicrawler.xpath.JXNode;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MissAV extends Spider {

    private static final String siteUrl = "https://missav.ws";
    private static final String cateUrl = siteUrl + "/dm588/cn/";
    private static final String detailUrl = siteUrl + "/videos/";
    private static final String searchUrl = siteUrl + "/search/";

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("dnt", "1");
        headers.put("priority", "u=0, i");
        headers.put("sec-ch-ua", "\"Chromium\";v=\"136\", \"Google Chrome\";v=\"136\", \"Not.A/Brand\";v=\"99\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"macOS\"");
        headers.put("sec-fetch-dest", "document");
        headers.put("sec-fetch-mode", "navigate");
        headers.put("sec-fetch-site", "none");
        headers.put("sec-fetch-user", "?1");
        headers.put("sec-gpc", "1");
        headers.put("upgrade-insecure-requests", "1");
        headers.put("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Vod> list = new ArrayList<>();
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("release", "新作上市"));
        return Result.string(classes, list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        List<Vod> list = new ArrayList<>();
        String webUrl = cateUrl + tid + "?page=" + pg;
        JXDocument doc = JXDocument.create(fetch(webUrl));
        List<JXNode> vodNodes = doc.selN("//div[1]/div[3]/div[2]/div[*]/div");
        for (int i = 0; i < vodNodes.size(); i++) {
            JXNode vodNode= vodNodes.get(i);
            String url = vodNode.selOne(".//div[1]/a[1]/@href").asString().trim();
            String pic = vodNode.selOne(".//div[1]/a[1]/img/@data-src").asString().trim();
            String name = vodNode.selOne(".//div[1]/a[1]/img/@alt").asString().trim();
            list.add(new Vod(url, name, pic, ""));
        }
        return Result.string(list);
    }

    protected String fetch(String webUrl) {
        SpiderDebug.log(webUrl);
        String res = OkHttp.string(webUrl, getHeaders());
        SpiderDebug.log(res);
        return res;
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {

        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodPlayFrom("MissAV");
        vod.setVodPlayUrl( ids.get(0));
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        List<Vod> list = new ArrayList<>();
        String html = fetch(searchUrl.concat(URLEncoder.encode(key, "UTF-8")).concat("/"));
        Document doc = Jsoup.parse(html);
        for (Element element : doc.select("div.video-item")) {
            String pic = element.select("img").attr("data-src");
            String url = element.select("a").attr("href");
            String name = element.select("div.title").text();
            String id = url.split("/")[4];
            list.add(new Vod(id, name, pic));
        }
        return Result.string(list);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return Result.get().parse(1).url(id).header(getHeaders()).string();
    }
}
