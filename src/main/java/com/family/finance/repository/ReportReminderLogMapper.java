package com.family.finance.repository;

import com.family.finance.domain.notify.ReportReminderLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * v0.4.14 FR-63c · report_reminder_log 写入 + 当天去重查询。
 */
@Mapper
public interface ReportReminderLogMapper {

    /** 当天该成员该渠道是否已发过(去重前置检查 · UNIQUE 兜底) */
    @Select("""
            SELECT COUNT(*)
              FROM report_reminder_log
             WHERE family_id = #{familyId}
               AND period_id = #{periodId}
               AND member_id = #{memberId}
               AND channel   = #{channel}
               AND remind_date = #{remindDate}
            """)
    int existsToday(@Param("familyId") long familyId,
                    @Param("periodId") long periodId,
                    @Param("memberId") long memberId,
                    @Param("channel") String channel,
                    @Param("remindDate") LocalDate remindDate);

    /** INSERT IGNORE · UNIQUE 命中(并发/重试)时静默跳过,不抛异常 */
    @Insert("""
            INSERT IGNORE INTO report_reminder_log
                (family_id, period_id, member_id, channel, remind_date, status, detail)
            VALUES
                (#{familyId}, #{periodId}, #{memberId}, #{channel},
                 #{remindDate}, #{status}, #{detail})
            """)
    int insert(ReportReminderLog log);

    /** v0.4.14 · /admin/reminders 提醒日志分页查询 · 倒序按发送时间 */
    @Select("""
            SELECT id, family_id, period_id, member_id, channel, remind_date, status, detail, sent_at
              FROM report_reminder_log
             WHERE family_id = #{familyId}
             ORDER BY sent_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<ReportReminderLog> findByFamily(@Param("familyId") long familyId,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM report_reminder_log WHERE family_id = #{familyId}")
    int countByFamily(@Param("familyId") long familyId);
}
