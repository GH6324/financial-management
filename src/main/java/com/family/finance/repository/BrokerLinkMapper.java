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

    /**
     * 记一次<b>失败</b>的尝试(v1.17.3)。
     *
     * <p>刻意<b>不动 {@code last_synced_at}</b> —— 那一列的语义是"最后一次<b>成功</b>同步是什么时候",
     * 失败把它刷新会让"上次同步 5 分钟前"和"数据其实是三天前的"同时成立,比不显示更误导。</p>
     *
     * <p>为什么必须记:在此之前失败路径只 {@code log.warn},数据库里一个字都不改 ——
     * 于是页面上一直挂着<b>上一次成功</b>的消息。生产上富途实际已经断了两天,
     * 页面显示的仍是「同步 · 新增 0 · 更新 7 · 归档 0」,用户不去手点一次永远不会发现。</p>
     */
    @Update("UPDATE broker_link SET last_status = #{status} WHERE account_id = #{accountId}")
    int markFailed(@Param("accountId") long accountId, @Param("status") String status);

    @Delete("DELETE FROM broker_link WHERE account_id = #{accountId}")
    int deleteByAccount(@Param("accountId") long accountId);
}
