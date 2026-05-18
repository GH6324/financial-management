package com.family.finance.service.notify;

import com.family.finance.domain.family.Family;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.notify.FamilyNotifyConfig;
import com.family.finance.repository.FamilyNotifyConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v0.4.14 FR-63c · 阿里云短信渠道(短信为主)。
 *
 * <p>读 family_notify_config 取 aksk/签名/模板 · 走 dysmsapi RPC(SendSms · HMAC-SHA1 签名)。
 * 未配置 / 未启用 / 成员无手机号 → 直接 false,绝不抛错阻塞调度。
 *
 * <p>私密红线:
 * <ul>
 *   <li>accessKeySecret 全程不打印(连掩码都不打);</li>
 *   <li>手机号日志一律掩码 138****1234;</li>
 *   <li>本类不被 PromptBuilder 引用,aksk/手机号绝不进任何 LLM prompt。</li>
 * </ul>
 */
@Component
@Slf4j
public class SmsAliyunChannel implements NotificationChannel {

    public static final String CODE = "SMS";

    private static final String ENDPOINT = "https://dysmsapi.aliyuncs.com/";
    private static final DateTimeFormatter ISO8601 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final FamilyNotifyConfigMapper notifyConfigMapper;
    private final RestTemplate restTemplate;

    public SmsAliyunChannel(FamilyNotifyConfigMapper notifyConfigMapper,
                            RestTemplateBuilder builder) {
        this.notifyConfigMapper = notifyConfigMapper;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(8))
                .build();
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public boolean usable(Family family) {
        return notifyConfigMapper.findByFamily(family.getId())
                .map(FamilyNotifyConfig::smsUsable)
                .orElse(false);
    }

    /**
     * v0.4.14 §20.5.1 · 一键测试详细回执 · 暴露 BizId / Code / Message 给管理员看。
     * 与日常调度 send() 不同:**不读 sms_enabled 开关**(测试本来就是为了试通)·
     * 但仍校验 aksk / 签名 / 模板齐全,缺则返回友好错。
     */
    public SendResult sendForTest(Family family, String phone, ReminderMessage msg) {
        if (phone == null || phone.isBlank()) {
            return SendResult.bad("CONFIG_MISSING", "未提供目标手机号");
        }
        FamilyNotifyConfig cfg = notifyConfigMapper.findByFamily(family.getId()).orElse(null);
        if (cfg == null
                || isBlank(cfg.getSmsAccessKeyId()) || isBlank(cfg.getSmsAccessKeySecret())
                || isBlank(cfg.getSmsSignName()) || isBlank(cfg.getSmsTemplateCode())) {
            return SendResult.bad("CONFIG_INCOMPLETE", "请先填齐 AccessKey/Secret/签名/模板");
        }
        return callAliyun(cfg, phone, msg);
    }

    @Override
    public boolean send(Family family, Member member, ReminderMessage msg) {
        String phone = member.getPhone();
        if (phone == null || phone.isBlank()) {
            log.info("sms skip · member={} 无手机号", member.getId());
            return false;
        }
        FamilyNotifyConfig cfg = notifyConfigMapper.findByFamily(family.getId()).orElse(null);
        if (cfg == null || !cfg.smsUsable()) {
            log.info("sms skip · family={} 短信未配置/未启用", family.getId());
            return false;
        }
        SendResult r = callAliyun(cfg, phone, msg);
        if (r.ok()) {
            log.info("sms sent · family={} phone={} daysLeft={} bizId={}",
                    family.getId(), mask(phone), msg.daysLeft(), r.bizId());
        } else {
            log.warn("sms failed · family={} phone={} code={} detail={}",
                    family.getId(), mask(phone), r.code(), r.detail());
        }
        return r.ok();
    }

    private SendResult callAliyun(FamilyNotifyConfig cfg, String phone, ReminderMessage msg) {
        try {
            // 模板参数:运营商报备模板形如 ${brand}账本提醒,距记账截止还剩${days}天
            // PRD §20.4 · 4 变量 brand/period/days/progress · 工程预算好,模板只填空
            String templateParam = "{"
                    + "\"brand\":\""    + jsonEscape(msg.brand())    + "\","
                    + "\"period\":\""   + jsonEscape(msg.period())   + "\","
                    + "\"days\":\""     + Math.max(msg.daysLeft(), 0) + "\","
                    + "\"progress\":\"" + jsonEscape(msg.progress()) + "\""
                    + "}";

            TreeMap<String, String> p = new TreeMap<>();
            p.put("AccessKeyId", cfg.getSmsAccessKeyId());
            p.put("Action", "SendSms");
            p.put("Format", "JSON");
            p.put("PhoneNumbers", phone.trim());
            p.put("RegionId", "cn-hangzhou");
            p.put("SignName", cfg.getSmsSignName());
            p.put("SignatureMethod", "HMAC-SHA1");
            p.put("SignatureNonce", UUID.randomUUID().toString());
            p.put("SignatureVersion", "1.0");
            p.put("TemplateCode", cfg.getSmsTemplateCode());
            p.put("TemplateParam", templateParam);
            p.put("Timestamp", ISO8601.format(Instant.now()));
            p.put("Version", "2017-05-25");

            String signature = sign(p, cfg.getSmsAccessKeySecret());

            StringBuilder url = new StringBuilder(ENDPOINT)
                    .append("?Signature=").append(pctEncode(signature));
            for (Map.Entry<String, String> e : p.entrySet()) {
                url.append('&').append(pctEncode(e.getKey()))
                        .append('=').append(pctEncode(e.getValue()));
            }

            String resp = restTemplate.getForObject(url.toString(), String.class);
            String code = extract(resp, "Code");
            String bizId = extract(resp, "BizId");
            String message = extract(resp, "Message");
            boolean ok = "OK".equals(code);
            return new SendResult(ok, bizId, code == null ? "UNKNOWN" : code,
                    message == null ? brief(resp) : message);
        } catch (Exception ex) {
            // aksk 绝不入日志:只打异常类型 + message(阿里云 SDK 异常不含 secret)
            return new SendResult(false, null, "EXCEPTION", ex.toString());
        }
    }

    /** 阿里云返回 JSON 简单字段抽取(避免引 Jackson · 仅取 top-level 字符串字段) */
    private static String extract(String json, String key) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** 一键测试 / 调度发送的统一回执 · 不含手机号 · 不含 aksk */
    public record SendResult(boolean ok, String bizId, String code, String detail) {
        public static SendResult bad(String code, String detail) {
            return new SendResult(false, null, code, detail);
        }
    }

    /** 阿里云 POP RPC 签名:HMAC-SHA1(secret+"&", "GET&%2F&"+pct(canonicalizedQuery)) → Base64 */
    private String sign(TreeMap<String, String> params, String accessKeySecret) throws Exception {
        StringBuilder canon = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (canon.length() > 0) canon.append('&');
            canon.append(pctEncode(e.getKey())).append('=').append(pctEncode(e.getValue()));
        }
        String stringToSign = "GET&" + pctEncode("/") + "&" + pctEncode(canon.toString());
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8),
                "HmacSHA1"));
        byte[] raw = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(raw);
    }

    /** 阿里云专用 percent-encode:URLEncoder 基础上把 加号/星号/波浪 修正为 RFC3986 */
    private static String pctEncode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 手机号掩码:138****1234 · 短号原样长度兜底 */
    private static String mask(String phone) {
        String t = phone.trim();
        if (t.length() < 7) return "***";
        return t.substring(0, 3) + "****" + t.substring(t.length() - 4);
    }

    private static String brief(String resp) {
        if (resp == null) return "(null)";
        return resp.length() > 160 ? resp.substring(0, 160) : resp;
    }
}
