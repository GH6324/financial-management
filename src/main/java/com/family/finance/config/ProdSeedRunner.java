package com.family.finance.config;

import com.family.finance.domain.member.Member;
import com.family.finance.repository.MemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Prod-profile only — 首次部署(含 Docker 一键 compose)时,种子成员的 password_hash 仍是
 * V2__seed.sql 里的 PLACEHOLDER(dev 的 {@link DevSeedRunner} 在 prod 不跑,而 Docker 跑 prod)。
 * 若不处理,种子账号根本登不进,也无法用"重置密码"(重置得先登录)→ bootstrap 死锁。
 * <p>
 * 本 runner 启动时把这些占位符换成临时密码(env {@code SEED_ADMIN_PASSWORD},缺省 {@code demo1234})、
 * 置 {@code must_change_pw=1}(首次登录强制改),并在启动日志打印醒目登录横幅,告诉部署者用什么账号登录。
 * <p>
 * <b>幂等 & 向后兼容</b>:只动 {@code password_hash LIKE 'PLACEHOLDER%'} 的成员。已设过真实密码的
 * 存量部署(线上 prod / 从 systemd 迁移过来的)一律 no-op,不碰任何已有账户。与 {@code deploy.sh}
 * (systemd)step 9b 共存:deploy.sh 在 app 启动前就替换了占位符 → 这里查不到 → 不重复处理。
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class ProdSeedRunner implements CommandLineRunner {

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;

    /** 种子账号首次临时密码;env SEED_ADMIN_PASSWORD 覆盖,缺省 demo1234(与 deploy.sh 一致,首登强制改)。 */
    @Value("${seed.admin-password:demo1234}")
    private String seedPassword;

    @Override
    public void run(String... args) {
        List<Member> placeholders = memberMapper.findSeedPlaceholders();
        if (placeholders.isEmpty()) {
            return; // 存量 / 已初始化部署 → 什么都不做(向后兼容)
        }

        String hash = passwordEncoder.encode(seedPassword);
        for (Member m : placeholders) {
            memberMapper.updatePasswordHash(m.getId(), hash, true); // must_change_pw=1
        }

        String names = placeholders.stream()
                .map(Member::getUsername)
                .collect(Collectors.joining("  /  "));
        log.warn("""

                ============================================================
                  首次登录 · 种子账号已初始化({} 个)
                  用户名:{}
                  临时密码:{}        ← 首次登录后会要求你改密
                  自定义临时密码:在 .env 设 SEED_ADMIN_PASSWORD= 后重建容器
                ============================================================
                """, placeholders.size(), names, seedPassword);
    }
}
