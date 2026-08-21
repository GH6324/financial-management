package com.family.finance.service.reconcile;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.AuditMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.repository.StockValuationEventMapper;
import com.family.finance.repository.StockValuationEventMapper.ReconEvent;
import com.family.finance.repository.StockValuationEventMapper.ReconFlow;
import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.18.2 · 账目对账扫描的判据护栏。
 *
 * <p><b>这条测试存在的理由,就是「不许再造一个永远绿的检查」。</b>
 * 这个扫描器的前两版判据都被真数据推翻过:</p>
 * <ol>
 *   <li>「余额变化 = 流水 + 估值变动,对不上就报」—— <b>抓不到</b>。估值抹钱时会忠实地写一条
 *       {@code delta = −(被抹的钱)} 的事件,两边正好相消。(与归因瀑布「未归因」同病:
 *       把结果记下来再拿结果去对,永远对得上。)</li>
 *   <li>「这一期记了流水,期末余额却跟期初一分没差」—— 在 beta 上反向验证时<b>也没抓到</b>,
 *       因为那个账户的持仓当期本身也在涨跌。形状太窄。</li>
 * </ol>
 * <p>所以本测的每一条都成对写:<b>该抓的抓到</b> + <b>不该抓的别抓</b>。
 * 只写前者的检查,是下一个「装饰品」。</p>
 *
 * <p>金额用合成值,不搬生产真实数值(护栏 v111-NO-PROD-AMOUNTS);
 * 但<b>时间线的形状</b>照抄生产实测(转入 → 数秒后估值精确抹平;以及分两次抹)。</p>
 */
class ReconciliationScanServiceTest {

    private static final long FAM = 1L, ACC = 7L, PERIOD = 100L;
    private static final LocalDate P_START = LocalDate.of(2026, 8, 1);

    private final AccountMapper accountMapper = mock(AccountMapper.class);
    private final FamilyMapper familyMapper = mock(FamilyMapper.class);
    private final StockHoldingMapper holdingMapper = mock(StockHoldingMapper.class);
    private final StockValuationEventMapper eventMapper = mock(StockValuationEventMapper.class);
    private final FamilyConfigService configService = mock(FamilyConfigService.class);
    private final AuditMapper auditMapper = mock(AuditMapper.class);

    private ReconciliationScanService svc(List<ReconEvent> events, List<ReconFlow> flows, AccountType type,
                                          List<StockHolding> holdings) {
        Family f = new Family();
        f.setId(FAM);
        f.setPeriodType(PeriodType.MONTHLY);
        f.setBaseCurrency("CNY");
        when(familyMapper.findById(anyLong())).thenReturn(java.util.Optional.of(f));

        Account a = new Account();
        a.setId(ACC);
        a.setFamilyId(FAM);
        a.setDisplayName("理财-货币基金");
        a.setType(type);
        a.setCurrency("CNY");
        when(accountMapper.findActiveByFamily(anyLong())).thenReturn(List.of(a));
        when(holdingMapper.findActiveByAccount(anyLong())).thenReturn(holdings);
        when(eventMapper.findEventsForReconcile(anyLong())).thenReturn(events);
        when(eventMapper.findFlowsForReconcile(anyLong())).thenReturn(flows);
        when(configService.getDouble(anyLong(), anyString(), anyDouble())).thenReturn(0.01);
        when(auditMapper.findByFamily(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        return new ReconciliationScanService(accountMapper, familyMapper, holdingMapper, eventMapper,
                configService, auditMapper);
    }

    private static List<StockHolding> holdings() {
        return List.of(StockHolding.builder().id(1L).accountId(ACC).displayName("某基金")
                .valuationMode(ValuationMode.MANUAL).build());
    }

    private static ReconEvent ev(String delta, int hour, int min) {
        return new ReconEvent(ACC, PERIOD, P_START, new BigDecimal(delta),
                LocalDateTime.of(2026, 8, 18, hour, min));
    }

    private static ReconFlow flow(String signed, int hour, int min) {
        return new ReconFlow(ACC, PERIOD, new BigDecimal(signed),
                LocalDateTime.of(2026, 8, 18, hour, min));
    }

    // ──────────────────────── 该抓的 ────────────────────────

    /** 生产形态:钱进来,几秒后估值把它精确抹平。 */
    @Test
    void 抓到_估值精确抹平了刚进来的钱() {
        var r = svc(List.of(ev("-40000.00", 12, 0)), List.of(flow("40000.00", 11, 0)),
                AccountType.WEALTH, holdings()).scan(FAM);
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).missing()).isEqualByComparingTo("40000.00");
        assertThat(r.findings().get(0).accountId()).isEqualTo(ACC);
    }

    /**
     * 生产上那笔是<b>分两次</b>抹掉的(转入 → 估值 → 又转入 → 又估值)。
     * 按「窗口 = 上次估值之后到这次估值为止」配对,两次都要命中,合计才是要补的钱。
     * 第一、二版判据都会在这里漏掉一半。
     */
    @Test
    void 抓到_一期里分两次被抹_合计要对() {
        var r = svc(
                List.of(ev("-40000.00", 12, 0), ev("-35000.00", 18, 0)),
                List.of(flow("40000.00", 11, 0), flow("35000.00", 15, 0)),
                AccountType.WEALTH, holdings()).scan(FAM);
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).missing()).isEqualByComparingTo("75000.00");
    }

    /** 方向对称:转出没从账户扣掉(等于凭空多出钱)也该被抓。 */
    @Test
    void 抓到_转出被抹平也算异常() {
        var r = svc(List.of(ev("12000.00", 12, 0)), List.of(flow("-12000.00", 11, 0)),
                AccountType.WEALTH, holdings()).scan(FAM);
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).missing()).isEqualByComparingTo("-12000.00");
    }

    // ──────────────────────── 不该抓的 ────────────────────────

    /** 钱正确落进现金行:估值重算后 Δ 只反映真实涨跌,不与流水相消。 */
    @Test
    void 不抓_钱正确入账时估值Δ只是真实涨跌() {
        var r = svc(List.of(ev("1350.00", 12, 0)), List.of(flow("40000.00", 11, 0)),
                AccountType.WEALTH, holdings()).scan(FAM);
        assertThat(r.findings()).isEmpty();
    }

    /** 估值跑在流水<b>之前</b>:窗口里没有钱,不该配对。 */
    @Test
    void 不抓_估值发生在流水之前() {
        var r = svc(List.of(ev("-40000.00", 10, 0)), List.of(flow("40000.00", 11, 0)),
                AccountType.WEALTH, holdings()).scan(FAM);
        assertThat(r.findings()).isEmpty();
    }

    /**
     * 账户没有持仓 = 估值从不接管它 = 不存在覆盖写。
     * 第二版判据就是在这里误报了 12 条(一个 2026-07 才加持仓的账户,2025 年那些期全被报出来)。
     */
    @Test
    void 不抓_没有持仓的账户压根不在扫描范围() {
        var r = svc(List.of(ev("-40000.00", 12, 0)), List.of(flow("40000.00", 11, 0)),
                AccountType.CASH, List.of()).scan(FAM);
        assertThat(r.findings()).isEmpty();
        assertThat(r.anyManagedAccount()).as("要能区分「查过没事」和「没得可查」").isFalse();
    }

    /** 不支持持仓的类型(房产/负债等)也不在范围内 —— 它们的余额本来就是人说了算。 */
    @Test
    void 不抓_不支持持仓的类型() {
        var r = svc(List.of(ev("-40000.00", 12, 0)), List.of(flow("40000.00", 11, 0)),
                AccountType.PROPERTY, holdings()).scan(FAM);
        assertThat(r.findings()).isEmpty();
    }

    /** 一分钱的舍入不该报警 —— 容差走管理页那个此前「存了没人读」的 unexplained_epsilon。 */
    @Test
    void 不抓_容差之内的零头() {
        var r = svc(List.of(ev("-40000.005", 12, 0)), List.of(flow("40000.00", 11, 0)),
                AccountType.WEALTH, holdings()).scan(FAM);
        assertThat(r.findings()).hasSize(1);   // 0.005 在 0.01 容差内 → 仍算精确相消
        var r2 = svc(List.of(ev("-39000.00", 12, 0)), List.of(flow("40000.00", 11, 0)),
                AccountType.WEALTH, holdings()).scan(FAM);
        assertThat(r2.findings()).as("差了 1000,不是精确相消 → 不该当成被抹").isEmpty();
    }
}
