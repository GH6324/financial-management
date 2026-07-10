package com.family.finance.service.broker;

import com.family.finance.domain.broker.BrokerLink;
import com.family.finance.domain.broker.BrokerVendor;

/**
 * 券商只读客户端 · v0.15。
 *
 * <p><b>只读铁律</b>:本接口<b>只有读方法</b>,没有任何下单 / 改单 / 撤单 / 解锁签名 ——
 * 上层无从调用写操作。实现类必须只调券商的查询接口(富途永不 unlockTrade;老虎只调 getPositions/getAssets)。</p>
 *
 * <p>凭据由实现类各自从 {@code FamilyConfigService} 读取(同 LLM client 范式,私密不回显)。</p>
 */
public interface BrokerClient {

    BrokerVendor vendor();

    /**
     * 一键测试连接:仅拉账户信息验证链路,不做任何写操作。失败抛异常。
     * @param link 关联(带 per-link 连接参数:OpenD host/port、券商账户号);null = 用全局默认凭据(管理台老虎开发者身份等)
     */
    BrokerDtos.TestReport testConnection(long familyId, BrokerLink link);

    /**
     * 只读拉取某关联的持仓 + 各币种现金。
     * @param link 关联(brokerAccountId 可空 → 聚合默认/全部实盘账户;opendHost/Port 可空 → 全局默认)
     */
    BrokerDtos.Snapshot fetch(long familyId, BrokerLink link);
}
