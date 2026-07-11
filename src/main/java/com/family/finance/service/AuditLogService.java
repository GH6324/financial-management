package com.family.finance.service;

import com.family.finance.domain.audit.AuditLog;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.repository.AuditMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditMapper auditMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** summary 列是 VARCHAR(255);防御性截断,审计写入永不因超长拖垮业务事务(明细放 payload_json)。 */
    static final int SUMMARY_MAX = 255;
    private static String clip(String s) {
        if (s == null || s.length() <= SUMMARY_MAX) return s;
        return s.substring(0, SUMMARY_MAX - 1) + "…";
    }

    /** 简版:无 payload */
    public void record(long familyId,
                       Long actorMemberId,
                       AuditLogType type,
                       String targetType,
                       Long targetId,
                       String summary) {
        auditMapper.insert(AuditLog.builder()
                .familyId(familyId)
                .actorMemberId(actorMemberId)
                .type(type)
                .targetType(targetType)
                .targetId(targetId)
                .summary(clip(summary))
                .payloadJson(null)
                .build());
    }

    /** 完整版:含结构化 payload(JSON)*/
    public void write(long familyId,
                      Long actorMemberId,
                      AuditLogType type,
                      String targetType,
                      Long targetId,
                      String summary,
                      Map<String, ?> payload) {
        String json = null;
        if (payload != null) {
            try { json = objectMapper.writeValueAsString(payload); }
            catch (Exception e) { log.warn("[Audit] failed to serialize payload: {}", e.toString()); }
        }
        auditMapper.insert(AuditLog.builder()
                .familyId(familyId)
                .actorMemberId(actorMemberId)
                .type(type)
                .targetType(targetType)
                .targetId(targetId)
                .summary(clip(summary))
                .payloadJson(json)
                .build());
    }

    /** 系统级告警(无 actor)*/
    public void systemAlert(long familyId, AuditLogType type, String summary) {
        record(familyId, null, type, "system", null, summary);
    }
}
