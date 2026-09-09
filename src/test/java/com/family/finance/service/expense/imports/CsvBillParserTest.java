package com.family.finance.service.expense.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.21 · 账单 CSV 解析。
 *
 * <p><b>样本是按调研规格手工构造的</b>(列名/编码/说明头行数来自 PRD §1.2 的渠道档案),
 * <b>不是真实账单</b> —— 真实样本要维护者自己在 beta 上导一份实测(TDD 待实测 1/2),
 * 那一步不入库、不进 commit。这里守的是「格式一旦如调研所述,解析就对」以及
 * 「格式不如所述时,<b>显式报错而不是静默解出垃圾</b>」。</p>
 */
class CsvBillParserTest {

    private static final Charset GBK = Charset.forName("GBK");

    /** 支付宝:GBK · 前 24 行说明 · 12 列 · 有「交易分类」 */
    private static byte[] alipay(int preambleLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("支付宝交易明细\n");
        for (int i = 1; i < preambleLines; i++) sb.append("说明第").append(i).append("行\n");
        sb.append("交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注\n");
        // 金额字段带千分逗号时【必须带引号】,否则 CSV 本身就是坏的(社区工具能正常解析,
        // 说明真实文件是带引号的)。这一行同时验「商品说明里的逗号」与「金额里的逗号」两处。
        sb.append("2026-09-01 12:00:00,餐饮美食,某餐厅,x@x.com,\"午餐,两人\",支出,\"￥1,280.00\",余额宝,交易成功,T1,M1,\n");
        sb.append("2026-09-02 09:00:00,交通出行,滴滴,y@y.com,快车,支出,32.50,花呗,交易成功,T2,M2,\n");
        sb.append("2026-09-03 09:00:00,餐饮美食,某外卖,z@z.com,晚饭,支出,45.00,余额,交易成功,T3,M3,\n");
        sb.append("2026-09-04 09:00:00,转账红包,亲属,a@a.com,转账,支出,2000.00,余额,交易成功,T4,M4,\n");
        sb.append("2026-09-05 09:00:00,餐饮美食,某餐厅,b@b.com,退款,收入,15.00,余额,退款成功,T5,M5,\n");
        sb.append("2026-09-06 09:00:00,投资理财,基金,c@c.com,申购,不计收支,500.00,余额,交易成功,T6,M6,\n");
        // 下面两行覆盖【别名】路径:渠道有这两个分类,而我们的起步包里没有同名大类
        sb.append("2026-09-07 09:00:00,家居家装,宜家,d@d.com,置物架,支出,399.00,余额,交易成功,T7,M7,\n");
        sb.append("2026-09-08 09:00:00,酒店旅游,某酒店,e@e.com,住宿,支出,660.00,余额,交易成功,T8,M8,\n");
        sb.append("------------------------------------\n");
        return sb.toString().getBytes(GBK);
    }

    /** 微信:UTF-8 · 前 16 行说明 · 11 列 · 【没有】分类列 */
    private static byte[] wechat() {
        StringBuilder sb = new StringBuilder();
        sb.append("微信支付账单明细\n");
        for (int i = 1; i < 16; i++) sb.append("说明第").append(i).append("行\n");
        sb.append("交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n");
        sb.append("2026-09-01 12:00:00,商户消费,美团外卖,订单,支出,¥68.00,零钱,支付成功,W1,S1,\n");
        sb.append("2026-09-02 12:00:00,商户消费,滴滴出行,行程,支出,¥25.00,招商银行(1234),支付成功,W2,S2,\n");
        sb.append("2026-09-03 12:00:00,转账,某人,转账,支出,¥500.00,零钱,支付成功,W3,S3,\n");
        sb.append("2026-09-04 12:00:00,信用卡还款,招商银行,还款,支出,¥3000.00,零钱,支付成功,W4,S4,\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ─────────────── 编码探测 ───────────────

    @Test
    @DisplayName("编码靠探测,不靠渠道写死 —— 支付宝 GBK / 微信 UTF-8 都要认出来")
    void sniffsBothEncodings() {
        assertThat(EncodingSniffer.sniff(alipay(24)).name()).isEqualTo("GBK");
        assertThat(EncodingSniffer.sniff(wechat())).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("GBK 文本按 UTF-8 解不会抛异常,只会出 U+FFFD —— 所以判据是数替换字符")
    void gbkDecodedAsUtf8YieldsReplacementChars() {
        var wrong = EncodingSniffer.score(alipay(24), StandardCharsets.UTF_8);
        var right = EncodingSniffer.score(alipay(24), GBK);
        assertThat(wrong.replacementChars())
                .as("用错编码不报错,只是出问号 —— 这就是为什么不能用 try-catch 判断")
                .isGreaterThan(0);
        assertThat(right.anchorHits()).isGreaterThan(wrong.anchorHits());
    }

    // ─────────────── 表头定位 ───────────────

    @Test
    @DisplayName("【关键】表头按特征定位,说明头行数变了也照样对")
    void headerFoundRegardlessOfPreambleLength() {
        for (int pre : new int[]{5, 16, 24, 30}) {
            var p = CsvBillParser.parse(alipay(pre));
            assertThat(p.skippedLines())
                    .as("说明头 " + pre + " 行时应定位到第 " + pre + " 行(0-based)")
                    .isEqualTo(pre);
            assertThat(p.rows()).hasSize(8);
        }
    }

    @Test
    @DisplayName("找不到表头 → 显式报错,并指出「证明材料」这个最常见的错")
    void missingHeaderFailsLoudly() {
        byte[] junk = "这是一份盖章的证明材料\n没有任何表头\n".getBytes(GBK);
        assertThatThrownBy(() -> CsvBillParser.parse(junk))
                .isInstanceOf(CsvBillParser.ParseException.class)
                .hasMessageContaining("用于个人对账")
                .hasMessageContaining("证明材料");
    }

    @Test
    @DisplayName("表头在但一条交易都没有 → 也要报错,不能静默返回空")
    void zeroRowsFailsLoudly() {
        String only = "说明\n交易时间,交易分类,交易对方,收/支,金额,交易状态\n";
        assertThatThrownBy(() -> CsvBillParser.parse(only.getBytes(GBK)))
                .isInstanceOf(CsvBillParser.ParseException.class)
                .hasMessageContaining("一条交易都没解出来");
    }

    @Test
    @DisplayName("缺「收/支」或「金额」列 → 报错并回显读到的表头,方便对照")
    void missingCriticalColumnFails() {
        String s = "说明\n交易时间,交易分类,交易对方,商品说明,交易状态,交易订单号\n2026-09-01,餐饮美食,x,y,成功,T1\n";
        assertThatThrownBy(() -> CsvBillParser.parse(s.getBytes(GBK)))
                .isInstanceOf(CsvBillParser.ParseException.class)
                .hasMessageContaining("没有「收/支」或「金额」列");
    }

    // ─────────────── 金额与引号 ───────────────

    @Test
    @DisplayName("金额带 ¥ 与千分逗号都要洗干净(两个渠道都会带)")
    void moneyCleansCurrencyAndCommas() {
        assertThat(CsvBillParser.money("￥1,280.00")).isEqualByComparingTo("1280.00");
        assertThat(CsvBillParser.money("¥1,280.00")).isEqualByComparingTo("1280.00");
        // 半角 ¥ 在 GBK 里不可映射,过一趟 GBK 会变成「?」—— 也得能解出来,
        // 否则一个字符差异就静默吞掉一整行(开发时真踩了这一下)
        assertThat(CsvBillParser.money("?1,280.00")).isEqualByComparingTo("1280.00");
        assertThat(CsvBillParser.money("-32.50")).isEqualByComparingTo("-32.50");
        assertThat(CsvBillParser.money("￥25")).isEqualByComparingTo("25");
        assertThat(CsvBillParser.money(" 32.50 ")).isEqualByComparingTo("32.50");
        assertThat(CsvBillParser.money("")).isNull();
        assertThat(CsvBillParser.money("不是数字")).isNull();
    }

    @Test
    @DisplayName("商品说明里的逗号不能把列拆错 —— 引号必须处理")
    void quotedCommasDoNotBreakColumns() {
        String[] c = CsvBillParser.splitCsv("a,\"午餐,两人\",b");
        assertThat(c).containsExactly("a", "午餐,两人", "b");
        var p = CsvBillParser.parse(alipay(24));
        assertThat(p.rows().get(0).goods()).isEqualTo("午餐,两人");
        assertThat(p.rows().get(0).amount())
                .as("￥1,280.00 —— 逗号既在金额里也在商品说明里,两处都要对")
                .isEqualByComparingTo("1280.00");
    }

    // ─────────────── 渠道差异 ───────────────

    @Test
    @DisplayName("支付宝有「交易分类」列 → 直接拿到渠道分类名")
    void alipayCarriesChannelCategory() {
        var p = CsvBillParser.parse(alipay(24));
        assertThat(p.rows()).extracting(BillRow::channelCategory)
                .contains("餐饮美食", "交通出行", "转账红包", "投资理财");
    }

    @Test
    @DisplayName("微信没有分类列 → channelCategory 是交易类型,得靠商户关键字映射")
    void wechatHasNoRealCategory() {
        var p = CsvBillParser.parse(wechat());
        assertThat(p.rows()).extracting(BillRow::channelCategory)
                .as("只有「商户消费/转账/信用卡还款」这种交易类型,不是消费分类")
                .contains("商户消费", "转账", "信用卡还款");
        assertThat(p.rows()).extracting(BillRow::counterparty)
                .contains("美团外卖", "滴滴出行");
    }

    @Test
    @DisplayName("方向与状态解对:支出 / 收入 / 不计收支 / 退款成功")
    void directionAndStatusParsed() {
        var p = CsvBillParser.parse(alipay(24));
        assertThat(p.rows().stream().filter(BillRow::isExpense).count()).isEqualTo(6);
        assertThat(p.rows().stream().filter(BillRow::isRefund).count()).isEqualTo(1);
        assertThat(p.rows()).extracting(BillRow::direction).contains("不计收支");
    }

    @Test
    @DisplayName("渠道新增了不认识的收支枚举 → 计数披露,不静默丢弃")
    void unknownDirectionIsCounted() {
        String s = "说明\n交易时间,交易分类,收/支,金额,交易状态\n"
                 + "2026-09-01,餐饮美食,某种新方向,100.00,成功\n";
        var p = CsvBillParser.parse(s.getBytes(GBK));
        assertThat(p.unknownDirection())
                .as("不认识的枚举要能在确认页说出来,而不是当没看见")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("文件尾部的分隔行不该被当成交易")
    void trailingSeparatorIgnored() {
        var p = CsvBillParser.parse(alipay(24));
        assertThat(p.rows()).hasSize(8);   // 8 条交易,尾部「-----」行不算
    }

    @Test
    @DisplayName("字段里的逗号没被引号包住 → 列数超表头,计数披露而不是静默错位")
    void columnMismatchIsCountedNotSilentlyMisparsed() {
        // 金额 ¥1,280.00 没加引号 —— 这一行会多出一列
        String s = "说明\n交易时间,交易分类,收/支,金额,交易状态\n"
                 + "2026-09-01,餐饮美食,支出,¥1,280.00,交易成功\n"
                 + "2026-09-02,交通出行,支出,32.50,交易成功\n";
        var p = CsvBillParser.parse(s.getBytes(GBK));
        assertThat(p.rows()).as("坏行被跳过,好行照解").hasSize(1);
        assertThat(p.columnMismatch())
                .as("坏行要能在确认页说出来 —— 静默错位会把金额读成 1")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("金额解不出来的行要计数披露 —— 静默少算几行的话,用户根本查不出来")
    void unparseableAmountIsCounted() {
        String s = "说明\n交易时间,交易分类,交易对方,收/支,金额,交易状态\n"
                 + "2026-09-01,餐饮美食,某店,支出,---,交易成功\n"
                 + "2026-09-02,交通出行,滴滴,支出,32.50,交易成功\n";
        var p = CsvBillParser.parse(s.getBytes(GBK));
        assertThat(p.rows()).hasSize(1);
        assertThat(p.badAmount()).isEqualTo(1);
        assertThat(p.hasAnomaly()).as("确认页据此提醒用户核对总额").isTrue();
    }

    // ─────────────── 映射:渠道名要在整棵树上匹配 ───────────────

    @Test
    @DisplayName("【踩过的坑】复杂深度下,渠道给的大类名要能落在【大类】上,而不是全进「其他」")
    void channelCategoryMatchesTopLevelEvenInDeepMode() {
        var cats = new com.family.finance.service.expense.ExpenseFakes.FakeCategoryMapper();
        var splits = new com.family.finance.service.expense.ExpenseFakes.FakeSplitMapper();
        var catSvc = new com.family.finance.service.expense.ExpenseCategoryService(cats, splits);
        catSvc.seed(1L, true);                       // 复杂版:10 大类 + 细类

        var all = catSvc.all(1L);
        long otherId = catSvc.other(1L).getId();
        var parsed = CsvBillParser.parse(alipay(24));
        var draft = BillCategoryResolver.aggregate(
                com.family.finance.domain.expense.ExpenseSource.ALIPAY, parsed, all,
                java.util.Map.of(), otherId);

        var food = draft.lines().stream()
                .filter(l -> "餐饮美食".equals(l.channelLabel())).findFirst().orElseThrow();
        assertThat(food.categoryName())
                .as("「餐饮美食」是大类名 —— 落在大类上就是「未细分」,不该进「其他」")
                .isEqualTo("餐饮美食");
        assertThat(food.how()).isEqualTo("同名直挂");

        // 复杂版里「日用百货」下正好有个同名细类「家居家装」→ 细类优先(更精确)
        var home = draft.lines().stream()
                .filter(l -> "家居家装".equals(l.channelLabel())).findFirst().orElseThrow();
        assertThat(home.categoryName()).isEqualTo("家居家装");
        assertThat(home.how()).isEqualTo("同名直挂");

        // 「酒店旅游」我们树里没有同名节点 → 走别名表落「文化休闲」的细类「旅游酒店」
        var hotel = draft.lines().stream()
                .filter(l -> "酒店旅游".equals(l.channelLabel())).findFirst().orElseThrow();
        assertThat(hotel.how()).isEqualTo("映射");
        assertThat(hotel.categoryName()).isEqualTo("文化休闲");

        assertThat(draft.unmapped())
                .as("起步包 + 别名表应该覆盖掉全部,不该有「没认出来」")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("中性交易被剔除且列得出来:转账红包 / 投资理财 都不算消费")
    void neutralTransactionsExcluded() {
        var cats = new com.family.finance.service.expense.ExpenseFakes.FakeCategoryMapper();
        var splits = new com.family.finance.service.expense.ExpenseFakes.FakeSplitMapper();
        var catSvc = new com.family.finance.service.expense.ExpenseCategoryService(cats, splits);
        catSvc.seed(1L, false);
        var draft = BillCategoryResolver.aggregate(
                com.family.finance.domain.expense.ExpenseSource.ALIPAY,
                CsvBillParser.parse(alipay(24)), catSvc.all(1L),
                java.util.Map.of(), catSvc.other(1L).getId());

        assertThat(draft.neutrals()).extracting("label").contains("转账红包");
        assertThat(draft.lines()).extracting("channelLabel")
                .as("投资理财是「不计收支」方向,连支出都不是")
                .doesNotContain("投资理财");
        assertThat(draft.total())
                .as("2000 的转账不该进支出合计")
                .isEqualByComparingTo("2416.50");   // 1280+32.5+45+399+660
    }
}
