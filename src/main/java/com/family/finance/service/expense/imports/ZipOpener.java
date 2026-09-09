package com.family.finance.service.expense.imports;

import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.LocalFileHeader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.21 · 解开渠道给的加密 zip,取出里面的 csv(或截图)。
 *
 * <h3>安全限额:这是本项目唯一接受任意上传压缩包的地方</h3>
 *
 * <ul>
 *   <li><b>不写盘</b> —— 全程流式读进内存,解析完即弃(隐私红线:账单是整月消费流水)</li>
 *   <li><b>限总解压体积与文件数</b> —— 防 zip bomb(声明大小可以撒谎,所以边读边数)</li>
 *   <li><b>忽略目录项与路径中的 {@code ..}</b> —— 我们只按名字后缀取内容,从不按 zip 里的路径写文件</li>
 * </ul>
 */
@Slf4j
public final class ZipOpener {

    /** 解压后总字节上限。整年账单 csv 约几 MB;30MB 足够宽松,又拦得住 bomb。 */
    public static final long MAX_TOTAL_BYTES = 30L * 1024 * 1024;
    public static final int MAX_ENTRIES = 60;

    private ZipOpener() {}

    public record Entry(String name, byte[] bytes) {}

    public static class ZipException extends RuntimeException {
        public ZipException(String m) { super(m); }
        public ZipException(String m, Throwable c) { super(m, c); }
    }

    /**
     * 取出 zip 里所有匹配后缀的条目。
     *
     * @param password 明文口令;<b>只在这个方法的栈上存在</b>,不落库、不进日志
     * @param suffixes 想要的后缀(小写,含点),如 {@code .csv} / {@code .jpg}
     */
    public static List<Entry> open(byte[] zipBytes, String password, String... suffixes) {
        List<Entry> out = new ArrayList<>();
        long total = 0;
        char[] pw = (password == null || password.isBlank()) ? null : password.toCharArray();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), pw)) {
            LocalFileHeader h;
            int count = 0;
            while ((h = zis.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw new ZipException("这个压缩包里有太多文件(超过 " + MAX_ENTRIES + " 个)—— "
                            + "确认一下是不是选错了文件?");
                }
                if (h.isDirectory()) continue;
                String name = h.getFileName() == null ? "" : h.getFileName();
                String lower = name.toLowerCase();
                boolean want = false;
                for (String sfx : suffixes) if (lower.endsWith(sfx)) { want = true; break; }
                if (!want) continue;

                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int r;
                while ((r = zis.read(chunk)) != -1) {
                    total += r;
                    // 边读边数,不信 zip 头里声明的大小 —— 那个字段可以撒谎
                    if (total > MAX_TOTAL_BYTES) {
                        throw new ZipException("解压出来的内容太大了(超过 30MB)。"
                                + "账单文件通常只有几 MB —— 确认一下选的是账单而不是别的东西。");
                    }
                    buf.write(chunk, 0, r);
                }
                out.add(new Entry(name, buf.toByteArray()));
            }
        } catch (ZipException e) {
            throw e;
        } catch (net.lingala.zip4j.exception.ZipException e) {
            // zip4j 对「密码错」和「不是 zip」都抛这一个类型,靠消息分流会随版本漂 ——
            // 所以给一句覆盖两种可能的人话,让用户自己对照。
            throw new ZipException("这个压缩包打不开。要么解压密码不对(支付宝在「服务消息」里推、"
                    + "微信在「微信支付」的通知里),要么它不是账单压缩包。", e);
        } catch (Exception e) {
            throw new ZipException("读这个文件时出错了。可以试试自己解压出 csv 再上传。", e);
        }
        if (out.isEmpty()) {
            throw new ZipException("压缩包里没找到能用的文件。"
                    + "导出账单时要选「用于个人对账」—— 如果选了「用作证明材料」,拿到的是盖章 PDF,那个导不了。");
        }
        return out;
    }

    /** 看起来像不像 zip(魔数)· 用来在收到文件时分流,而不是靠扩展名 */
    public static boolean looksLikeZip(byte[] b) {
        return b != null && b.length > 4 && b[0] == 'P' && b[1] == 'K'
                && (b[2] == 3 || b[2] == 5 || b[2] == 7);
    }
}
