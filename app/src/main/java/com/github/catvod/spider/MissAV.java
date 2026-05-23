package com.github.catvod.spider;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MissAV extends Spider {

    private static final String siteUrl = "https://missav.ws";
    private Context context;

    @Override
    public void init(Context context) throws Exception {
        this.context = context;
    }

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("DNT", "1");
        headers.put("Upgrade-Insecure-Requests", "1");
        headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36");
        return headers;
    }

    protected String fetch(String webUrl) {
        SpiderDebug.log(webUrl);
        String html = OkHttp.string(webUrl, getHeaders());
        if (isChallenge(html) && context != null) {
            try {
                html = new WebViewSpider(context).getHtmlSource(webUrl, getHeaders());
            } catch (Exception e) {
                SpiderDebug.log(e);
            }
        }
        return html;
    }

    private boolean isChallenge(String html) {
        return !TextUtils.isEmpty(html) && (html.contains("cf-mitigated") || html.contains("_cf_chl_opt") || html.contains("Just a moment"));
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Vod> list = new ArrayList<>();
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("https://missav.ws/dm588/cn/release", "新作上市"));
        classes.add(new Class("https://missav.ws/dm257/cn/monthly-hot", "本月热门"));
        classes.add(new Class("https://missav.ws/cn/actresses", "女优一览"));
        for (int i = 1; i <= 3; i++) {
            JXDocument doc = JXDocument.create(fetch("https://missav.ws/cn/genres?page=" + i));
            List<JXNode> vodNodes = doc.selN("//div[1]/div[3]/div[1]/div/div[*]/a");
            for (JXNode vodNode : vodNodes) {
                String url = vodNode.selOne(".//@href") + "";
                String name = vodNode.asElement().text();
                classes.add(new Class(url, name));
            }
        }
        return Result.string(classes, list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        if (tid.equals("https://missav.ws/cn/actresses")) {
            return ladyList(tid, pg);
        } else {
            String webUrl = tid + "?page=" + pg;
            JXDocument doc = JXDocument.create(fetch(webUrl));
            List<Vod> list = new ArrayList<>();
            List<JXNode> vodNodes = doc.selN("//div[@class='thumbnail group']");
            for (int i = 0; i < vodNodes.size(); i++) {
                JXNode vodNode = vodNodes.get(i);
                String url = vodNode.selOne(".//div[1]/a[1]/@href") + "";
                String pic = vodNode.selOne(".//div[1]/a[1]/img/@data-src") + "";
                String name = vodNode.selOne(".//div[1]/a[1]/img/@alt") + "";

                list.add(new Vod(url, name, pic, ""));
            }
            return Result.string(list);
        }
    }

    private String ladyList(String tid, String pg) {
        List<Vod> vods = new ArrayList<>();
        if (StringUtils.isNotBlank(pg)) {
            tid = tid + "?page=" + pg;
        }
        JXDocument doc = JXDocument.create(fetch(tid));
        List<JXNode> vodNodes = doc.selN("//img[@class='object-cover object-top w-full h-full']");
        for (JXNode vodNode : vodNodes) {
            String url = vodNode.selOne("./../../@href") + "";
            String name = vodNode.selOne("./@alt").asString();
            String pic = vodNode.selOne("./@src").asString();
            String remark = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                remark = vodNode.sel("./../../../div/a/p[2]/text()").stream().map(JXNode::asString).collect(Collectors.joining(","));
            }
            Vod vod=new Vod(url, name, pic, remark, true);
            vod.setStyle(Vod.Style.rect());
            vods.add(vod);
        }
        return Result.string(vods);
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
        List<JXNode> actors = doc.selN("//span[text()='女优:']/../a");
        if (!actors.isEmpty()) {
            StringBuilder linkStr = new StringBuilder();
            for (JXNode actorNode : actors) {
                String actor = actorNode.selOne("./text()").asString().trim();
                String href = actorNode.selOne("./@href").asString().trim();
                linkStr.append("[a=cr:$json/]".replace("$json", new Gson().toJson(new Class(href, actor)))).append(actor).append("[/a]");
                linkStr.append(" ");
            }
            vod.setVodActor(linkStr.toString().trim());
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
            String name = vodNode.selOne("./a/text()") + "";
            String pic = vodNode.selOne("./../div[1]/a[1]/img/@data-src") + "";
            String url = vodNode.selOne("./../div[1]/a[1]/@href") + "";
            list.add(new Vod(url, name, pic, ""));
        }
        Log.d("结束搜索", new Gson().toJson(list));
        return Result.string(list);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        WebViewVIdeoUrlSpider webViewVIdeoUrlSpider = new WebViewVIdeoUrlSpider(context);
        String script = "document.getElementsByClassName('plyr__control plyr__control--overlaid')[0].click()";
        Pattern sniffer = Pattern.compile("http((?!http).){12,}?\\.(m3u8)\\?.*|http((?!http).){12,}\\.(m3u8)");
        String webUrl = id;
        String videoUrl = webViewVIdeoUrlSpider.getVideoUrl(webUrl, getHeaders(), script, sniffer);
        if (TextUtils.isEmpty(videoUrl)) return "";
        HashMap<String, String> headers = getHeaders();
        headers.putAll(webViewVIdeoUrlSpider.getVideoHeaders());
        headers.put("Referer", webUrl);
        headers.put("Origin", siteUrl);
        return Result.get().url(videoUrl).header(headers).string();
    }
}
