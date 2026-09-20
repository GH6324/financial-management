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

/**
 * broker_link 表 Mapper · v0.15。
 *
 * <p>v1.24 · 家庭隔离:本表没有 {@code family_id} 列,归属沿
 * {@code broker_link → account.family_id} 取。这张表里存的是<b>券商账号</b>与
 * OpenD 连接信息 —— 跨家庭读到就是直接读到别人家在哪个券商、哪个账号。</p>
 */
@Mapper
public interface BrokerLinkMapper {

    String COLS = " bl.id, bl.account_id, bl.vendor, bl.broker_account_id AS brokerAccountId,"
            + " bl.opend_host AS opendHost, bl.opend_port AS opendPort, bl.enabled,"
            + " bl.last_synced_at AS lastSyncedAt, bl.last_status AS lastStatus,"
            + " bl.created_at AS createdAt ";

    String OWNED = " JOIN account a ON a.id = bl.account_id";

    @Select("SELECT" + COLS + "FROM broker_link bl" + OWNED
          + " WHERE a.family_id = #{familyId} AND bl.account_id = #{accountId}")
    Optional<BrokerLink> findByAccount(@Param("familyId") long familyId,
                                       @Param("accountId") long accountId);

    /**
     * 这个家里所有 enabled 的关联 · 定时同步用。
     *
     * <p><b>v1.24 · 这个方法原来叫 {@code findAllEnabled()},查的是全库</b> ——
     * 而唯一的调用方 {@code BrokerSyncService.syncAllEnabled(familyId, …)} 拿到结果后
     * 用<b>自己的 familyId</b> 去 sync 每一条。也就是说定时任务会把别人家的券商账户
     * 当成这个家的来同步:持仓会被写进这个家的账户、审计日志记在这个家名下,
     * 而且<b>不报错</b>。这不是「缺一个过滤条件」,是一个实打实的跨家庭写入路径。</p>
     */
    @Select("SELECT" + COLS + "FROM broker_link bl" + OWNED
          + " WHERE a.family_id = #{familyId} AND bl.enabled = 1")
    List<BrokerLink> findEnabledByFamily(@Param("familyId") long familyId);

    /** 家庭内全部关联(账户页徽章用)· join account 拿 family 维度 */
    @Select("SELECT" + COLS + "FROM broker_link bl" + OWNED
          + " WHERE a.family_id = #{familyId}")
    List<BrokerLink> findByFamily(@Param("familyId") long familyId);

    @Insert("""
            INSERT INTO broker_link (account_id, vendor, broker_account_id, opend_host, opend_port, enabled)
            SELECT #{l.accountId}, #{l.vendor}, #{l.brokerAccountId}, #{l.opendHost}, #{l.opendPort}, #{l.enabled}
              FROM account a
             WHERE a.id = #{l.accountId} AND a.family_id = #{familyId}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "l.id")
    int insert(@Param("familyId") long familyId, @Param("l") BrokerLink link);

    /** 带归属断言的插入 —— 业务代码一律用这个 */
    default void insertOwned(long familyId, BrokerLink link) {
        if (insert(familyId, link) != 1) {
            throw new IllegalStateException("券商关联归属校验不通过:账户 " + link.getAccountId()
                    + " 不属于家庭 " + familyId);
        }
    }

    @Update("""
            UPDATE broker_link bl
              JOIN account a ON a.id = bl.account_id
               SET bl.enabled = #{l.enabled}, bl.broker_account_id = #{l.brokerAccountId},
                   bl.opend_host = #{l.opendHost}, bl.opend_port = #{l.opendPort},
                   bl.last_synced_at = #{l.lastSyncedAt}, bl.last_status = #{l.lastStatus}
             WHERE a.family_id = #{familyId}
               AND bl.account_id = #{l.accountId}
            """)
    int update(@Param("familyId") long familyId, @Param("l") BrokerLink link);

    @Update("UPDATE broker_link bl" + OWNED
          + " SET bl.last_synced_at = NOW(3), bl.last_status = #{status}"
          + " WHERE a.family_id = #{familyId} AND bl.account_id = #{accountId}")
    int markSynced(@Param("familyId") long familyId,
                   @Param("accountId") long accountId, @Param("status") String status);

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
    @Update("UPDATE broker_link bl" + OWNED
          + " SET bl.last_status = #{status}"
          + " WHERE a.family_id = #{familyId} AND bl.account_id = #{accountId}")
    int markFailed(@Param("familyId") long familyId,
                   @Param("accountId") long accountId, @Param("status") String status);

    @Delete("DELETE bl FROM broker_link bl" + OWNED
          + " WHERE a.family_id = #{familyId} AND bl.account_id = #{accountId}")
    int deleteByAccount(@Param("familyId") long familyId, @Param("accountId") long accountId);
}
