package com.family.finance.service.checkup.llm;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LLM 综合诊断输出校验 · v0.2 FR-40c · 2026-05-10 修订(决策 20)
 *
 * <p><b>新方向校验策略</b>(从"锁原文每个数字"放宽为软校验):
 * <ol>
 *   <li>长度限制 150-600 中文字符(综合诊断需要长度,但避免 LLM 偷懒或废话连篇)</li>
 *   <li>禁词扫描:
 *     <ul>
 *       <li>具体产品名:余额宝 / 510300 / 茅台 / 招行某理财 / 平安XXX / 工商XXX...</li>
 *       <li>古典中式词:师傅 / 打理 / 挪 / 留个 / 家底 / 搁着</li>
 *       <li>担保性话术:保证 / 稳赚 / 一定能 / 必然 / 零风险 / 包赚</li>
 *       <li>过度客套:您 出现 > 2 次</li>
 *     </ul>
 *   </li>
 *   <li>真名泄露扫描:LLM 输出不能包含原始成员真名(已在 prompt 阶段映射成代号,但作防御深度)</li>
 *   <li>不再"锁原文每个数字必须保留"——综合诊断模式下 LLM 必须能引入推理性新数字</li>
 * </ol>
 *
 * <p>任意一项失败 → reject,fallback 到「AI 暂时不可用」占位 + audit_log LLM_REJECTED。
 */
public final class OutputValidator {

    /** 长度下限:综合诊断需要叙事完整,150 字以下信号太弱 */
    private static final int MIN_LEN = 150;
    /** 长度上限:600 字以上是废话连篇,产品上需要克制 */
    private static final int MAX_LEN = 700; // 留 100 字缓冲(LLM 偶尔会超 600)

    /** 担保性话术(最不能容忍 — 涉及金融建议合规) */
    private static final List<String> GUARANTEE_PHRASES = List.of(
            "保证", "稳赚", "一定能", "必然", "零风险", "包赚", "无风险套利", "稳定盈利",
            "肯定盈利", "绝对收益", "保底", "保收益"
    );

    /** 古典中式词(品牌 chrome 用,AI 综合诊断不该出现) */
    private static final List<String> ARCHAIC_PHRASES = List.of(
            "师傅", "打理", "挪个", "留个", "家底", "搁着", "压着", "一笔", "几笔"
    );

    /** 具体产品名 / 股票代码 — 这是合规底线 */
    private static final Pattern PRODUCT_NAME_PATTERN = Pattern.compile(
            "余额宝|余利宝|微信零钱通|招商银行某|工商银行某|平安某|建设银行某|农业银行某|"
            + "茅台|宁德时代|腾讯|阿里|美团|京东|"
            + "510\\d{3}|512\\d{3}|513\\d{3}|159\\d{3}|" // ETF 代码常见前缀
            + "\\b\\d{6}\\b" // 任何 6 位数字 (A 股代码)
    );

    private OutputValidator() {}

    /**
     * 校验综合诊断输出(无账户白名单 · 老 caller 兼容)。
     */
    public static Result check(String polished, Set<String> realNames) {
        return check(polished, realNames, java.util.Set.of());
    }

    /**
     * 校验综合诊断输出。
     *
     * @param polished           LLM 输出文本
     * @param realNames          原始真名列表(防御深度:扫描 LLM 输出是否泄露真名)
     * @param accountWhitelist   用户已有账户名集合(子串匹配)· 调仓建议场景 · LLM 引用用户自己账户合法
     *                           (e.g. 用户有「支付宝-余额宝」账户 · LLM 说"从余额宝调出" 不算产品推荐)
     */
    public static Result check(String polished, Set<String> realNames, Set<String> accountWhitelist) {
        if (polished == null || polished.isBlank()) {
            return Result.reject("空字符串");
        }
        String trimmed = polished.trim();
        int len = trimmed.length();
        if (len < MIN_LEN) {
            return Result.reject("文本过短 len=" + len + "(< " + MIN_LEN + ")");
        }
        if (len > MAX_LEN) {
            return Result.reject("文本过长 len=" + len + "(> " + MAX_LEN + ")");
        }

        // 1. 担保性话术(最严重)
        for (String w : GUARANTEE_PHRASES) {
            if (trimmed.contains(w)) {
                return Result.reject("含担保性话术: \"" + w + "\"");
            }
        }

        // 2. 古典中式词
        for (String w : ARCHAIC_PHRASES) {
            if (trimmed.contains(w)) {
                return Result.reject("含古典中式词: \"" + w + "\"");
            }
        }

        // 3. 具体产品名 / 股票代码
        //    白名单:如果匹配命中的产品名是「用户已有账户名」的子串,放行
        //    (调仓建议场景 · 用户对自己账户有完全知情权 · LLM 引用自己账户不算产品推荐)
        var matcher = PRODUCT_NAME_PATTERN.matcher(trimmed);
        while (matcher.find()) {
            String hit = matcher.group();
            boolean isUserAccount = accountWhitelist != null && accountWhitelist.stream()
                .anyMatch(name -> name != null && name.contains(hit));
            if (!isUserAccount) {
                return Result.reject("含具体产品名/代码: \"" + hit + "\"");
            }
        }

        // 4. 真名泄露扫描(防御深度;理论上 LLM 看不到真名就不会写出来)
        if (realNames != null && !realNames.isEmpty()) {
            for (String name : realNames) {
                if (name != null && name.length() >= 2 && trimmed.contains(name)) {
                    return Result.reject("真名泄露: \"" + name + "\"");
                }
            }
        }

        // 5. 过度客套
        long youCount = trimmed.chars().filter(c -> c == '您').count();
        if (youCount > 2) {
            return Result.reject("过度客套(您 出现 " + youCount + " 次)");
        }

        // 6. 至少包含一个金融术语(避免 LLM 跑题成"心灵鸡汤")
        boolean hasFinanceTerm = trimmed.contains("年化") || trimmed.contains("配置")
                || trimmed.contains("资产") || trimmed.contains("流动")
                || trimmed.contains("风险") || trimmed.contains("收益")
                || trimmed.contains("基准") || trimmed.contains("应急")
                || trimmed.contains("负债") || trimmed.contains("偿还");
        if (!hasFinanceTerm) {
            return Result.reject("无金融术语,可能跑题");
        }

        return Result.accept();
    }

    public record Result(boolean accepted, String reason) {
        public static Result accept() { return new Result(true, null); }
        public static Result reject(String reason) { return new Result(false, reason); }
    }
}
