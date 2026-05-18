package com.family.finance.domain.member;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 成员实体 · 纯 POJO,无 ORM 注解。
 * 详见 PRD § 1.4 / TDD § 3.1 member 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Member {
    private Long id;
    private Long familyId;
    private String username;
    private String passwordHash;
    private String displayName;
    private String roleLabel;
    /** v0.4.14 FR-63c · 手机号 · 私密 · 绝不进 LLM prompt / audit_log 明文 */
    private String phone;
    private boolean mustChangePw;
    private LocalDateTime archivedAt;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }
}
