package com.family.finance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 应用级配置属性。前缀 `app.*` 见 application.yml。
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("/tmp/finance-uploads") String uploadRoot,
        @DefaultValue("dev-only-key-change-in-prod") String rememberMeKey,
        @DefaultValue("2592000") int rememberMeValiditySeconds,
        @DefaultValue("https://api.frankfurter.dev") String fxApiBase,
        @DefaultValue("3000") long fxFetchTimeoutMs,
        // v0.15 · 富途 OpenD 傻瓜向导:应用自管子进程的家目录(下载/解压/配置/日志都在此)
        @DefaultValue("/tmp/finance-futu-opend") String brokerOpendHome
) {}
