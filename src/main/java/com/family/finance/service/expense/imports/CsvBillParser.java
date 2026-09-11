package com.family.finance.service.expense.imports;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21 · 支付宝 / 微信「用于个人对账」CSV 的解析。
 *
 * <h3>核心决定:按【表头特征】定位,不写死行号</h3>
 *
 * <p>调研值是支付宝前 ~24 行说明、微信前 ~16 行,但<b>行数随导出条件浮动</b>
 * (勾不勾「展示交易对手信息」就会变)。写死行号 = 换个导出选项就全错位,
 * 而错位之后解出来的是<b>一堆看似合法的垃圾</b>,不是异常。</p>
 *
 * <p>所以:逐行找「同时含有几个已知列名」的那一行,它就是表头。找不到就<b>显式报错</b>,
 * 绝不猜一个行号继续。</p>
 *
 * <h3>为什么 0 行也要报错</h3>
 *
 * <p>「解析成功但一条支出都没有」在用户眼里和「导入了但没效果」一样 ——
 * 而真实原因通常是选错了文件(导了收入账单/证明材料)。静默返回空是最坏的选择。</p>
 */
@Slf4j
public final class CsvBillParser {

    private CsvBillParser() {}

    public static class ParseException extends RuntimeException {
        public ParseException(String m) { super(m); }
    }

    public record Parsed(List<BillRow> rows, String headerLine, int skippedLines,
                         int unknownDirection, int columnMismatch, int badAmount) {

        /** 解析过程中有没有「说不清的行」—— 确认页据此决定要不要提醒用户核对总额 */
        public boolean hasAnomaly() { return unknownDirection > 0 || columnMismatch > 0 || badAmount > 0; }
    }

    /** 表头必须至少命中这么多个已知列名 —— 3 个足以排除说明文字里偶然出现的词 */
    private static final int MIN_HEADER_HITS = 3;

    private static final String[] HEADER_TOKENS = {
            "交易时间", "交易分类", "交易对方", "对方账号", "商品说明", "收/支", "金额",
            "收/付款方式", "交易状态", "交易订单号", "商家订单号", "备注",
            "交易类型", "商品", "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号"
    };

    public static Parsed parse(byte[] csvBytes) {
        Charset cs = EncodingSniffer.sniff(csvBytes);
        String text = new String(csvBytes, cs);
        String[] lines = text.split("\r?\n");

        int headerIdx = -1;
        String[] header = null;
        for (int i = 0; i < lines.length && i < 200; i++) {
            String[] cells = splitCsv(lines[i]);
            int hits = 0;
            for (String c : cells) {
                String t = c.trim();
                for (String tok : HEADER_TOKENS) if (t.equals(tok)) { hits++; break; }
            }
            if (hits >= MIN_HEADER_HITS) { headerIdx = i; header = cells; break; }
        }
        if (headerIdx < 0) {
            throw new ParseException("这个文件里找不到账单的表头 —— 它可能不是「用于个人对账」的账单。"
                    + "另一个选项「用作证明材料」拿到的是盖章 PDF,那个导不了。"
                    + "(已按 " + cs.displayName() + " 读取)");
        }

        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) col.put(header[i].trim(), i);

        Integer iCat = firstOf(col, "交易分类", "交易类型");
        Integer iParty = firstOf(col, "交易对方");
        Integer iGoods = firstOf(col, "商品说明", "商品");
        Integer iDir = firstOf(col, "收/支");
        Integer iAmt = firstOf(col, "金额", "金额(元)", "金额（元）");
        Integer iStat = firstOf(col, "交易状态", "当前状态");
        Integer iTime = firstOf(col, "交易时间", "交易创建时间", "交易日期");
        /* 交易号:支付宝叫「交易订单号」,微信叫「交易单号」。
         * 拿不到不是致命的 —— 只是这批无法参与重复导入去重(见 BillRow.txNo 注释)。 */
        Integer iTx = firstOf(col, "交易订单号", "交易单号", "订单号");
        /* 资金来源。支付宝叫「收/付款方式」(余额宝 / 花呗 / 招商银行储蓄卡(1234)),
         * 微信叫「支付方式」(零钱 / 零钱通 / 工商银行(5678))。
         * 【一份账单里这一列是变化的】—— 整批落到同一个账户是错的,所以必须解出来。 */
        Integer iPay = firstOf(col, "收/付款方式", "支付方式", "付款方式");
        if (iAmt == null || iDir == null) {
            throw new ParseException("这个账单里没有「收/支」或「金额」列,解不出支出 —— "
                    + "导出时不要过滤掉这些列。表头读到的是:" + String.join(" / ", header));
        }

        List<BillRow> rows = new ArrayList<>();
        int unknownDir = 0;
        /* 列数超出表头 = 某个字段里的逗号没被引号包住(比如金额写成 ¥1,280.00 却不加引号)。
         * 这种行如果照常解析,会【静默错位】—— 金额读成 1、类目读成别的列。
         * 所以计数并在确认页披露,让人一眼看出「这个文件有点不对」。 */
        int colMismatch = 0;
        /* 金额解不出来的行。以前是 continue 了事 —— 而「静默少算了几行」在用户那儿
         * 表现为「导入的总额比账单上少一点」,基本查不出来。所以计数并在确认页披露。 */
        int badAmount = 0;
        for (int i = headerIdx + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) continue;
            String[] c = splitCsv(line);
            if (c.length <= iAmt) continue;                 // 文件尾部常有「-----」之类的收尾行
            if (c.length > header.length) { colMismatch++; continue; }
            BigDecimal amt = money(get(c, iAmt));
            if (amt == null) {
                // 只有「整行看起来是数据行」才算异常;文件尾部的分隔行/空行不算
                if (c.length >= header.length - 2) badAmount++;
                continue;
            }
            String dir = get(c, iDir).trim();
            if (!dir.equals("支出") && !dir.equals("收入") && !dir.equals("不计收支") && !dir.isEmpty()) {
                // 渠道加了新的收支枚举 → 计数并在确认页披露,而不是悄悄丢掉
                unknownDir++;
            }
            rows.add(new BillRow(
                    iCat == null ? null : nz(get(c, iCat)),
                    iParty == null ? null : nz(get(c, iParty)),
                    iGoods == null ? null : nz(get(c, iGoods)),
                    dir, amt,
                    iStat == null ? null : nz(get(c, iStat)),
                    iCat == null ? null : nz(get(c, iCat)),
                    iTime == null ? null : parseDate(nz(get(c, iTime))),
                    iTx == null ? null : blankToNull(nz(get(c, iTx))),
                    iPay == null ? null : blankToNull(nz(get(c, iPay)))));
        }
        if (rows.isEmpty()) {
            throw new ParseException("表头找到了,但一条交易都没解出来 —— "
                    + "确认一下选的是账单文件本身,而不是导出说明或其它文件。");
        }
        return new Parsed(rows, String.join(",", header), headerIdx, unknownDir, colMismatch, badAmount);
    }

    // ──────────────────────── 小工具 ────────────────────────

    private static Integer firstOf(Map<String, Integer> col, String... names) {
        for (String n : names) if (col.containsKey(n)) return col.get(n);
        return null;
    }

    private static String get(String[] c, int i) { return i < c.length ? c[i] : ""; }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    private static String blankToNull(String s) { return s == null || s.isEmpty() ? null : s; }

    /**
     * 交易时间 → 日期。渠道给的是 {@code 2026-09-03 12:31:05} 这种,取前 10 位即可。
     *
     * <p>解不出来返回 null,由调用方兜底成账期末 —— <b>不抛异常</b>:
     * 一个日期格式没见过,不该让整份账单导不进来。</p>
     */
    static java.time.LocalDate parseDate(String raw) {
        if (raw == null || raw.length() < 10) return null;
        String d = raw.substring(0, 10).replace('/', '-');
        try {
            return java.time.LocalDate.parse(d);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 金额清洗:两个渠道都可能带 ¥ 与千分逗号(社区合并工具的通行处理)。
     * 解不出数字返回 null —— 调用方跳过该行,而不是当 0。
     */
    static BigDecimal money(String raw) {
        if (raw == null) return null;
        /* 不逐个列举币种符号,而是【只保留数字/小数点/负号】。
         *
         * 起因是一个真实的坑:半角 ¥(U+00A5)在 GBK 里【不可映射】,
         * 写进 GBK 文件再读回来会变成「?」—— 而我原来的实现只 replace 了 ¥ 与 ￥,
         * 于是这一行的金额解不出来,整行被静默丢掉。真实 GBK 账单用的是全角 ￥(U+FFE5),
         * 但既然一个字符差异就能悄悄吞掉一整行,就别再靠枚举了。 */
        StringBuilder keep = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || (c == '-' && keep.isEmpty())) keep.append(c);
        }
        String s = keep.toString();
        if (s.isEmpty() || s.equals("-") || s.equals(".")) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * CSV 拆行。渠道账单的字段里会出现逗号(商品说明),所以必须处理引号。
     * 不引外部 CSV 库:这两个渠道的格式简单且固定,一个依赖不值得。
     */
    static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else inQuote = !inQuote;
            } else if (ch == ',' && !inQuote) {
                out.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
