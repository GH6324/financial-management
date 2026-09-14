package com.family.finance.service.expense.imports;

import com.family.finance.domain.expense.ExpenseCategory;
import com.family.finance.domain.expense.ExpenseSource;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /**
     * 归类依据 —— 确认页要把它标出来(FR-564)。
     *
     * <p>为什么必须显示:用户没时间逐行核对 428 笔。标出依据之后,
     * 「渠道分类」和「你的规则」那两类基本可以跳过,只需要盯 AI 和兜底 ——
     * 428 笔的核对量压缩到十几笔。不标依据,这一页就只是一张长表。</p>
     */
    public enum How {
        CHANNEL("渠道分类"),   // 渠道自己那一列,和你的树同名 → 零成本、最可信
        ALIAS("渠道分类"),     // 同上,只是我们做了一层别名映射
        RULE("你的规则"),      // 你上次改过并勾了「记住」
        BUILTIN("商户关键字"), // 内置的常见商户表
        AI("AI 猜的"),         // 前面都没命中,送商户名给模型
        FALLBACK("兜底");      // 全都没命中 → 「其他」,等你手改

        public final String label;
        How(String l) { this.label = l; }
        /** 需要用户重点核对的那两类 */
        public boolean needsReview() { return this == AI || this == FALLBACK; }
    }

    /** 这一笔属于哪个桶(FR-560)。桶必须显式,且每桶的笔数金额都要显示出来。 */
    public enum Bucket {
        /** 消费支出 —— 要导入的主体 */
        SPEND,
        /** 性质另算:还贷 / 转账给亲属。进储蓄率与负债口径,不进「钱花在哪」 */
        NATURE,
        /** 收入 —— 默认不导(用户可勾选) */
        INCOME,
        /** 剔除:不计收支 / 退款 / 交易关闭。**这些钱实际没花出去** */
        DROPPED,
        /** 已存在:按交易号去重跳过 */
        SKIPPED
    }

    /**
     * 确认页上的一行 = 账单里的一笔。
     *
     * <p>第 1 稿这里是「渠道分类 → 家类目 · 金额 · 笔数」的<b>聚合行</b>。
     * 改成逐笔之后行数多了,但换来两件做不到的事:单笔可改分类、报表可下钻到单笔。</p>
     *
     * @param categoryId 归到哪个消费分类;{@link Bucket#NATURE} 时为 null
     * @param natureCode NATURE 桶专用的性质码(loan_payment / to_relatives);其余为 null
     */
    public record Line(int idx, LocalDate occurredAt, String merchant, BigDecimal amount,
                       Long categoryId, String categoryName, How how, Bucket bucket,
                       String natureCode, String dropReason, String txNo,
                       String payMethod, Long accountId, BillAccountResolver.How accountHow) {

        /**
         * 这一行值得用户重点核对吗。
         *
         * <p><b>分类没把握</b>或<b>账户没把握</b>都算 —— 账户猜错比分类猜错严重:
         * 分类错了只是构成图不准,账户错了会让那个账户的余额和收益率一起错。</p>
         */
        public boolean needsReview() {
            if (bucket != Bucket.SPEND) return false;
            return (how != null && how.needsReview())
                || (accountHow != null && accountHow.needsReview());
        }
    }

    /**
     * 解析产物。
     *
     * @param noTxNo 没有交易号的笔数 —— 它们<b>无法参与重复导入去重</b>,确认页要说出来
     */
    public record Draft(ExpenseSource channel, List<Line> lines, int total,
                        CsvBillParser.Parsed parsed, int noTxNo) {

        public List<Line> bucket(Bucket b) {
            return lines.stream().filter(l -> l.bucket() == b).toList();
        }

        public int count(Bucket b) { return bucket(b).size(); }

        public BigDecimal sum(Bucket b) {
            return bucket(b).stream().map(Line::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * 逐笔归类 + 分桶。
     *
     * @param allNodes 整棵树(大类 + 细类)· <b>不是「当前深度可填的那一层」</b>
     * @param rules    家庭自建的商户关键字规则(优先于内置表)
     * @param otherId  「其他」类目 id —— 映射不上的最终去处
     * @param seenTxNo 已经导过的交易号 → 落 SKIPPED 桶
     */
    public static Draft classify(ExpenseSource channel, CsvBillParser.Parsed parsed,
                                 List<ExpenseCategory> allNodes,
                                 Map<String, Long> rules, long otherId,
                                 java.util.Set<String> seenTxNo,
                                 List<com.family.finance.domain.account.Account> accounts,
                                 Map<String, Long> acctRules, Long defaultAccountId) {
        /* 名字 → 节点 id,匹配范围是【整棵树】(大类 + 细类)。
         *
         * 这一点第 1 稿搞错过,而且错得很隐蔽:原来只在「当前深度可填的那一层」里找,
         * 于是渠道给的「餐饮美食」(大类名)在细类表里找不到 → 全落「其他」;
         * 偏偏「家居家装」因为正好有个同名细类而命中了,看起来像是「大部分认不出来」。
         *
         * 细类优先(更精确),大类兜底 —— 落在大类上完全正常,不是降级。 */
        Map<String, Long> byName = new LinkedHashMap<>();
        for (ExpenseCategory c : allNodes) if (!c.isTopLevel()) byName.putIfAbsent(c.getName(), c.getId());
        for (ExpenseCategory c : allNodes) if (c.isTopLevel()) byName.putIfAbsent(c.getName(), c.getId());
        Map<Long, String> nameOf = new LinkedHashMap<>();
        for (ExpenseCategory c : allNodes) nameOf.put(c.getId(), c.getName());
        nameOf.putIfAbsent(otherId, "其他");

        List<Line> lines = new ArrayList<>();
        int idx = 0, noTx = 0;
        for (BillRow r : parsed.rows()) {
            idx++;
            String merchant = r.merchantText();
            if (merchant.isBlank()) merchant = "(没有名字)";
            LocalDate at = r.occurredAt();
            String tx = r.txNo();
            if (tx == null) noTx++;
            /* 【每一笔各自推荐账户】—— 一份账单里「收/付款方式」本来就是变化的。
             * 放在分桶之前算:剔除/跳过的行也带着账户信息,用户在确认页上能看出
             * 「这批里有几笔是花呗的」,而不是等确认完才发现。 */
            BillAccountResolver.Hit acct =
                    BillAccountResolver.resolve(r.payMethod(), accounts, acctRules, defaultAccountId);

            /* ① 已经导过 —— 最先判,免得同一笔又走一遍归类然后被用户看见两次 */
            if (tx != null && seenTxNo != null && seenTxNo.contains(tx)) {
                lines.add(new Line(idx, at, merchant, r.amount(), null, null, null,
                        Bucket.SKIPPED, null, "上次已导入", tx, r.payMethod(), acct.accountId(), acct.how()));
                continue;
            }
            /* ② 金额 ≤ 0 —— 冲正行、被重开的退款、或者渠道自己写的占位行。
             *
             *    【必须在这里挡】:cash_flow 上有 CHECK(amount > 0),落到 insert 才炸的话
             *    整批事务回滚,而用户看到的只是一句「落库失败」—— 真实账单上撞到过。
             *    分桶挡掉之后它会出现在「已剔除」里并写明原因,用户能看懂发生了什么。 */
            if (r.amount() == null || r.amount().signum() <= 0) {
                lines.add(new Line(idx, at, merchant,
                        r.amount() == null ? java.math.BigDecimal.ZERO : r.amount(),
                        null, null, null, Bucket.DROPPED, null, "金额是 0 或负数(冲正行)", tx,
                        r.payMethod(), acct.accountId(), acct.how()));
                continue;
            }
            /* ③ 退款 / 交易关闭 —— 这笔钱实际没花出去 */
            if (r.isRefund()) {
                lines.add(new Line(idx, at, merchant, r.amount(), null, null, null,
                        Bucket.DROPPED, null, "退款 / 交易关闭", tx, r.payMethod(), acct.accountId(), acct.how()));
                continue;
            }
            /* ④ 渠道自己说「不计收支」—— 这是<b>渠道的判断</b>,最可信:
             *    余额宝转入转出、理财买入赎回,钱还在你自己名下。
             *    当成支出就等于把同一笔钱花两遍(账户余额那边已经反映了这次移动)。 */
            if (r.isNeutral()) {
                lines.add(new Line(idx, at, merchant, r.amount(), null, null, null,
                        Bucket.DROPPED, null, "不计收支(划转)", tx, r.payMethod(), acct.accountId(), acct.how()));
                continue;
            }
            /* ⑤ 收入 —— 默认不导。支出侧的分类体系套不到收入上(收入类目绑账户类型)。 */
            if (r.isIncome()) {
                lines.add(new Line(idx, at, merchant, r.amount(), null, null, null,
                        Bucket.INCOME, null, null, tx, r.payMethod(), acct.accountId(), acct.how()));
                continue;
            }
            if (!r.isExpense()) {
                lines.add(new Line(idx, at, merchant, r.amount(), null, null, null,
                        Bucket.DROPPED, null, "收支方向认不出来", tx, r.payMethod(), acct.accountId(), acct.how()));
                continue;
            }
            /* ⑤ 还贷 —— 【必须排在关键字划转判据之前】。
             *
             *    开发时踩过:NEUTRAL 关键字表里有「还款」,于是渠道标成【支出】的「花呗还款」
             *    先被当成划转剔掉了 —— NATURE 桶永远是空的,页面上那个格子形同虚设。
             *    渠道说是支出就是支出:钱确实从这个账户流出去了,它只是不属于「消费」。
             *    落成 loan_payment 与手工记一笔还贷完全等价。 */
            String nature = natureOf(r);
            if (nature != null) {
                lines.add(new Line(idx, at, merchant, r.amount(), null, null, null,
                        Bucket.NATURE, nature, null, tx, r.payMethod(), acct.accountId(), acct.how()));
                continue;
            }
            /* ⑦ 关键字兜底的划转判据(转账红包 / 提现充值 / 理财)。
             *    放在还贷之后 —— 它是<b>猜</b>,而上面几条是渠道明说的。 */
            if (isNeutral(r)) {
                lines.add(new Line(idx, at, merchant, r.amount(), null, null, null,
                        Bucket.DROPPED, null, "看着像划转,不是消费", tx, r.payMethod(), acct.accountId(), acct.how()));
                continue;
            }

            /* ⑧ 消费 —— 三层兜底归类(FR-562) */
            Long target;
            How how;
            String cc = r.channelCategory();
            if (cc != null && !cc.isBlank() && byName.containsKey(cc)) {
                target = byName.get(cc); how = How.CHANNEL;
            } else if (cc != null && ALIPAY_ALIAS.containsKey(cc)
                    && byName.containsKey(ALIPAY_ALIAS.get(cc))) {
                target = byName.get(ALIPAY_ALIAS.get(cc)); how = How.ALIAS;
            } else {
                String hay = merchant.toLowerCase();
                Long byRule = matchRule(hay, rules);
                if (byRule != null) { target = byRule; how = How.RULE; }
                else {
                    String cat = matchBuiltin(hay);
                    if (cat != null && byName.containsKey(cat)) {
                        target = byName.get(cat); how = How.BUILTIN;
                    } else { target = otherId; how = How.FALLBACK; }
                }
            }
            lines.add(new Line(idx, at, merchant, r.amount(), target,
                    nameOf.getOrDefault(target, "其他"), how, Bucket.SPEND, null, null, tx, r.payMethod(), acct.accountId(), acct.how()));
        }
        return new Draft(channel, lines, lines.size(), parsed, noTx);
    }

    /**
     * 是不是「还贷 / 转账给亲属」这类<b>不是消费的支出</b>。
     *
     * <p>判据保守 —— 宁可当成普通消费让用户改回来,也不要把一笔正常消费误判成还贷:
     * 前者只是分类不准,后者会直接影响储蓄率。</p>
     */
    static String natureOf(BillRow r) {
        String hay = (nz(r.channelCategory()) + " " + nz(r.counterparty()) + " " + nz(r.goods()));
        if (hay.contains("还款") || hay.contains("房贷") || hay.contains("车贷")
                || hay.contains("贷款") || hay.contains("花呗") || hay.contains("借呗")) {
            return "loan_payment";
        }
        return null;
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
