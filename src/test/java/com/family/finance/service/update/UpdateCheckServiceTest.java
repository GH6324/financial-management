package com.family.finance.service.update;

import com.family.finance.repository.FamilyRuntimeConfigMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * v1.9 · 版本检查的纯函数部分。
 *
 * <p>这些断言守的是 tech-design v1.9 里三条最容易做错的:
 * ① 版本解析不了就别猜 ② 迁移判定 fail-closed ③ 结果必须收敛进 VARCHAR(512)。</p>
 */
class UpdateCheckServiceTest {

    private UpdateCheckService svc() {
        return new UpdateCheckService(mock(FamilyRuntimeConfigMapper.class), new ObjectMapper());
    }

    // ── 版本比较 ────────────────────────────────────────────────────────

    @Test
    void 三段版本正常比较() {
        assertThat(UpdateCheckService.compare("v1.6.4", "v1.9.0")).isNegative();
        assertThat(UpdateCheckService.compare("v1.9.0", "v1.9.0")).isZero();
        assertThat(UpdateCheckService.compare("v1.9.1", "v1.9.0")).isPositive();
    }

    @Test
    void 比较按数值不按字典序() {
        // 字典序会把 v1.10.0 判成比 v1.9.0 小 —— 这是版本比较最经典的错
        assertThat(UpdateCheckService.compare("v1.9.0", "v1.10.0")).isNegative();
        assertThat(UpdateCheckService.compare("v1.2.10", "v1.2.9")).isPositive();
    }

    @Test
    void 带不带v前缀都认() {
        assertThat(UpdateCheckService.compare("1.8.1", "v1.8.1")).isZero();
    }

    @Test
    void 解析不了一律返回null_不猜() {
        // 自己 fork 改过版本号、或带 -rc / -SNAPSHOT 后缀的,一律不认
        assertThat(UpdateCheckService.compare("dev", "v1.9.0")).isNull();
        assertThat(UpdateCheckService.compare("v1.9.0-rc1", "v1.9.0")).isNull();
        assertThat(UpdateCheckService.compare("v1.9", "v1.9.0")).isNull();
        assertThat(UpdateCheckService.compare(null, "v1.9.0")).isNull();
    }

    @Test
    void 版本解析不了时hasUpdate必须是false() {
        var info = new UpdateCheckService.UpdateInfo(Instant.now(), "dev", "v1.9.0", 3,
                new UpdateCheckService.Migrations(0, List.of(), true), List.of());
        assertThat(info.hasUpdate()).isFalse();
    }

    @Test
    void 从来没查成功过时hasUpdate必须是false() {
        // checkedAt == null 代表「一次都没成功过」→ 全站不许显示任何更新信息(PRD 验收 2)
        assertThat(UpdateCheckService.UpdateInfo.unknown().hasUpdate()).isFalse();
    }

    // ── compare 的 ref 必须是真实 tag ────────────────────────────────────

    @Test
    void 版本号要归一化成tag引用_否则compare必然404() {
        // app.version 是 "1.9.0"(不带 v),而 tag 是 "v1.9.0"。
        // 直接拼进 compare URL → /compare/1.9.0...v1.9.1 → 404 → 迁移判定永远「无法确定」。
        assertThat(UpdateCheckService.tagOf("1.9.0")).isEqualTo("v1.9.0");
        assertThat(UpdateCheckService.tagOf("v1.9.0")).isEqualTo("v1.9.0");   // 已带 v 不重复加
        assertThat(UpdateCheckService.tagOf("V1.9.0")).isEqualTo("V1.9.0");
        assertThat(UpdateCheckService.tagOf("  1.9.0  ")).isEqualTo("v1.9.0");
        assertThat(UpdateCheckService.tagOf(null)).isNull();
        assertThat(UpdateCheckService.tagOf("  ")).isNull();
    }

    // ── 迁移判定 · fail-closed ──────────────────────────────────────────

    @Test
    void 能从改动文件里认出迁移() {
        var m = UpdateCheckService.detectMigrations(List.of(
                "src/main/java/Foo.java",
                "db/migration/V53__expense_entry_mode.sql",
                "README.md"), false);
        assertThat(m.known()).isTrue();
        assertThat(m.count()).isEqualTo(1);
        assertThat(m.ids()).containsExactly("V53");
        assertThat(m.hasAny()).isTrue();
    }

    @Test
    void 没有迁移时是已知的零而不是未知() {
        var m = UpdateCheckService.detectMigrations(List.of("README.md"), false);
        assertThat(m.known()).isTrue();
        assertThat(m.count()).isZero();
        assertThat(m.hasAny()).isFalse();      // 页面显示「无 schema 变更」
    }

    @Test
    void 文件清单被截断时必须标未知_不能报没有迁移() {
        // GitHub compare 的 files 上限 300。达到上限说明清单不全 ——
        // 这时报「无 schema 变更」是**错误且危险**的结论(用户会以为能安全回退)。
        var m = UpdateCheckService.detectMigrations(List.of("a", "b"), true);
        assertThat(m.known()).isFalse();
        assertThat(m.hasAny()).isFalse();      // 未知 ≠ 有,页面显示「无法确定」
    }

    @Test
    void 拿不到清单时必须标未知() {
        var m = UpdateCheckService.detectMigrations(null, false);
        assertThat(m.known()).isFalse();
    }

    @Test
    void 迁移id取双下划线之前那段() {
        var m = UpdateCheckService.detectMigrations(
                List.of("db/migration/V50__a.sql", "db/migration/V51__b.sql"), false);
        assertThat(m.ids()).containsExactly("V50", "V51");
    }

    // ── 写入前收敛进 VARCHAR(512) ───────────────────────────────────────

    @Test
    void 结果必须收敛进512_超长时丢items尾部而不是写半截json() {
        List<UpdateCheckService.Item> many = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            many.add(new UpdateCheckService.Item("v1." + i + ".0",
                    "这是一个非常长的版本主题用来把 JSON 撑爆看看会不会被正确截断处理掉"));
        }
        var info = new UpdateCheckService.UpdateInfo(Instant.now(), "v1.0.0", "v1.9.0", 5,
                new UpdateCheckService.Migrations(4, List.of("V50", "V51", "V52", "V53"), true), many);

        var s = svc().serializeWithin(info);
        assertThat(s).isPresent();
        assertThat(s.get().length()).isLessThanOrEqualTo(UpdateCheckService.VALUE_MAX);
        // 关键字段一个都不能丢 —— 丢的只能是 items 尾部
        assertThat(s.get()).contains("\"latest\":\"v1.9.0\"", "\"behind\":5", "\"known\":true");
    }

    @Test
    void 短结果原样序列化不丢items() {
        var info = new UpdateCheckService.UpdateInfo(Instant.now(), "v1.8.1", "v1.9.0", 1,
                new UpdateCheckService.Migrations(0, List.of(), true),
                List.of(new UpdateCheckService.Item("v1.9.0", "自动版本查询")));
        var s = svc().serializeWithin(info).orElseThrow();
        assertThat(s).contains("v1.9.0", "自动版本查询");
        assertThat(s.length()).isLessThanOrEqualTo(UpdateCheckService.VALUE_MAX);
    }

    @Test
    void 长标题被截断() {
        String t = "a".repeat(200);
        assertThat(UpdateCheckService.trimTitle(t).length())
                .isLessThanOrEqualTo(UpdateCheckService.MAX_TITLE);
    }

    // ── 不带遥测 ────────────────────────────────────────────────────────

    @Test
    void UA不含版本号() {
        // GitHub 要求带 UA,顺手写成 financial-management/1.9.0 是最自然的写法,
        // 那就把版本号发出去了 —— 与 PRD FR-303「不带版本号」冲突。
        assertThat(UpdateCheckService.UA).isEqualTo("financial-management");
        assertThat(UpdateCheckService.UA).doesNotContain("/");
        assertThat(UpdateCheckService.UA).doesNotMatch(".*\\d+\\.\\d+.*");
    }

    @Test
    void 仓库地址写死_不可配置() {
        // 可配置 = 可被指向任意仓库 = 可被诱导去信任别人的 release
        assertThat(UpdateCheckService.REPO).isEqualTo("LuoDi-Nate/financial-management");
    }
}
