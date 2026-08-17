package com.family.finance.service.broker.opend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.17 · 安装包哈希校验护栏。
 *
 * <p>对接 OpenD 等于在家里跑一个能操作真实券商账户的网关,而<b>富途官网不公布任何 md5/sha256</b>。
 * 所以清单里的哈希是我们自己算的,校验不过必须<b>拒绝安装</b> —— 这条链路错了,整个"值得信任"的说法就没了。</p>
 */
class OpendCatalogTest {

    /** 仓库里那份清单必须真的能读出来、且每条都带齐三个判据。 */
    @Test
    void repo_catalog_is_readable_and_complete() {
        OpendCatalog c = new OpendCatalog();
        var releases = c.catalog().releases();
        assertThat(releases).isNotEmpty();
        for (OpendCatalog.Release r : releases) {
            assertThat(r.version()).isNotBlank();
            assertThat(r.os()).isNotBlank();
            assertThat(r.file()).contains(r.version()).endsWith(".tar.gz");
            assertThat(r.bytes()).isGreaterThan(0);
            assertThat(r.sha256()).matches("[0-9a-f]{64}");   // 我们算的,不是官方给的
            assertThat(r.md5()).matches("[0-9a-f]{32}");
        }
    }

    /** 2026-08-17 实测钉住的那一版必须在清单里(跟版时只加行,不改这条判据的形状)。 */
    @Test
    void verified_release_for_ubuntu_is_pinned() {
        Optional<OpendCatalog.Release> r = new OpendCatalog().latestVerified("Ubuntu18.04");
        assertThat(r).isPresent();
        assertThat(r.get().md5()).isEqualTo("4297cdec5653565556802fcd1b148f05");   // == CDN etag(两次独立下载确认)
        assertThat(r.get().bytes()).isEqualTo(466920616L);
    }

    @Test
    void version_compare_is_numeric_not_lexical() {
        // 字符串比较会把 9.3.5308 判成比 10.10.7008 大
        assertThat(OpendCatalog.compareVersion("10.10.7008", "9.3.5308")).isPositive();
        assertThat(OpendCatalog.compareVersion("10.10.7008", "10.9.9999")).isPositive();
        assertThat(OpendCatalog.compareVersion("10.10.7008", "10.10.7008")).isZero();
        assertThat(OpendCatalog.compareVersion("2.19.1252", "10.0.0")).isNegative();
    }

    // ---------- 校验三种结局 ----------

    private Path pkg(Path dir, String name, String content) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    /** 清单里有 + 对上 → 放行。 */
    @Test
    void matching_hash_passes(@TempDir Path dir) throws Exception {
        Path f = pkg(dir, "Futu_OpenD_9.9.9_Test.tar.gz", "hello opend");
        OpendCatalog c = catalogFor(dir, f, "Futu_OpenD_9.9.9_Test.tar.gz");
        OpendCatalog.Verdict v = c.verify(f, "Futu_OpenD_9.9.9_Test.tar.gz");
        assertThat(v.verified()).isTrue();
        assertThat(v.ok()).isTrue();
        assertThat(v.detail()).contains("已核对");
    }

    /** 清单里有 + 对不上 → 必须判失败(调用方据此中止安装)。 */
    @Test
    void tampered_package_is_rejected(@TempDir Path dir) throws Exception {
        Path f = pkg(dir, "Futu_OpenD_9.9.9_Test.tar.gz", "hello opend");
        OpendCatalog c = catalogFor(dir, f, "Futu_OpenD_9.9.9_Test.tar.gz");
        Files.writeString(f, "hello 0pend", StandardCharsets.UTF_8);   // 同长度改一个字节 → 只有 sha 能抓住
        OpendCatalog.Verdict v = c.verify(f, "Futu_OpenD_9.9.9_Test.tar.gz");
        assertThat(v.verified()).isTrue();
        assertThat(v.ok()).isFalse();
        assertThat(v.detail()).contains("请不要绕过这个检查");
    }

    /** 字节数先对一遍:能立刻认出"下到一半"或"下到一个错误页面"。 */
    @Test
    void truncated_download_is_caught_by_size_first(@TempDir Path dir) throws Exception {
        Path f = pkg(dir, "Futu_OpenD_9.9.9_Test.tar.gz", "hello opend");
        OpendCatalog c = catalogFor(dir, f, "Futu_OpenD_9.9.9_Test.tar.gz");
        Files.writeString(f, "hel", StandardCharsets.UTF_8);
        OpendCatalog.Verdict v = c.verify(f, "Futu_OpenD_9.9.9_Test.tar.gz");
        assertThat(v.ok()).isFalse();
        assertThat(v.detail()).contains("下载可能不完整");
    }

    /** 清单里没有 → 不是"校验失败",而是"未核对":带出实算哈希交给用户判断。 */
    @Test
    void unknown_version_reports_unverified_with_real_hashes(@TempDir Path dir) throws Exception {
        Path f = pkg(dir, "Futu_OpenD_99.0.1_Ubuntu18.04.tar.gz", "brand new release");
        OpendCatalog.Verdict v = new OpendCatalog().verify(f, "Futu_OpenD_99.0.1_Ubuntu18.04.tar.gz");
        assertThat(v.verified()).isFalse();
        assertThat(v.ok()).isTrue();
        assertThat(v.sha256()).matches("[0-9a-f]{64}");
        assertThat(v.detail()).contains("富途官方不公布校验和");
    }

    /**
     * 清单本身读不出来时,必须说"无法校验",<b>不能</b>降级成"这一版没核对过"。
     * 后者会让用户以为只是新版本没跟上,而真实情况是我们的校验数据丢了 —— 勾一下"我确认"就绕过去了。
     */
    @Test
    void broken_catalog_reports_cannot_verify_not_unverified(@TempDir Path dir) throws Exception {
        Path f = pkg(dir, "Futu_OpenD_9.9.9_Test.tar.gz", "hello opend");
        OpendCatalog empty = new OpendCatalog() {
            @Override public Catalog catalog() { return new Catalog(java.util.List.of()); }
        };
        OpendCatalog.Verdict v = empty.verify(f, "Futu_OpenD_9.9.9_Test.tar.gz");
        assertThat(v.ok()).isFalse();                       // 不许放行
        assertThat(v.detail()).contains("无法校验");         // 也不许说成"未核对"
        assertThat(v.detail()).doesNotContain("还没核对过");
    }

    /** 造一个只含这个临时包的清单(哈希现算,避免测试里写死一个假值)。 */
    private OpendCatalog catalogFor(Path dir, Path f, String fileName) throws IOException {
        String sha = OpendCatalog.digest(f, "SHA-256");
        String md5 = OpendCatalog.digest(f, "MD5");
        long bytes = Files.size(f);
        Path json = dir.resolve("cat.json");
        Files.writeString(json, """
                {"releases":[{"version":"9.9.9","os":"Test","file":"%s","bytes":%d,"sha256":"%s","md5":"%s"}]}
                """.formatted(fileName, bytes, sha, md5), StandardCharsets.UTF_8);
        return new OpendCatalog() {
            @Override public Catalog catalog() {
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(Files.readString(json, StandardCharsets.UTF_8), Catalog.class);
                } catch (IOException e) { throw new IllegalStateException(e); }
            }
        };
    }
}
