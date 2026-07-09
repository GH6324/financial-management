package com.family.finance.service.broker;

import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.service.config.FamilyConfigService;
import com.tigerbrokers.stock.openapi.client.config.ClientConfig;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.https.request.trade.PositionsRequest;
import com.tigerbrokers.stock.openapi.client.https.request.trade.PrimeAssetRequest;
import com.tigerbrokers.stock.openapi.client.https.response.trade.PositionsResponse;
import com.tigerbrokers.stock.openapi.client.https.response.trade.PrimeAssetResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 老虎 Tiger 只读客户端 · v0.15。
 *
 * <p><b>只读铁律</b>:只调 {@code PrimeAssetRequest}(资产/现金)与 {@code PositionsRequest}(持仓)查询,
 * 绝不构造 / 执行任何下单请求。</p>
 *
 * <p><b>接线状态(应用户约定:尽力版 + 真机迭代)</b>:凭据装配 + 现金(PrimeAsset segments)已按 SDK 写好;
 * 持仓请求的 ApiModel 构造方式需以真机 SDK 行为确认(javap 未能定出 build 入口)→ 见 fetch() 内 TODO,
 * 用真实老虎账号跑通后补上。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TigerBrokerClient implements BrokerClient {

    private final FamilyConfigService config;

    @Override public BrokerVendor vendor() { return BrokerVendor.TIGER; }

    private TigerHttpClient client(long familyId) {
        String tigerId = config.getString(familyId, FamilyConfigService.K_BROKER_TIGER_ID, "");
        String privateKey = config.getString(familyId, FamilyConfigService.K_BROKER_TIGER_KEY, "");
        String account = config.getString(familyId, FamilyConfigService.K_BROKER_TIGER_ACCOUNT, "");
        if (tigerId.isBlank() || privateKey.isBlank()) {
            throw new IllegalStateException("老虎凭据未配置(tiger_id / RSA 私钥)");
        }
        ClientConfig cc = ClientConfig.DEFAULT_CONFIG;
        cc.tigerId = tigerId;
        cc.privateKey = privateKey;
        if (!account.isBlank()) cc.defaultAccount = account;
        return new TigerHttpClient().clientConfig(cc);
    }

    @Override
    public String testConnection(long familyId) {
        String account = config.getString(familyId, FamilyConfigService.K_BROKER_TIGER_ACCOUNT, "");
        PrimeAssetResponse resp = client(familyId).execute(PrimeAssetRequest.buildPrimeAssetRequest(account));
        if (resp == null || !resp.isSuccess()) {
            throw new IllegalStateException("老虎连接失败:" + (resp == null ? "无响应" : resp.getMessage()));
        }
        return "老虎连接正常 · 已拉到账户资产";
    }

    @Override
    public BrokerDtos.Snapshot fetch(long familyId, String brokerAccountId) {
        TigerHttpClient c = client(familyId);
        String account = (brokerAccountId != null && !brokerAccountId.isBlank())
                ? brokerAccountId
                : config.getString(familyId, FamilyConfigService.K_BROKER_TIGER_ACCOUNT, "");

        // ---- 现金(PrimeAsset · segment 按币种)----
        List<BrokerDtos.Cash> cash = new ArrayList<>();
        PrimeAssetResponse ar = c.execute(PrimeAssetRequest.buildPrimeAssetRequest(account));
        if (ar != null && ar.isSuccess() && ar.getItem() != null && ar.getItem().getSegments() != null) {
            ar.getItem().getSegments().forEach(seg -> {
                if (seg.getCurrency() != null && seg.getCashBalance() != null) {
                    cash.add(new BrokerDtos.Cash(seg.getCurrency(), BigDecimal.valueOf(seg.getCashBalance())));
                }
            });
        }

        // ---- 持仓 ----
        List<BrokerDtos.Position> positions = new ArrayList<>();
        int skipped = 0;
        PositionsRequest posReq = buildPositionsRequest(account);
        if (posReq != null) {
            PositionsResponse pr = c.execute(posReq);
            if (pr != null && pr.isSuccess() && pr.getItem() != null && pr.getItem().getPositions() != null) {
                for (var d : pr.getItem().getPositions()) {
                    if (!BrokerTicker.isEquity(d.getSecType())) { skipped++; continue; }
                    var n = BrokerTicker.fromTiger(d.getMarket(), d.getSymbol());
                    if (n == null) { skipped++; continue; }
                    positions.add(new BrokerDtos.Position(
                            n.market().name(), n.ticker(),
                            d.getPositionQty() == null ? BigDecimal.ZERO : BigDecimal.valueOf(d.getPositionQty()),
                            d.getAverageCost() == null ? null : BigDecimal.valueOf(d.getAverageCost()),
                            d.getCurrency(), true));
                }
            }
        }
        return new BrokerDtos.Snapshot(positions, cash, skipped);
    }

    /**
     * 构造持仓查询请求。⚠ 真机接线点:PositionsRequest 需要一个 ApiModel(携带 account 等),
     * 该 ApiModel 的确切构造方式需以真实 SDK 行为确认;暂返回 null(fetch 只取现金),真机跑通后补。
     */
    private PositionsRequest buildPositionsRequest(String account) {
        // TODO(真机): 参照老虎官方 java 示例设置 PositionsRequest 的 ApiModel(account/secType 等)。
        return null;
    }
}
