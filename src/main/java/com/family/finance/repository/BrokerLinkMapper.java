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
            SELECT id, account_id, vendor, broker_account_id AS brokerAccountId,
                   opend_host AS opendHost, opend_port AS opendPort, enabled,
                   last_synced_at AS lastSyncedAt, last_status AS lastStatus, created_at AS createdAt
              FROM broker_link WHERE account_id = #{accountId}
            """)
    Optional<BrokerLink> findByAccount(@Param("accountId") long accountId);

    @Select("""
            SELECT id, account_id, vendor, broker_account_id AS brokerAccountId,
                   opend_host AS opendHost, opend_port AS opendPort, enabled,
                   last_synced_at AS lastSyncedAt, last_status AS lastStatus, created_at AS createdAt
              FROM broker_link WHERE enabled = 1
            """)
    List<BrokerLink> findAllEnabled();

    /** 家庭内全部关联(账户页徽章用)· join account 拿 family 维度 */
    @Select("""
            SELECT bl.id, bl.account_id, bl.vendor, bl.broker_account_id AS brokerAccountId,
                   bl.opend_host AS opendHost, bl.opend_port AS opendPort, bl.enabled,
                   bl.last_synced_at AS lastSyncedAt, bl.last_status AS lastStatus, bl.created_at AS createdAt
              FROM broker_link bl JOIN account a ON a.id = bl.account_id
             WHERE a.family_id = #{familyId}
            """)
    List<BrokerLink> findByFamily(@Param("familyId") long familyId);

    @Insert("""
            INSERT INTO broker_link (account_id, vendor, broker_account_id, opend_host, opend_port, enabled)
            VALUES (#{accountId}, #{vendor}, #{brokerAccountId}, #{opendHost}, #{opendPort}, #{enabled})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BrokerLink link);

    @Update("""
            UPDATE broker_link
               SET enabled = #{enabled}, broker_account_id = #{brokerAccountId},
                   opend_host = #{opendHost}, opend_port = #{opendPort},
                   last_synced_at = #{lastSyncedAt}, last_status = #{lastStatus}
             WHERE account_id = #{accountId}
            """)
    int update(BrokerLink link);

    @Update("UPDATE broker_link SET last_synced_at = NOW(3), last_status = #{status} WHERE account_id = #{accountId}")
    int markSynced(@Param("accountId") long accountId, @Param("status") String status);

    @Delete("DELETE FROM broker_link WHERE account_id = #{accountId}")
    int deleteByAccount(@Param("accountId") long accountId);
}
