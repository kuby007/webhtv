package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.bean.Proxy;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 站点级代理（移植自 AVBox Xbox 的 SiteProxy 语义，接入 webhtv 现有 OkProxySelector）。
 *
 * 规则（与站点配置 "proxy" 字段一致）：
 *   - 字段缺省或为空 -> 该站不走代理；
 *   - 字段是合法代理地址（http://、https://、socks 开头）-> 用该地址；
 *   - 字段是其他非空值（如占位标记 "proxy"）-> 回落到配置全局 proxy 规则的第一条地址。
 *
 * 生效方式：当前 home 站生效时，向 OkProxySelector 装入一条名为 RULE_NAME 的通配规则，
 * 切站时先卸后装。OkHttp 请求不带站点身份，这是"当前站"近似（与 AVBox 相同）。
 * py 爬虫不走 OkHttp：当前生效地址经 currentUrl() 暴露，chaquo 的 spider.py 读取后
 * 作为 requests 的 proxies 参数。
 *
 * 首页开关：override 按站持久化（Prefers），优先于字段判定。
 */
public final class SiteProxy {

    private static final String RULE_NAME = "__site_proxy__";
    private static final String PREF_KEY = "site_proxy_override";

    private static volatile String currentUrl = "";
    private static volatile String currentKey = "";

    private SiteProxy() {
    }

    /** 当前生效的代理地址（空 = 不走代理）。供 py 侧读取。 */
    public static String currentUrl() {
        return currentUrl;
    }

    /** 站点最终是否走代理：override 优先，其次看 proxy 字段是否非空。 */
    public static boolean isEnabled(Site site) {
        if (site == null) return false;
        Boolean override = getOverride(site);
        if (override != null) return override;
        return !site.getProxy().isEmpty();
    }

    /** 解析站点应使用的代理地址；null = 无可用地址。 */
    public static String resolve(Site site) {
        if (site == null) return null;
        String value = site.getProxy().trim();
        if (isProxyUrl(value)) return value;
        return globalFallback();
    }

    /** 对当前 home 站应用判定结果：装/卸通配规则，并更新 py 侧可见的当前地址。 */
    public static synchronized void apply(Site site) {
        OkHttp.selector().remove(RULE_NAME);
        currentUrl = "";
        currentKey = site == null ? "" : site.getKey();
        if (site == null || !isEnabled(site)) {
            SpiderDebug.log("proxy", "site-proxy off site=%s", currentKey);
            return;
        }
        String url = resolve(site);
        if (TextUtils.isEmpty(url)) {
            SpiderDebug.log("proxy", "site-proxy on but no address site=%s", currentKey);
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("name", RULE_NAME);
        json.add("hosts", new Gson().toJsonTree(List.of("*")));
        json.add("urls", new Gson().toJsonTree(List.of(url)));
        Proxy rule = new Gson().fromJson(json, Proxy.class);
        OkHttp.selector().addAll(List.of(rule));
        currentUrl = url;
        SpiderDebug.log("proxy", "site-proxy on site=%s url=%s", currentKey, url);
    }

    /**
     * 首页开关：翻转当前站的代理状态。
     *
     * @return 翻转后的状态；若开启时无可用代理地址，返回 null 且不改状态。
     */
    public static synchronized Boolean toggle(Site site) {
        if (site == null) return null;
        boolean target = !isEnabled(site);
        if (target && TextUtils.isEmpty(resolve(site))) return null;
        setOverride(site, target);
        apply(site);
        return target;
    }

    private static boolean isProxyUrl(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String lower = value.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("socks");
    }

    /** 回落地址：现有全局规则（排除本站规则）中第一条可用 url。 */
    private static String globalFallback() {
        for (Proxy item : OkHttp.selector().getProxy()) {
            if (RULE_NAME.equals(item.getName())) continue;
            for (String url : item.getUrls()) if (isProxyUrl(url)) return url;
        }
        return null;
    }

    private static String overrideKey(Site site) {
        String config = "";
        try {
            config = VodConfig.get().getConfig().getUrl();
        } catch (Exception ignored) {
        }
        return Util.md5(config) + ":" + site.getKey();
    }

    private static Boolean getOverride(Site site) {
        try {
            String raw = Prefers.getString(PREF_KEY, "");
            if (TextUtils.isEmpty(raw)) return null;
            JsonObject json = new Gson().fromJson(raw, JsonObject.class);
            String key = overrideKey(site);
            return json != null && json.has(key) ? json.get(key).getAsBoolean() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void setOverride(Site site, boolean value) {
        try {
            String raw = Prefers.getString(PREF_KEY, "");
            JsonObject json = TextUtils.isEmpty(raw) ? new JsonObject() : new Gson().fromJson(raw, JsonObject.class);
            if (json == null) json = new JsonObject();
            json.addProperty(overrideKey(site), value);
            Prefers.put(PREF_KEY, json.toString());
        } catch (Exception ignored) {
        }
    }
}
