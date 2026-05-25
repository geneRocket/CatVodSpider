package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;

public class NinetyOnePorn extends Spider {

    private static final String siteUrl = "https://www.91porn.com";
    private static final Pattern SOURCE = Pattern.compile("<source\\b[^>]*\\bsrc\\s*=\\s*['\"]([^'\"]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_URL = Pattern.compile("(https?://[^'\"<>\\s]+\\.(?:m3u8|mp4)(?:\\?[^'\"<>\\s]*)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRENCODE_PATTERN = Pattern.compile("strencode\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]+)['\"](?:\\s*,\\s*['\"]([^'\"]*)['\"])?", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRENCODE2_PATTERN = Pattern.compile("strencode2\\s*\\(\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("(?:添加时间|Added)[:：]?\\s*(\\d{4}-\\d{2}-\\d{2})");
    private Context context;

    @Override
    public void init(Context context) throws Exception {
        this.context = context;
    }

    /**
     * 随机生成国内段 IP 防止每日的限制
     */
    private String getRandomIp() {
        Random random = new Random();
        int[] prefixes = {112, 113, 116, 117, 120, 123, 180, 218, 221, 222};
        int prefix = prefixes[random.nextInt(prefixes.length)];
        return prefix + "." +
                random.nextInt(254) + "." +
                random.nextInt(254) + "." +
                (random.nextInt(253) + 1);
    }

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Cache-Control", "max-age=0");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        headers.put("X-Forwarded-For", getRandomIp());
        // 强制中文语言，防止返回英文页面导致解析错误
        headers.put("Cookie", "session_language=cn_CN; language=cn_CN;");
        return headers;
    }

    private String fetch(String url) {
        Request request = new Request.Builder().url(url).headers(Headers.of(getHeaders())).build();
        try (Response response = OkHttp.client().newBuilder()
                .callTimeout(15, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()) {
            String html = response.body() == null ? "" : response.body().string();
            return isChallenge(html) ? fetchByWebView(url) : html;
        } catch (IOException e) {
            return "";
        }
    }

    private boolean isChallenge(String html) {
        return html != null && (html.contains("cf-mitigated") || html.contains("_cf_chl_opt") || html.contains("Just a moment"));
    }

    private String fetchByWebView(String url) {
        if (context == null) return "";
        try {
            return new WebViewSpider(context).getHtmlSource(url, getHeaders());
        } catch (Exception e) {
            return "";
        }
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
        String html = fetch(url);
        List<Vod> list = parseList(html);
        int page = parsePage(pg);
        int pageCount = parsePageCount(html, page);
        return Result.get().page(page, pageCount, 24, pageCount * 24).vod(list).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0);
        String htmlSource=fetch(url);
        Document doc = Jsoup.parse(htmlSource);

        Element title = doc.selectFirst("h4.login_register_header, h4, .video-title, title");
        String name = title == null ? "" : title.text().replace("收藏", "").trim();

        Element video = doc.selectFirst("video");
        String pic = video != null ? video.attr("poster") : "";

        String date = parseDate(doc.text());

        Element actorEl = doc.selectFirst("a[href*=uprofile.php], a[href^=author.php], .author");
        String actor = actorEl != null ? actorEl.text().trim() : "";

        Element contentEl = doc.selectFirst("#v_desc, .video-desc, .description");
        String content = contentEl != null ? contentEl.text().trim() : "";

        Vod vod = new Vod();
        vod.setVodId(url);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodYear(date);
        vod.setVodActor(actor);
        vod.setVodContent(content);
        vod.setVodPlayFrom("91Porn");
        vod.setVodPlayUrl(url);
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
        String url = id.contains("view_video") ? parseSource(id, fetch(id)) : id;
        HashMap<String, String> headers = getHeaders();
        headers.put("Referer", id.contains("view_video") ? id : siteUrl + "/");
        return Result.get().url(url).header(headers).string();
    }

    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        HashSet<String> parsedUrls = new HashSet<>();
        Document doc = Jsoup.parse(html);
        for (Element element : doc.select("div.well.well-sm, div.list-channel, div.video-box")) {
            if (element.classNames().contains("col-lg-8")) continue;
            Element parent = element.parent();
            if (parent != null && parent.classNames().contains("col-lg-8")) continue;
            Element a = element.selectFirst("a[href*=view_video]");
            if (a == null) continue;

            String url = fixUrl(a.attr("href"));
            if (parsedUrls.contains(url)) continue;
            parsedUrls.add(url);

            String name = "";
            Element titleEl = element.selectFirst("span.video-title, .video-title, .title");
            if (titleEl != null) {
                name = titleEl.text().trim();
            } else {
                name = a.text().trim();
            }

            // 处理图片的懒加载和防盗链
            Element img = element.selectFirst("img");
            String pic = "";
            if (img != null) {
                pic = img.attr("src");
                if (pic.contains("blank.gif") || pic.isEmpty() || pic.contains("loading")) {
                    if (img.hasAttr("data-src")) {
                        pic = img.attr("data-src");
                    } else if (img.hasAttr("data-original")) {
                        pic = img.attr("data-original");
                    } else if (img.hasAttr("dynamic-src")) {
                        pic = img.attr("dynamic-src");
                    }
                }
            }

            String remark = "";
            Element durationEl = element.selectFirst("span.duration, .duration");
            if (durationEl != null) {
                remark = durationEl.text().trim();
            }

            if (url.isEmpty() || name.isEmpty()) continue;
            list.add(new Vod(url, name, pic, remark));
        }
        return list;
    }

    private String parseDate(String text) {
        Matcher matcher = DATE.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
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
        Matcher matcher = Pattern.compile("[?&]page=(\\d+)").matcher(html);
        while (matcher.find()) count = Math.max(count, parsePage(matcher.group(1)));
        return count;
    }

    private String fixUrl(String url) {
        if (url.startsWith("http")) return url;
        if (url.startsWith("/")) return siteUrl + url;
        return siteUrl + "/" + url;
    }

    private String parseSource(String html) throws Exception {
        if (html == null || html.isEmpty()) return "";

        // 1. 优先尝试解析 strencode，算法与 /js/m.js 中的 strencode 保持一致。
        Matcher matcher = STRENCODE_PATTERN.matcher(html);
        while (matcher.find()) {
            try {
                String decoded = decodeStrencode(matcher.group(1), matcher.group(2), matcher.group(3));
                String source = extractSource(decoded);
                if (!source.isEmpty()) return source;
            } catch (Exception e) {
                // 忽略当前匹配块的失败，继续尝试后续匹配
            }
        }

        // 2. 尝试解析 strencode2，它在 /js/m2.js 中只是 JS unescape(input)。
        matcher = STRENCODE2_PATTERN.matcher(html);
        while (matcher.find()) {
            try {
                String source = extractSource(unescape(matcher.group(1)));
                if (!source.isEmpty()) return source;
            } catch (Exception e) {
                // 忽略当前匹配块的失败，继续尝试后续匹配
            }
        }

        // 3. 尝试在去除注释后的源码中直接寻找 source 标签
        String source = extractSource(html.replaceAll("(?s)<!--.*?-->", ""));
        if (!source.isEmpty()) return source;

        // 4. 兜底策略：在全文捕获可能的 m3u8 或者 mp4 直链
        Matcher m4 = VIDEO_URL.matcher(html);
        if (m4.find()) return m4.group(1);

        return "";
    }

    private String parseSource(String url, String html) throws Exception {
        String source = parseSource(html);
        if (!source.isEmpty() || context == null) return source;
        return parseSource(fetchByWebView(url));
    }

    private String extractSource(String html) {
        if (html == null || html.isEmpty()) return "";
        Element source = Jsoup.parseBodyFragment(html).selectFirst("source[src]");
        if (source != null) return source.attr("src").trim();
        Matcher matcher = SOURCE.matcher(html);
        if (matcher.find()) return Parser.unescapeEntities(matcher.group(1), true).trim();
        matcher = VIDEO_URL.matcher(html);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String decodeStrencode(String cipher, String key, String mode) {
        try {
            if (mode != null && mode.endsWith("2")) {
                String temp = cipher;
                cipher = key;
                key = temp;
            }
            byte[] cipherBytes = Base64.decode(cipher.trim(), Base64.DEFAULT);
            byte[] keyBytes = key.getBytes("UTF-8");
            int keyLen = keyBytes.length;
            byte[] xorBytes = new byte[cipherBytes.length];
            for (int i = 0; i < cipherBytes.length; i++) {
                int k = i % keyLen;
                xorBytes[i] = (byte) ((cipherBytes[i] & 0xFF) ^ (keyBytes[k] & 0xFF));
            }
            String xorStr = new String(xorBytes, "UTF-8").trim();
            byte[] finalBytes = Base64.decode(xorStr, Base64.DEFAULT);
            return new String(finalBytes, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private String unescape(String src) {
        if (src == null) return "";
        StringBuilder tmp = new StringBuilder();
        tmp.ensureCapacity(src.length());
        int lastPos = 0, pos = 0;
        char ch;
        while (pos < src.length()) {
            pos = src.indexOf("%", lastPos);
            if (pos == lastPos) {
                if (pos + 1 < src.length() && src.charAt(pos + 1) == 'u') {
                    if (pos + 6 <= src.length()) {
                        try {
                            ch = (char) Integer.parseInt(src.substring(pos + 2, pos + 6), 16);
                            tmp.append(ch);
                            lastPos = pos + 6;
                        } catch (NumberFormatException e) {
                            tmp.append(src.substring(pos, pos + 2));
                            lastPos = pos + 2;
                        }
                    } else {
                        tmp.append(src.substring(pos));
                        lastPos = src.length();
                    }
                } else {
                    if (pos + 3 <= src.length()) {
                        try {
                            ch = (char) Integer.parseInt(src.substring(pos + 1, pos + 3), 16);
                            tmp.append(ch);
                            lastPos = pos + 3;
                        } catch (NumberFormatException e) {
                            tmp.append(src.substring(pos, pos + 1));
                            lastPos = pos + 1;
                        }
                    } else {
                        tmp.append(src.substring(pos));
                        lastPos = src.length();
                    }
                }
            } else {
                if (pos == -1) {
                    tmp.append(src.substring(lastPos));
                    lastPos = src.length();
                } else {
                    tmp.append(src.substring(lastPos, pos));
                    lastPos = pos;
                }
            }
        }
        return tmp.toString();
    }
}
