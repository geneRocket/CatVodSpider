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
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NinetyOnePorn extends Spider {

    private static final String siteUrl = "https://www.91porn.com";
    private static final Pattern SOURCE = Pattern.compile("<source\\s+src=['\"]([^'\"]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRENCODE_PATTERN = Pattern.compile("strencode\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern STRENCODE2_PATTERN = Pattern.compile("strencode2\\s*\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern DATE = Pattern.compile("(?:添加时间|Added)[:：]?\\s*(\\d{4}-\\d{2}-\\d{2})");

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

        Element title = doc.selectFirst("h4.login_register_header, h4, .video-title, title");
        String name = title == null ? "" : title.text().replace("收藏", "").trim();

        Element video = doc.selectFirst("video");
        String pic = video != null ? video.attr("poster") : "";

        String date = parseDate(doc.text());

        Element actorEl = doc.selectFirst("a[href*=uprofile.php], a[href^=author.php], .author");
        String actor = actorEl != null ? actorEl.text().trim() : "";

        Element contentEl = doc.selectFirst("#v_desc, .video-desc, .description");
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
        HashSet<String> parsedUrls = new HashSet<>();
        Document doc = Jsoup.parse(html);
        for (Element element : doc.select("div.well.well-sm, div.list-channel, div.video-box, div.col-xs-12")) {
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
        // 1. 优先尝试解析 strencode（含有 cipher 和 key 两个主要参数的 XOR 解密算法）
        Matcher matcher = STRENCODE_PATTERN.matcher(html);
        while (matcher.find()) {
            try {
                String cipher = URLDecoder.decode(matcher.group(1), "UTF-8");
                String key = URLDecoder.decode(matcher.group(2), "UTF-8");
                String decoded = decodeStrencode(cipher, key);

                if (!decoded.isEmpty()) {
                    Matcher m2 = SOURCE.matcher(decoded);
                    if (m2.find()) return m2.group(1);

                    Matcher m3 = Pattern.compile("(https?://[^'\"]+\\.(?:m3u8|mp4)[^'\"]*)").matcher(decoded);
                    if (m3.find()) return m3.group(1);
                }
            } catch (Exception e) {
                // 忽略当前匹配块的失败，继续尝试后续匹配
            }
        }

        // 2. 尝试解析 strencode2（纯 unescape 编码的参数）
        matcher = STRENCODE2_PATTERN.matcher(html);
        while (matcher.find()) {
            try {
                String encoded = URLDecoder.decode(matcher.group(1), "UTF-8");
                String decoded = unescape(encoded);

                if (!decoded.isEmpty()) {
                    Matcher m2 = SOURCE.matcher(decoded);
                    if (m2.find()) return m2.group(1);

                    Matcher m3 = Pattern.compile("(https?://[^'\"]+\\.(?:m3u8|mp4)[^'\"]*)").matcher(decoded);
                    if (m3.find()) return m3.group(1);
                }
            } catch (Exception e) {
                // 忽略当前匹配块的失败，继续尝试后续匹配
            }
        }

        // 3. 尝试在去除注释后的源码中直接寻找 source 标签
        matcher = SOURCE.matcher(html.replaceAll("(?s)<!--.*?-->", ""));
        if (matcher.find()) return matcher.group(1);

        // 4. 兜底策略：在全文捕获可能的 m3u8 或者 mp4 直链
        Matcher m4 = Pattern.compile("(https?://[^'\"]+\\.(?:m3u8|mp4)[^'\"]*)").matcher(html);
        if (m4.find()) return m4.group(1);

        return "";
    }

    private String decodeStrencode(String cipher, String key) {
        try {
            // 1. 将密文先进行 Base64 解码，还原成字节数组
            byte[] cipherBytes = Base64.getDecoder().decode(cipher.trim());
            byte[] keyBytes = key.getBytes("UTF-8");
            int keyLen = keyBytes.length;
            byte[] xorBytes = new byte[cipherBytes.length];

            // 2. 利用密钥逐字节进行 XOR 运算
            for (int i = 0; i < cipherBytes.length; i++) {
                int k = i % keyLen;
                xorBytes[i] = (byte) ((cipherBytes[i] & 0xFF) ^ (keyBytes[k] & 0xFF));
            }

            // 3. XOR 后的数据是一个 Base64 格式的字符串，需要再次对其进行 Base64 解码获得最终结果
            String xorStr = new String(xorBytes, "UTF-8").trim();
            byte[] finalBytes = Base64.getDecoder().decode(xorStr);
            return new String(finalBytes, "UTF-8");
        } catch (Exception e) {
            // 如果遇到异常，尝试使用 Mime 格式解码作为兼容手段
            try {
                byte[] cipherBytes = Base64.getMimeDecoder().decode(cipher.trim());
                byte[] keyBytes = key.getBytes("UTF-8");
                int keyLen = keyBytes.length;
                byte[] xorBytes = new byte[cipherBytes.length];
                for (int i = 0; i < cipherBytes.length; i++) {
                    int k = i % keyLen;
                    xorBytes[i] = (byte) ((cipherBytes[i] & 0xFF) ^ (keyBytes[k] & 0xFF));
                }
                String xorStr = new String(xorBytes, "UTF-8").trim();
                byte[] finalBytes = Base64.getMimeDecoder().decode(xorStr);
                return new String(finalBytes, "UTF-8");
            } catch (Exception ex) {
                return "";
            }
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