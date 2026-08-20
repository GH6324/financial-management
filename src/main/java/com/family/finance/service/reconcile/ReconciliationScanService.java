package com.family.finance.service.reconcile;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.family.Family;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.repository.StockValuationEventMapper;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.stock.StockHoldingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1.18.2 · 账目对账扫描(**只读**)—— 找「记了流水、却没落到余额里」的钱。
 *
 * <h3>为什么要有它</h3>
 * <p>v1.18.1 修掉一个会<b>丢钱</b>的 bug:划转进「持仓估值托管」的账户后,钱只加到当期余额、
 * 没记进该账户的现金行,下一次自动估值按「持仓合计」重算就把它覆盖掉 —— 生产上 7.5w 就这么
 * 消失了,而且<b>不可自动恢复</b>(period_snapshot 是覆盖写,被盖掉的值没有任何地方留底)。</p>
 *
 * <p>复盘结论是:<b>我们有探测器,但没接线</b>。{@code ReconciliationCalculator.unexplained}
 * 算的正是这个量,可它只在填报页对 CASH/LOAN 两种账户显示;而归因瀑布的「未归因」是<b>残差定义</b>
 * (按构造恒等闭合),任何错误都会被它吸收成某个账户的「亏损」——
 * <b>一个不会失败的恒等式不是校验,是装饰</b>。这个服务补的就是那条真会失败的校验。</p>
 *
 * <h3>判据(前两版都被真数据推翻了,如实记在这里)</h3>
 * <p><b>第一版</b>:「余额变化 = 流水 + 估值变动,对不上就报」,即 {@code periodPnl − Σ事件Δ}。
 * <b>抓不到</b> —— 估值覆盖余额时会<b>忠实地写一条 delta = −(被抹掉的钱) 的事件</b>,
 * 两边正好相消。这和归因瀑布「未归因」是同一个毛病:<b>把结果记下来再拿结果去对,永远对得上</b>。</p>
 *
 * <p><b>第二版</b>:「这一期记了流水,而期末余额跟期初一分没差」。在 beta 上反向验证时<b>没抓到</b> ——
 * 那个账户的持仓本身当期也在涨跌,余额并不是"一分没差"。这个形状只在「持仓恰好没动」时成立,太窄。</p>
 *
 * <p><b>现在这版</b>:签名要在<b>时间线</b>上才看得见。生产数据长这样:</p>
 * <pre>  08-17 17:42  转入 +40,000
 *  08-18 00:20  估值 Δ −40,000.00     ← 精确抹掉
 *  08-18 10:35  转入 +35,000
 *  08-18 16:10  估值 Δ −35,000.00     ← 又精确抹掉一次</pre>
 * <p>判据:<b>某次估值的 Δ,恰好等于它之前那段时间里进出账户的钱的相反数</b>
 * (窗口 = 上一次估值之后 到 这次估值为止)。市场波动不可能精确到分,所以几乎不会误报;
 * 而且它天然覆盖「一期里被分几次抹掉」的情形 —— 生产那笔正是分两次抹的。</p>
 *
 * <p>方向上它是对称的:转出没从账户扣掉(凭空多钱)也会被抓到,因为窗口和同样与 Δ 相消。</p>
 *
 * <p><b>它抓不到什么(说清楚,免得有人以为这是全量体检)</b>:估值那一下<b>同时</b>有真实涨跌 +
 * 被吞的流水时,两者叠加后就不再精确相消。要根治得在估值写回时就 fail-closed
 * (复盘里的方案 B),那是另一件事。</p>
 *
 * <h3>为什么只扫这些期(误报会让告警被关掉,等于没做)</h3>
 * <ul>
 *   <li><b>只看当前由估值托管的账户</b>:那是全系统唯一会对余额做<b>破坏性覆盖写</b>的地方。
 *       自己填余额的账户,数字是用户说了算,系统没有立场判他错。</li>
 *   <li><b>只在估值事件的时间窗口上判</b>:没有估值跑过,就没有覆盖写,也就没有钱会被吞。
 *       这条顺带解决了误报 —— 第二版曾把一个「2026-07 才加持仓」的账户的 2025 年那 12 期
 *       全报成异常(那时它还是普通自填账户,用户敲多少就是多少)。
 *       <b>误报会让告警被关掉,等于没做</b>,所以宁可窄。</li>
 *   <li><b>容差走管理页已有的 {@code unexplained_epsilon}</b>。那个参数此前<b>存了但没有任何
 *       代码读它</b>(旋钮装好了、线没接),这里把它接上。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReconciliationScanService {

    /** 一条异常:这一期记了流水,而余额的变化恰好等于「这笔钱从没发生过」 */
    public record Finding(long accountId, String accountName, String accountType, String currency,
                          long periodId, LocalDate periodStart,
                          BigDecimal netFlow,          // 该期净流水(收入−支出+转入−转出)
                          BigDecimal balanceChange,    // 命中的那几次估值一共写了多少(= 抹掉的动作)
                          BigDecimal valuationDelta,   // 同上(页面上并列展示,保留两列位置)
                          BigDecimal missing) {}       // 被抹掉、需要补回的金额

    public record Report(List<Finding> findings, BigDecimal epsilon,
                         int scannedAccounts, int scannedPeriods, boolean anyManagedAccount) {}

    private final AccountMapper accountMapper;
    private final FamilyMapper familyMapper;
    private final StockHoldingMapper holdingMapper;
    private final StockValuationEventMapper valuationEventMapper;
    private final FamilyConfigService configService;

    /** 全量扫描(默认回看 36 个月 —— 够覆盖这个 bug 的存续期,又不至于把首期建仓也拖进来)。 */
    public Report scan(long familyId) {
        return scan(familyId, 36);
    }

    public Report scan(long familyId, int lookbackMonths) {
        Family family = familyMapper.findById(familyId).orElse(null);
        if (family == null) return new Report(List.of(), eps(familyId), 0, 0, false);

        // ① 谁是「估值托管」账户 —— 判据与录入/估值同源(StockHoldingService.valuationManaged)
        Map<Long, Account> managed = new HashMap<>();
        for (Account a : accountMapper.findActiveByFamily(familyId)) {
            if (StockHoldingService.valuationManaged(a.getType(), holdingMapper.findActiveByAccount(a.getId()))) {
                managed.put(a.getId(), a);
            }
        }
        if (managed.isEmpty()) return new Report(List.of(), eps(familyId), 0, 0, false);

        // ② 估值事件 + 进出账户的钱,都按时间取回来(只读)
        Map<String, List<StockValuationEventMapper.ReconEvent>> eventsByKey = new HashMap<>();
        for (var e : valuationEventMapper.findEventsForReconcile(familyId)) {
            if (!managed.containsKey(e.accountId())) continue;
            eventsByKey.computeIfAbsent(e.accountId() + ":" + e.periodId(), k -> new ArrayList<>()).add(e);
        }
        Map<String, List<StockValuationEventMapper.ReconFlow>> flowsByKey = new HashMap<>();
        for (var f : valuationEventMapper.findFlowsForReconcile(familyId)) {
            if (!managed.containsKey(f.accountId())) continue;
            flowsByKey.computeIfAbsent(f.accountId() + ":" + f.periodId(), k -> new ArrayList<>()).add(f);
        }

        BigDecimal epsilon = eps(familyId);
        LocalDate floor = LocalDate.now().minusMonths(lookbackMonths);
        List<Finding> out = new ArrayList<>();
        Set<Long> periods = new HashSet<>();

        for (var entry : eventsByKey.entrySet()) {
            List<StockValuationEventMapper.ReconEvent> events = entry.getValue();
            var head = events.get(0);
            if (head.periodStart() != null && head.periodStart().isBefore(floor)) continue;
            Account acc = managed.get(head.accountId());
            if (acc == null) continue;
            periods.add(head.periodId());

            List<StockValuationEventMapper.ReconFlow> flows =
                    flowsByKey.getOrDefault(entry.getKey(), List.of());
            if (flows.isEmpty()) continue;

            BigDecimal erased = BigDecimal.ZERO;
            BigDecimal erasedByValuation = BigDecimal.ZERO;
            java.time.LocalDateTime windowStart = null;   // null = 从本期最早算起
            for (var ev : events) {
                if (ev.at() == null || ev.delta() == null) continue;
                BigDecimal windowFlow = BigDecimal.ZERO;
                for (var fl : flows) {
                    if (fl.at() == null || fl.at().isAfter(ev.at())) continue;
                    if (windowStart != null && !fl.at().isAfter(windowStart)) continue;
                    windowFlow = windowFlow.add(nz(fl.signedAmount()));
                }
                windowStart = ev.at();
                if (windowFlow.signum() == 0) continue;
                // 命中:这次估值的 Δ 恰好把窗口内进出的钱抵消掉了
                if (nz(ev.delta()).add(windowFlow).abs().compareTo(epsilon) <= 0) {
                    erased = erased.add(windowFlow);
                    erasedByValuation = erasedByValuation.add(nz(ev.delta()));
                }
            }
            if (erased.signum() == 0) continue;

            BigDecimal netFlow = BigDecimal.ZERO;
            for (var fl : flows) netFlow = netFlow.add(nz(fl.signedAmount()));
            out.add(new Finding(acc.getId(), acc.getDisplayName(), acc.getType().getLabel(), acc.getCurrency(),
                    head.periodId(), head.periodStart(),
                    netFlow.setScale(2, RoundingMode.HALF_EVEN),
                    erasedByValuation.setScale(2, RoundingMode.HALF_EVEN),
                    erasedByValuation.setScale(2, RoundingMode.HALF_EVEN),
                    erased.setScale(2, RoundingMode.HALF_EVEN)));
        }
        // 金额大的排前面(要人去补的钱,先看大的)
        out.sort((a, b) -> b.missing().abs().compareTo(a.missing().abs()));
        return new Report(out, epsilon, managed.size(), periods.size(), true);
    }

    /**
     * 容差:复用管理页「录入阈值」里的 {@code unexplained_epsilon}。
     * 下限钉 0.01 —— 配成 0 会让每一分钱的舍入都报警,那样的告警一天就会被关掉。
     */
    private BigDecimal eps(long familyId) {
        double v = configService.getDouble(familyId, FamilyConfigService.K_UNEXPLAINED_EPSILON, 0.01);
        return BigDecimal.valueOf(Math.max(0.01, v));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
