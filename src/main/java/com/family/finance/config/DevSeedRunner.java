package com.family.finance.config;

import com.family.finance.domain.member.Member;
import com.family.finance.repository.MemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dev-profile only — 在启动时检测种子成员的 password_hash 是否仍为 PLACEHOLDER,
 * 若是则自动设置为 bcrypt('demo1234')。
 * <p>
 * 生产环境请用 admin/members 的"重置密码"流程。
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevSeedRunner implements CommandLineRunner {

    private static final String DEV_PASSWORD = "demo1234";
    private static final String PLACEHOLDER_PREFIX = "PLACEHOLDER";

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        for (String username : List.of("diwa", "lijing")) {
            memberMapper.findByUsername(username).ifPresent(this::resetIfPlaceholder);
        }
    }

    private void resetIfPlaceholder(Member m) {
        if (m.getPasswordHash() != null && m.getPasswordHash().startsWith(PLACEHOLDER_PREFIX)) {
            String hash = passwordEncoder.encode(DEV_PASSWORD);
            // dev profile 直接放行 — 不要求 dev 用户首次登录强制改密(否则 QA / 手测都被拦)
            memberMapper.updatePasswordHash(m.getFamilyId(), m.getId(), hash, false);
            log.warn("[DevSeed] reset password for username='{}' (member id={}) to '{}'",
                    m.getUsername(), m.getId(), DEV_PASSWORD);
        }
    }
}
