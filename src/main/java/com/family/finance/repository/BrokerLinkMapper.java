package com.family.finance.repository;

import com.family.finance.domain.broker.BrokerLink;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/** broker_link 表 Mapper · v0.15。 */
@Mapper
public interface BrokerLinkMapper {

    @Select("""
            SELECT id, account_id, vendor, broker_account_id AS brokerAccountId, enabled,
                   last_synced_at AS lastSyncedAt, last_status AS lastStatus, created_at AS createdAt
              FROM broker_link WHERE account_id = #{accountId}
            """)
    Optional<BrokerLink> findByAccount(@Param("accountId") long accountId);

    @Select("""
            SELECT id, account_id, vendor, broker_account_id AS brokerAccountId, enabled,
                   last_synced_at AS lastSyncedAt, last_status AS lastStatus, created_at AS createdAt
              FROM broker_link WHERE enabled = 1
            """)
    List<BrokerLink> findAllEnabled();

    @Insert("""
            INSERT INTO broker_link (account_id, vendor, broker_account_id, enabled)
            VALUES (#{accountId}, #{vendor}, #{brokerAccountId}, #{enabled})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BrokerLink link);

    @Update("""
            UPDATE broker_link
               SET enabled = #{enabled}, broker_account_id = #{brokerAccountId},
                   last_synced_at = #{lastSyncedAt}, last_status = #{lastStatus}
             WHERE account_id = #{accountId}
            """)
    int update(BrokerLink link);

    @Update("UPDATE broker_link SET last_synced_at = NOW(3), last_status = #{status} WHERE account_id = #{accountId}")
    int markSynced(@Param("accountId") long accountId, @Param("status") String status);

    @Delete("DELETE FROM broker_link WHERE account_id = #{accountId}")
    int deleteByAccount(@Param("accountId") long accountId);
}
