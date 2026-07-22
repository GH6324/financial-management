package com.family.finance.common;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.service.NavService;
import com.family.finance.service.NavState;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.Instant;

/**
 * 把 me + nav + buildVersion + appVersion 自动注入到所有 controller 的 model。
 * 未登录(/login 等)时 me/nav 为 null。
 * buildVersion 来自 Spring Boot build-info(maven build-info goal 生成 META-INF/build-info.properties)。
 * 用于模板中 vendor / css 静态资源 ?v=... 失效缓存。
 * appVersion 来自 application.yml app.version(发布语义版本,nav logo 下展示;发版随 tag 同步)。
 */
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final NavService navService;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;

    /** 发布语义版本(nav logo 下展示)· application.yml app.version · 发版随 tag 同步 */
    @Value("${app.version:dev}")
    private String appVersion;

    @ModelAttribute("appVersion")
    public String appVersion() {
        return appVersion;
    }

    @ModelAttribute("me")
    public MemberPrincipal me(@AuthenticationPrincipal MemberPrincipal me) {
        return me;
    }

    @ModelAttribute("nav")
    public NavState nav(@AuthenticationPrincipal MemberPrincipal me) {
        return me == null ? null : navService.load(me);
    }

    @ModelAttribute("buildVersion")
    public String buildVersion() {
        BuildProperties bp = buildPropertiesProvider.getIfAvailable();
        if (bp != null) {
            Instant t = bp.getTime();
            // 用构建毫秒时间戳的 base36 表达,短而单调
            long ms = t == null ? 0L : t.toEpochMilli();
            return bp.getVersion() + "-" + Long.toString(ms, 36);
        }
        // build-info 未生成时(开发模式 mvn spring-boot:run 等):用应用启动时间作 fallback
        return "dev-" + Long.toString(System.currentTimeMillis(), 36);
    }
}
