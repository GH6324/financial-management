package com.family.finance.service.broker;

import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 富途 Futu 只读客户端 · v0.15。
 *
 * <p><b>只读铁律</b>:只连 FutuOpenD 走查询上下文,<b>永不 {@code unlockTrade}</b> → 物理上不能下单。</p>
 *
 * <p><b>接线状态(应用户约定:尽力版 + 真机迭代)</b>:富途 SDK 是<b>异步 protobuf over OpenD 回调</b>,
 * 且本环境无 OpenD 登录态、无法 runtime 验证 → 这里给出<b>确定的调用流程(见下),真机接线在你的环境完成</b>。
 * 流程(来自官方 java 文档 + jar 类):</p>
 * <pre>
 *   FTAPI.init();
 *   FTAPI_Conn_Trd trd = new FTAPI_Conn_Trd();
 *   trd.setClientInfo("finance", 1);
 *   trd.setTrdSpi(spi);            // 实现 FTSPI_Trd:onReply_GetPositionList / onReply_GetFunds
 *   trd.setConnSpi(spi);           // 实现 FTSPI_Conn:onInitConnect
 *   trd.initConnect(host, (short)port, false);   // 连 OpenD(127.0.0.1:11111)
 *   // 连上后:getAccList → getPositionList(TrdGetPositionList.Request{TrdHeader:accID/trdEnv/trdMarket})
 *   //        getFunds(TrdGetFunds.Request{同 header})
 *   // 回调里读 rsp.getS2C().getPositionListList()(code/qty/costPrice/val/secMarket)与 funds
 *   // 用 CompletableFuture/CountDownLatch 按 serialNo 把异步回调包成同步返回。
 * </pre>
 * 归一走 {@link BrokerTicker#fromFutu(String)}(HK./US./SH./SZ. → Market+ticker),非股票 secType 跳过。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FutuBrokerClient implements BrokerClient {

    private final FamilyConfigService config;

    @Override public BrokerVendor vendor() { return BrokerVendor.FUTU; }

    private void requireConfigured(long familyId) {
        String host = config.getString(familyId, FamilyConfigService.K_BROKER_FUTU_HOST, "");
        if (host.isBlank()) throw new IllegalStateException("富途 OpenD 未配置(host:port)");
    }

    @Override
    public String testConnection(long familyId) {
        requireConfigured(familyId);
        // TODO(真机): 连 FutuOpenD、拉一次 accList 验证只读链路(见类注释流程)。
        throw new UnsupportedOperationException("富途适配器待真机接线:需 FutuOpenD 登录态,请在自有环境按类注释流程接通(dev 无法验证)");
    }

    @Override
    public BrokerDtos.Snapshot fetch(long familyId, String brokerAccountId) {
        requireConfigured(familyId);
        // TODO(真机): getPositionList + getFunds(异步→同步),归一后返回 Snapshot(见类注释流程)。
        throw new UnsupportedOperationException("富途适配器待真机接线:需 FutuOpenD 登录态,请在自有环境按类注释流程接通(dev 无法验证)");
    }
}
