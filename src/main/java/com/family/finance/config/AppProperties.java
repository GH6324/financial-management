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
        @DefaultValue("/tmp/finance-futu-opend") String brokerOpendHome,
        // v1.17 · 与可选网关容器共享的控制目录(只有 app 与网关容器挂载它 —— 文件权限就是鉴权)。
        //         这个目录存在 = 用户启用过网关;不存在 = 走本机托管或还没启用。
        @DefaultValue("/ctl") String futuCtlDir,
        // v1.17 · 网关容器在 compose 内网的服务名(逃生阀:用户想指到别处就在管理页改 host)
        @DefaultValue("opend") String futuGatewayHost
) {}
