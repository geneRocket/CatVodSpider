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
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NinetyOnePorn extends Spider {

    private static final String siteUrl = "https://www.91porn.com";
    private static final Pattern SOURCE = Pattern.compile("<source\\s+src=['\"]([^'\"]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED = Pattern.compile("strencode2?\\s*\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern DATE = Pattern.compile("(?:添加时间|Added)[:：]?\\s*(\\d{4}-\\d{2}-\\d{2})");

    /**
     * 随机生成国内段 IP 防止每日 10 次的观看限额
     */
    private String getRandomIp() {
        Random random = new Random();
        return (random.nextInt(215) + 1) + "." +
                (random.nextInt(254) + 1) + "." +
                (random.nextInt(254) + 1) + "." +
                (random.nextInt(254) + 1);
    }

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Accept-Encoding", "gzip, deflate");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Cache-Control", "max-age=0");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        headers.put("X-Forwarded-For", getRandomIp());
        // 强制中文语言，防止返回英文页面导致解析错误
        headers.put("Cookie", "session_language=cn_CN; language=cn_CN;");
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

        Element title = doc.selectFirst("h4.login_register_header, h4");
        String name = title == null ? "" : title.text().replace("收藏", "").trim();

        Element video = doc.selectFirst("video");
        String pic = video != null ? video.attr("poster") : "";

        String date = parseDate(doc.text());

        Element actorEl = doc.selectFirst("a[href*=uprofile.php], a[href^=author.php]");
        String actor = actorEl != null ? actorEl.text().trim() : "";

        Element contentEl = doc.selectFirst("#v_desc");
        String content = contentEl != null ? contentEl.text().trim() : "";

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
        // 注意：最近91Porn针对非登录且无积分用户限制了搜索，此接口视目标网站政策可能无有效返回内容
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
        for (Element element : doc.select("div.well.well-sm, div.list-channel")) {
            Element parent = element.parent();
            if (parent != null && parent.classNames().contains("col-lg-8")) continue;
            Element a = element.selectFirst("a[href*=view_video]");
            if (a == null) continue;

            String url = fixUrl(a.attr("href"));
            String name = element.select("span.video-title").text();

            // 处理图片的懒加载和防盗链
            Element img = element.selectFirst("img");
            String pic = "";
            if (img != null) {
                pic = img.attr("src");
                if (pic.contains("blank.gif") || pic.isEmpty()) {
                    pic = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("data-original");
                }
            }

            String remark = element.select("span.duration").text();
            if (url.isEmpty() || name.isEmpty()) continue;
            list.add(new Vod(url, name, pic, remark));
        }
        return list;
    }

    private String parseDate(String text) {
        Matcher matcher = DATE.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
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

            // 尝试在解密内容里找 <source>
            Matcher m2 = SOURCE.matcher(decoded);
            if (m2.find()) return m2.group(1);

            // 如果解密后直接是纯URL的情况
            Matcher m3 = Pattern.compile("(https?://[^'\"]+\\.(?:m3u8|mp4)[^'\"]*)").matcher(decoded);
            if (m3.find()) return m3.group(1);
        }

        // 尝试在源码去除注释后找
        matcher = SOURCE.matcher(html.replaceAll("(?s)<!--.*?-->", ""));
        if (matcher.find()) return matcher.group(1);

        // 最后兜底：直接全文捕获 m3u8 或者 mp4 的视频外链
        Matcher m4 = Pattern.compile("(https?://[^'\"]+\\.(?:m3u8|mp4)[^'\"]*)").matcher(html);
        if (m4.find()) return m4.group(1);

        return "";
    }
}
