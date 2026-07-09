package com.family.finance.service.broker;

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

    /** 一键测试连接:仅拉账户信息验证链路,不做任何写操作。返回人类可读状态;失败抛异常。 */
    String testConnection(long familyId);

    /**
     * 只读拉取某券商交易账户的持仓 + 各币种现金。
     * @param brokerAccountId 券商侧账户号(可空 → 用默认账户)
     */
    BrokerDtos.Snapshot fetch(long familyId, String brokerAccountId);
}
