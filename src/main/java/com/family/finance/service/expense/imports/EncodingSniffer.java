package com.family.finance.service.expense.imports;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * v1.21 · 账单 CSV 的编码探测。
 *
 * <p><b>支付宝是 GBK,微信是 UTF-8</b> —— 同一个功能里两种编码,而且没有 BOM 可依赖。
 *
 * <h3>为什么不按渠道写死</h3>
 *
 * <p>写死意味着渠道哪天改了编码就<b>静默乱码</b>:文件解析得出来、行数也对,
 * 只是类目名变成一串问号,然后被当成「未映射」全塞进「其他」。
 * 不报错、不为零、只是错 —— 这是最难发现的一类失败。</p>
 *
 * <h3>判据:看 U+FFFD,不看异常</h3>
 *
 * <p>GBK 文本按 UTF-8 解码<b>不会抛异常</b>,只会产生替换字符 U+FFFD(「�」)。
 * 所以不能用 try-catch 判断 —— 必须数替换字符。反向同理:
 * UTF-8 中文按 GBK 解出来是「乱码但合法」的字符,所以还要看「有没有解出常见中文」。</p>
 */
public final class EncodingSniffer {

    private static final Charset GBK = Charset.forName("GBK");

    /** 账单里必然出现的中文锚点 —— 解对了就一定能看到其中几个 */
    private static final String[] ANCHORS = {
            "交易时间", "交易分类", "交易对方", "商品说明", "收/支", "金额", "交易状态",
            "交易类型", "商品", "支付方式", "当前状态", "备注", "支付宝", "微信"
    };

    private EncodingSniffer() {}

    public record Guess(Charset charset, int anchorHits, int replacementChars) {}

    /**
     * 探测。只看前 32KB —— 说明头 + 表头一定在里面,而整文件可能有几十万行。
     *
     * <p>规则:锚点命中多的胜;打平时替换字符少的胜;还打平就用 UTF-8
     * (它是更常见的默认值,猜错的话下一步的表头定位会显式报错,不会静默)。</p>
     */
    public static Charset sniff(byte[] bytes) {
        int n = Math.min(bytes.length, 32 * 1024);
        byte[] head = new byte[n];
        System.arraycopy(bytes, 0, head, 0, n);

        Guess utf8 = score(head, StandardCharsets.UTF_8);
        Guess gbk = score(head, GBK);

        if (utf8.anchorHits() != gbk.anchorHits()) {
            return utf8.anchorHits() > gbk.anchorHits() ? utf8.charset() : gbk.charset();
        }
        if (utf8.replacementChars() != gbk.replacementChars()) {
            return utf8.replacementChars() < gbk.replacementChars() ? utf8.charset() : gbk.charset();
        }
        return StandardCharsets.UTF_8;
    }

    static Guess score(byte[] head, Charset cs) {
        String s = new String(head, cs);
        int hits = 0;
        for (String a : ANCHORS) if (s.contains(a)) hits++;
        int bad = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '�') bad++;
        return new Guess(cs, hits, bad);
    }
}
