package com.family.finance.service.expense.imports;

import com.family.finance.domain.expense.ExpenseCategory;
import com.family.finance.domain.expense.ExpenseSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21 · 把渠道给的东西映射到<b>我们的类目树节点</b>,并把结果聚合成「一个类目一个数」。
 *
 * <h3>三条通道在这里汇合</h3>
 *
 * <p>支付宝(有分类列)、微信(只有商户名)、截图(AI 转写出的类目名)——
 * 映射规则不同,但<b>出口只有一个</b>:{@code Draft},里面是「树节点 id → 金额」。
 * 之后走同一个确认页、同一套批次归位。</p>
 *
 * <h3>中性交易为什么必须剔除</h3>
 *
 * <p>「转账红包」是把钱给了家人(可能还是给同一个家庭的另一个账户),
 * 「投资理财」是买了资产,「信用卡还款」「零钱提现」是资金搬家 ——
 * <b>都不是消费</b>。算进支出会让储蓄率、月均支出、紧急储备一起失真。</p>
 *
 * <p>但剔除必须<b>看得见</b>:清单进确认页,用户能改。因为「转给亲属」在有些家庭
 * 确实是赡养支出 —— 我们不替他判。</p>
 */
public final class BillCategoryResolver {

    private BillCategoryResolver() {}

    /**
     * 渠道分类 → 我们的一级类目名。
     *
     * <p>起步包的一级<b>刻意与支付宝账单同名</b>,所以绝大多数是恒等映射(不用出现在这张表里);
     * 这张表只收「渠道有、我们起步包里没有」的长尾,让它们落到最贴近的大类。</p>
     */
    static final Map<String, String> ALIPAY_ALIAS = Map.ofEntries(
            Map.entry("家居家装", "日用百货"),
            Map.entry("美容美发", "服饰装扮"),
            Map.entry("运动户外", "文化休闲"),
            Map.entry("酒店旅游", "文化休闲"),
            Map.entry("生活服务", "日用百货"),
            Map.entry("公共服务", "充值缴费"),
            Map.entry("母婴亲子", "日用百货"),
            Map.entry("宠物", "日用百货"),
            Map.entry("信用借还", "充值缴费"),
            Map.entry("商业服务", "日用百货")
    );

    /**
     * 中性交易:不算消费。
     *
     * <p>按渠道分类名 / 交易类型名匹配。列在这里的默认剔除,但会在确认页列出来供用户改回。</p>
     */
    static final List<String> NEUTRAL = List.of(
            "转账红包", "投资理财", "保险", "信用卡还款", "还款", "零钱充值", "零钱提现",
            "提现", "充值", "理财通", "余额宝", "转账", "红包", "亲友代付", "退款"
    );

    /** 微信没有分类列 → 内置一份常见商户关键字。家庭自建规则优先于它。 */
    static final Map<String, String> BUILTIN_MERCHANT = new LinkedHashMap<>();
    static {
        BUILTIN_MERCHANT.put("美团", "餐饮美食");
        BUILTIN_MERCHANT.put("饿了么", "餐饮美食");
        BUILTIN_MERCHANT.put("肯德基", "餐饮美食");
        BUILTIN_MERCHANT.put("麦当劳", "餐饮美食");
        BUILTIN_MERCHANT.put("星巴克", "餐饮美食");
        BUILTIN_MERCHANT.put("瑞幸", "餐饮美食");
        BUILTIN_MERCHANT.put("滴滴", "交通出行");
        BUILTIN_MERCHANT.put("高德", "交通出行");
        BUILTIN_MERCHANT.put("地铁", "交通出行");
        BUILTIN_MERCHANT.put("公交", "交通出行");
        BUILTIN_MERCHANT.put("12306", "交通出行");
        BUILTIN_MERCHANT.put("加油", "交通出行");
        BUILTIN_MERCHANT.put("停车", "交通出行");
        BUILTIN_MERCHANT.put("京东", "日用百货");
        BUILTIN_MERCHANT.put("淘宝", "日用百货");
        BUILTIN_MERCHANT.put("天猫", "日用百货");
        BUILTIN_MERCHANT.put("拼多多", "日用百货");
        BUILTIN_MERCHANT.put("超市", "日用百货");
        BUILTIN_MERCHANT.put("便利", "日用百货");
        BUILTIN_MERCHANT.put("药", "医疗健康");
        BUILTIN_MERCHANT.put("医院", "医疗健康");
        BUILTIN_MERCHANT.put("电影", "文化休闲");
        BUILTIN_MERCHANT.put("健身", "文化休闲");
        BUILTIN_MERCHANT.put("酒店", "文化休闲");
        BUILTIN_MERCHANT.put("电费", "充值缴费");
        BUILTIN_MERCHANT.put("话费", "充值缴费");
        BUILTIN_MERCHANT.put("水费", "充值缴费");
        BUILTIN_MERCHANT.put("燃气", "充值缴费");
        BUILTIN_MERCHANT.put("物业", "住房物业");
        BUILTIN_MERCHANT.put("房租", "住房物业");
    }

    /** 确认页上的一行:渠道给的原名 → 落到哪个树节点,以及金额与笔数 */
    public record Line(String channelLabel, long categoryId, String categoryName,
                       BigDecimal amount, int count, String how) {}

    /** 被剔除的中性交易(确认页要列出来,用户可以改回) */
    public record Neutral(String label, BigDecimal amount, int count) {}

    public record Draft(ExpenseSource channel, List<Line> lines, List<Neutral> neutrals,
                        Map<Long, BigDecimal> byCategory, BigDecimal total, int rowCount,
                        int unmapped, CsvBillParser.Parsed parsed) {}

    /**
     * 聚合。
     *
     * @param fillable 当前深度下可填的类目(简单深度=一级;复杂深度=细类/未细分的大类)
     * @param rules    家庭自建的商户关键字规则(优先于内置表)
     * @param otherId  「其他」类目 id —— 映射不上的最终去处
     */
    public static Draft aggregate(ExpenseSource channel, CsvBillParser.Parsed parsed,
                                  List<ExpenseCategory> allNodes,
                                  Map<String, Long> rules, long otherId) {
        /* 名字 → 节点 id,匹配范围是<b>整棵树</b>(大类 + 细类),不是「当前深度可填的那一层」。
         *
         * 这一点开发时搞错过,而且错得很隐蔽:原来只在 fillable(复杂深度下=细类)里找,
         * 于是渠道给的「餐饮美食」(大类名)在细类表里找不到 → 全落「其他」;
         * 偏偏「家居家装」因为复杂版里正好有个同名细类而命中了,看起来像是「大部分认不出来」。
         *
         * 正确的做法是让它落在【大类】上 —— 那就是 rollup 里的「未细分」,
         * 一个一等形态:钱记在大类上,以后想细分再细分,合计一分不差。
         * 细类优先(更精确),大类兜底。 */
        Map<String, Long> byName = new LinkedHashMap<>();
        for (ExpenseCategory c : allNodes) if (!c.isTopLevel()) byName.putIfAbsent(c.getName(), c.getId());
        for (ExpenseCategory c : allNodes) if (c.isTopLevel()) byName.putIfAbsent(c.getName(), c.getId());
        Map<Long, String> nameOf = new LinkedHashMap<>();
        for (ExpenseCategory c : allNodes) nameOf.put(c.getId(), c.getName());
        nameOf.putIfAbsent(otherId, "其他");

        Map<String, BigDecimal> neutralAmt = new LinkedHashMap<>();
        Map<String, Integer> neutralCnt = new LinkedHashMap<>();
        // key = 渠道原名 + "→" + 目标类目
        Map<String, BigDecimal> lineAmt = new LinkedHashMap<>();
        Map<String, Integer> lineCnt = new LinkedHashMap<>();
        Map<String, Long> lineTarget = new LinkedHashMap<>();
        Map<String, String> lineHow = new LinkedHashMap<>();
        Map<Long, BigDecimal> byCategory = new LinkedHashMap<>();
        int unmapped = 0;

        for (BillRow r : parsed.rows()) {
            if (!r.isExpense()) continue;                 // 收入 / 不计收支都不是消费

            String label = pickLabel(r);
            if (isNeutral(r)) {
                neutralAmt.merge(label, r.amount(), BigDecimal::add);
                neutralCnt.merge(label, 1, Integer::sum);
                continue;
            }

            Long target = null;
            String how;
            if (r.channelCategory() != null && !r.channelCategory().isBlank()
                    && byName.containsKey(r.channelCategory())) {
                target = byName.get(r.channelCategory());
                how = "同名直挂";
            } else if (r.channelCategory() != null && ALIPAY_ALIAS.containsKey(r.channelCategory())
                    && byName.containsKey(ALIPAY_ALIAS.get(r.channelCategory()))) {
                target = byName.get(ALIPAY_ALIAS.get(r.channelCategory()));
                how = "映射";
            } else {
                // 微信这条路:靠商户名/商品说明的关键字
                String hay = (nz(r.counterparty()) + " " + nz(r.goods())).toLowerCase();
                Long byRule = matchRule(hay, rules);
                if (byRule != null) { target = byRule; how = "你的关键字规则"; }
                else {
                    String cat = matchBuiltin(hay);
                    if (cat != null && byName.containsKey(cat)) {
                        target = byName.get(cat); how = "商户关键字";
                    } else { target = otherId; how = "没认出来 · 落「其他」"; unmapped++; }
                }
            }

            String key = label + "→" + target;
            lineAmt.merge(key, r.amount(), BigDecimal::add);
            lineCnt.merge(key, 1, Integer::sum);
            lineTarget.put(key, target);
            lineHow.put(key, how);
            byCategory.merge(target, r.amount(), BigDecimal::add);
        }

        List<Line> lines = new ArrayList<>();
        for (var e : lineAmt.entrySet()) {
            long t = lineTarget.get(e.getKey());
            lines.add(new Line(e.getKey().substring(0, e.getKey().lastIndexOf("→")),
                    t, nameOf.getOrDefault(t, "其他"), e.getValue(),
                    lineCnt.get(e.getKey()), lineHow.get(e.getKey())));
        }
        lines.sort((a, b) -> b.amount().compareTo(a.amount()));

        List<Neutral> neutrals = new ArrayList<>();
        for (var e : neutralAmt.entrySet()) {
            neutrals.add(new Neutral(e.getKey(), e.getValue(), neutralCnt.get(e.getKey())));
        }
        neutrals.sort((a, b) -> b.amount().compareTo(a.amount()));

        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : byCategory.values()) total = total.add(v);

        int rows = 0;
        for (Line l : lines) rows += l.count();
        return new Draft(channel, lines, neutrals, byCategory, total, rows, unmapped, parsed);
    }

    // ──────────────────────── 判据 ────────────────────────

    static String pickLabel(BillRow r) {
        if (r.channelCategory() != null && !r.channelCategory().isBlank()) return r.channelCategory();
        if (r.counterparty() != null && !r.counterparty().isBlank()) return r.counterparty();
        return "(没有名字)";
    }

    static boolean isNeutral(BillRow r) {
        String cat = nz(r.channelCategory());
        String party = nz(r.counterparty());
        for (String n : NEUTRAL) {
            if (cat.contains(n)) return true;
            // 商户名里带「还款/提现」这类词也算 —— 微信没有分类列,只能从这儿看
            if (r.channelCategory() == null && party.contains(n)) return true;
        }
        return r.isRefund();
    }

    /** 家庭规则:长关键字优先(mapper 已按长度倒序返回),避免「京东」抢在「京东健康」之前 */
    static Long matchRule(String hay, Map<String, Long> rules) {
        for (var e : rules.entrySet()) {
            if (hay.contains(e.getKey().toLowerCase())) return e.getValue();
        }
        return null;
    }

    static String matchBuiltin(String hay) {
        for (var e : BUILTIN_MERCHANT.entrySet()) {
            if (hay.contains(e.getKey().toLowerCase())) return e.getValue();
        }
        return null;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
