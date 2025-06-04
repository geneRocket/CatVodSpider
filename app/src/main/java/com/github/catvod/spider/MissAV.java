package com.github.catvod.spider;

import android.content.Context;
import android.util.Log;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;

import org.apache.commons.lang3.StringUtils;
import org.seimicrawler.xpath.JXDocument;
import org.seimicrawler.xpath.JXNode;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MissAV extends Spider {

    Context context;

    @Override
    public void init(Context context) throws Exception {
        this.context = context;
    }

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
        classes.add(new Class("https://missav.ws/dm588/cn/release", "新作上市"));
        classes.add(new Class("https://missav.ws/dm257/cn/monthly-hot", "本月热门"));
        for (int i = 1; i <= 3; i++) {
            JXDocument doc = JXDocument.create(fetch("https://missav.ws/cn/genres?page=" + i));
            List<JXNode> vodNodes = doc.selN("//div[1]/div[3]/div[1]/div/div[*]/a");
            for (JXNode vodNode : vodNodes) {
                String url = vodNode.selOne(".//@href").asString().trim();
                String name = vodNode.asElement().text();
                classes.add(new Class(url, name));
            }
        }
        return Result.string(classes, list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String webUrl = tid + "?page=" + pg;
        JXDocument doc = JXDocument.create(fetch(webUrl));
        List<Vod> list = new ArrayList<>();
        List<JXNode> vodNodes = doc.selN("//div[1]/div[3]/div[2]/div[*]/div");
        for (int i = 0; i < vodNodes.size(); i++) {
            JXNode vodNode = vodNodes.get(i);
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
        return res;
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String webUrl = ids.get(0);
        Vod vod = new Vod();
        vod.setVodId(webUrl);
        vod.setVodPlayFrom("MissAV");
        vod.setVodPlayUrl(webUrl);
        JXDocument doc = JXDocument.create(fetch(webUrl));
        String code = doc.selNOne("//span[text()='番号:']/../span[2]/text()") + "";
        String intro = doc.selNOne("//div[@class='mb-1 text-secondary break-all line-clamp-2']/text()") + "";
        vod.setVodRemarks(code);
        vod.setVodContent(intro);
        vod.setVodYear(doc.selNOne("//span[text()='发行日期:']/../time[1]/text()") + "");
        String actor = doc.selNOne("//span[text()='女优:']/../a[1]/text()") + "";
        if (StringUtils.isNotBlank(actor)) {
            String[] actorParts = actor.split("\\(");
            String linkStr = "[a=cr:{\"scheme\":\"search\"}/]$token[/a]".replace("$token", actorParts[0]);
            if (actorParts.length > 1) {
                linkStr += '(';
                for (int i = 1; i < actorParts.length; i++) {
                    linkStr += actorParts[i];
                }
            }

            vod.setVodActor(linkStr);
        }
        return Result.string(vod);
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, null);
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        WebViewSpider webViewSpider = new WebViewSpider(context);

        String webUrl = "https://missav.ws/cn/search/" + URLEncoder.encode(key, "UTF-8");
        if (StringUtils.isNotBlank(pg)) {
            webUrl += "?page=" + pg;
        }

        Log.d("开始搜索", webUrl);
        String htmlSource = webViewSpider.getHtmlSource(webUrl, getHeaders());

        JXDocument doc = JXDocument.create(htmlSource);
        List<Vod> list = new ArrayList<>();
        List<JXNode> vodNodes = doc.selN("//div[@class='my-2 text-sm text-nord4 truncate']");
        for (JXNode vodNode : vodNodes) {
            String name = vodNode.selOne("./a/text()").asString();
            String pic = vodNode.selOne("./../div[1]/a[1]/img/@data-src").asString();
            String url = vodNode.selOne("./../div[1]/a[1]/@href").asString();
            list.add(new Vod(url, name, pic, ""));
        }
        Log.d("结束搜索", new Gson().toJson(list));

        return Result.string(list);
    }


    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return Result.get().parse().url(id).header(getHeaders()).string();
    }
}
