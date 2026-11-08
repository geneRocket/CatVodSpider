package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Eighteen extends Spider {

    private static final String UNAVAILABLE = "18AV 暂时无法访问或年龄验证未通过";
    private String url = "https://mjv002.com/zh/";
    private OkHttpClient client;

    @Override
    public void init(Context context, String extend) throws Exception {
        if (extend != null && extend.startsWith("https://")) url = extend.replaceAll("/+$", "") + "/";
        String host = HttpUrl.get(url).host();
        client = OkHttp.client().newBuilder().cookieJar(new CookieJar() {
            private List<Cookie> session = Collections.emptyList();

            @Override
            public synchronized void saveFromResponse(HttpUrl responseUrl, List<Cookie> cookies) {
                if (!host.equals(responseUrl.host())) return;
                List<Cookie> updated = new ArrayList<>(session);
                for (Cookie cookie : cookies) {
                    for (int i = updated.size() - 1; i >= 0; i--) {
                        Cookie old = updated.get(i);
                        if (old.name().equals(cookie.name()) && old.domain().equals(cookie.domain())
                                && old.path().equals(cookie.path())) updated.remove(i);
                    }
                    if (cookie.expiresAt() > System.currentTimeMillis()) updated.add(cookie);
                }
                session = updated;
            }

            @Override
            public synchronized List<Cookie> loadForRequest(HttpUrl requestUrl) {
                List<Cookie> matching = new ArrayList<>();
                if (!host.equals(requestUrl.host())) return matching;
                for (Cookie cookie : session) {
                    if (cookie.expiresAt() > System.currentTimeMillis() && cookie.matches(requestUrl)) matching.add(cookie);
                }
                return matching;
            }
        }).followRedirects(false).build();
        Request gate = new Request.Builder().url(url + "chinese_IamOverEighteenYearsOld/19/index.html").build();
        try (Response response = client.newCall(gate).execute()) {
            // The age confirmation can send cookies and redirect to an advertisement.
        } catch (IOException ignored) { }
    }

    private Document fetch(Request request) {
        for (int redirects = 0; redirects <= 5; redirects++) {
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    Document doc = Jsoup.parse(response.body().string(), request.url().toString());
                    return doc.selectFirst("#fwin_dialog_submit") == null ? doc : null;
                }
                String location = response.header("Location");
                if (!response.isRedirect() || location == null) return null;
                HttpUrl current = request.url();
                HttpUrl next = current.resolve(location);
                if (next == null || !current.scheme().equals(next.scheme())
                        || !current.host().equals(next.host()) || current.port() != next.port()) return null;
                Request.Builder redirected = request.newBuilder().url(next);
                if (response.code() != 307 && response.code() != 308) redirected.get();
                request = redirected.build();
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private Document fetch(String target) {
        return fetch(new Request.Builder().url(target).build());
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        List<Vod> list = new ArrayList<>();
        Document doc = fetch(url);
        if (doc == null) return Result.error(UNAVAILABLE);
        for (Element a : doc.select("ul.animenu__nav > li > a")) {
            String typeName = a.text();
            String typeId = a.attr("href").replace(url, "");
            if (!typeId.contains("random/all/")) continue;
            if (typeName.contains("18H")) break;
            classes.add(new Class(typeId, typeName));
        }
        for (Element div : doc.select("div.post")) {
            String id = div.select("a").attr("href").replace(url, "");
            String name = div.select("h3").text();
            String pic = div.select("a > img").attr("src");
            String remark = div.select("div.meta").text();
            list.add(new Vod(id, name, pic, remark));
        }
        return Result.string(classes, list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        List<Vod> list = new ArrayList<>();
        tid = tid.replace("random", "list");
        tid = tid.replace("index", pg);
        Document doc = fetch(url + tid);
        if (doc == null) return Result.error(UNAVAILABLE);
        for (Element div : doc.select("div.post")) {
            String id = div.select("a").attr("href").replace(url, "");
            String name = div.select("h3").text();
            String pic = div.select("a > img").attr("src");
            String remark = div.select("div.meta").text();
            list.add(new Vod(id, name, pic, remark));
        }
        return Result.get().vod(list).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        Document doc = fetch(url + ids.get(0));
        if (doc == null) return Result.error(UNAVAILABLE);
        Element wrap = doc.selectFirst("div.video-wrap");
        if (wrap == null) return Result.get().vod(new ArrayList<Vod>()).string();
        String name = wrap.select("div.archive-title > h1").text();
        String pic = wrap.select("div.player-wrap > img").attr("src");
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodPic(pic);
        vod.setVodName(name);
        vod.setVodPlayFrom("18AV");
        vod.setVodPlayUrl("播放$" + ids.get(0));
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return searchContent(key, pg);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        HttpUrl target = HttpUrl.get(url + id);
        List<String> cookies = new ArrayList<>();
        for (Cookie cookie : client.cookieJar().loadForRequest(target)) {
            cookies.add(cookie.name() + "=" + cookie.value());
        }
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Referer", url);
        if (!cookies.isEmpty()) headers.put("Cookie", android.text.TextUtils.join("; ", cookies));
        return Result.get().parse().url(target.toString()).header(headers).string();
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        return true;
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        return !url.contains("afcdn.net") && (url.contains(".m3u8") || url.contains(".mp4"));
    }

    private String searchContent(String key, String pg) {
        FormBody form = new FormBody.Builder().add("search_keyword", key).add("search_type", "fc").add("op", "search").build();
        Document doc = fetch(new Request.Builder().url(url + "searchform_search/all/" + pg + ".html").post(form).build());
        if (doc == null) return Result.error(UNAVAILABLE);
        List<Vod> list = new ArrayList<>();
        for (Element div : doc.select("div.post")) {
            String id = div.select("a").attr("href").replace(url, "");
            String name = div.select("h3").text();
            String pic = div.select("a > img").attr("src");
            String remark = div.select("div.meta").text();
            list.add(new Vod(id, name, pic, remark));
        }
        return Result.get().vod(list).string();
    }
}
