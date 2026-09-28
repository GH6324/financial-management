package com.family.finance.web.analysis;

import org.springframework.web.util.UriComponentsBuilder;

/**
 * v1.27 · 分析范围 / 模板相关的站内链接。
 *
 * <p>临时切换记在网址上(FR-823 / FR-845):刷新保持、发给家人的链接保持,不改家庭默认。
 * 定制页「从哪来回哪去」(FR-882)的回跳地址只收站内相对路径 —— 不接受任意跳转,免得成了开放重定向。</p>
 */
public final class AnalysisUrls {

    private AnalysisUrls() {}

    /** path + ?scope= &tpl=(空的不带)+ #anchor */
    public static String with(String path, String scope, String tpl, String anchor) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(path);
        if (scope != null && !scope.isBlank()) b.queryParam("scope", scope);
        if (tpl != null && !tpl.isBlank()) b.queryParam("tpl", tpl);
        String url = b.build().encode().toUriString();
        return anchor == null || anchor.isBlank() ? url : url + "#" + anchor;
    }

    /**
     * 回跳地址白名单:只认本站的几页(体检 / 报表 / 超级 Agent / 分析设置),且必须是相对路径。
     * 其余一律回分析设置。
     */
    public static String safeBack(String back) {
        if (back == null || back.isBlank()) return null;
        String b = back.trim();
        if (!b.startsWith("/") || b.startsWith("//") || b.contains("\\") || b.contains("\r") || b.contains("\n")) {
            return null;
        }
        for (String ok : new String[]{"/checkup", "/reports", "/ask", "/admin/analysis"}) {
            if (b.equals(ok) || b.startsWith(ok + "?") || b.startsWith(ok + "#") || b.startsWith(ok + "/")) return b;
        }
        return null;
    }

    /** 「基于它定制」的入口(FR-882 · 带上回跳地址) */
    public static String customize(String fromKey, String back) {
        return UriComponentsBuilder.fromPath("/admin/analysis/template/new")
                .queryParam("from", fromKey)
                .queryParam("back", back)
                .build().encode().toUriString();
    }

    /** 回跳时换上新模板(当场用新模板重跑 · FR-882):去掉原来的 tpl,加上新的 */
    public static String withTemplate(String back, String tplKey) {
        String b = safeBack(back);
        if (b == null) return null;
        String anchor = null;
        int hash = b.indexOf('#');
        if (hash >= 0) { anchor = b.substring(hash + 1); b = b.substring(0, hash); }
        UriComponentsBuilder u = UriComponentsBuilder.fromUriString(b);
        u.replaceQueryParam("tpl", tplKey);
        String url = u.build().toUriString();
        return anchor == null ? url : url + "#" + anchor;
    }
}
