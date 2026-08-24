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
 * <ol start="3">
 *   <li>v1.18.6 · 「某次估值 Δ 恰好抵消刚进出的钱」—— <b>抓到了不该动的</b>。生产上有一格命中,
 *       但那是用户转账后立刻重导持仓截图(导入如实还原了转账前的持仓、于是相消),而
 *       <b>8 天后的又一次导入已经把账做平</b>。判据只看那一个瞬间,照样报「需要补回 12.5w」——
 *       <b>照着补就是凭空删掉真实存在的钱</b>。修法不是改判据,是加<b>第二视角</b>
 *       (整期是否自洽)并把措辞降级成「疑似 · 请核对」。</li>
 * </ol>
 * <p>所以本测的每一条都成对写:<b>该抓的抓到</b> + <b>不该抓的别抓</b>;
 * v1.18.6 起再加一对:<b>该判「确定」的判确定</b> + <b>该判「存疑」的别当成确定</b>。
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
        return svc(events, flows, type, holdings, null);
    }

    /** balances = null 表示「查不到期初/期末」—— 那时第二视角失效,stillMissing 必须是 false。 */
    private ReconciliationScanService svc(List<ReconEvent> events, List<ReconFlow> flows, AccountType type,
                                          List<StockHolding> holdings,
                                          StockValuationEventMapper.ReconBalance balance) {
        when(eventMapper.findBalancesForReconcile(anyLong()))
                .thenReturn(balance == null ? List.of() : List.of(balance));
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

    // ────────────────────────────────────────────────────────────────
    // v1.18.6 · 第二视角:整期是否自洽
    //
    // 前两版的毛病是【抓不到】,这一版的毛病是【抓到了不该动的】。生产上有一格命中了时间线判据,
    // 但真相是用户转账后立刻重导了一次持仓截图(导入如实还原了转账前的持仓、于是与转账相消),
    // 而 8 天后的又一次导入已经把余额纠正了。判据只看那一个瞬间,照样报「需要补回」——
    // 照着补就是【凭空删掉真实存在的钱】,比漏报危险得多。
    //
    // 下面这组把两种情形分开钉住。判据本身不变(仍会命中),变的是【结论的确定性】。
    // ────────────────────────────────────────────────────────────────

    private static StockValuationEventMapper.ReconBalance bal(String prevEnd, String end) {
        return new StockValuationEventMapper.ReconBalance(ACC, PERIOD,
                new BigDecimal(end), new BigDecimal(prevEnd));
    }

    /**
     * 钱一直没回来:净流入 40,000、余额一分没涨 → 隐含损益 = −40,000,正好背着这个缺口,
     * 残留 = 0 → <b>期末仍对不上</b>,这是真要动手的那种。
     */
    @Test
    void 第二视角_钱至今没回来_标为期末仍对不上() {
        var r = svc(List.of(ev("-40000.00", 12, 0)), List.of(flow("40000.00", 11, 0)),
                AccountType.WEALTH, holdings(), bal("500000.00", "500000.00")).scan(FAM);
        assertThat(r.findings()).hasSize(1);
        var f = r.findings().get(0);
        assertThat(f.impliedPnl()).isEqualByComparingTo("-40000.00");
        assertThat(f.residual()).isEqualByComparingTo("0.00");
        assertThat(f.stillMissing()).isTrue();
    }

    /**
     * <b>生产上那次误报的形状</b>:同样命中了时间线判据,但期末余额后来被重新导入纠正过 ——
     * 净流入 40,000、余额涨了 37,000(= 40,000 流入 − 3,000 真实回撤),
     * 隐含损益 = −3,000 看着就是正常波动,残留 = 37,000 ≠ 0。
     *
     * <p>这一条<b>必须落在「需人工核对」</b>。要是它被判成「期末仍对不上」,
     * 维护者照着补 40,000 就是凭空造钱 —— 这正是 v1.18.6 要堵的那个方向。</p>
     */
    @Test
    void 第二视角_后来已被纠正_只标需人工核对_不许当成要补的钱() {
        var r = svc(List.of(ev("-40000.00", 12, 0)), List.of(flow("40000.00", 11, 0)),
                AccountType.WEALTH, holdings(), bal("500000.00", "537000.00")).scan(FAM);
        assertThat(r.findings()).hasSize(1);
        var f = r.findings().get(0);
        assertThat(f.impliedPnl()).as("隐含损益看着像正常涨跌,而不是背着 40,000 的缺口")
                .isEqualByComparingTo("-3000.00");
        assertThat(f.residual()).isEqualByComparingTo("37000.00");
        assertThat(f.stillMissing())
                .as("期末余额已被改动过 → 只能提示核对。判成「仍对不上」会让人照着删/补真实存在的钱")
                .isFalse();
    }

    /**
     * 钱补回来之后,同一条历史痕迹要<b>自动降级</b>。
     *
     * <p>这就是「已处理」标记的替代方案:不做人手打的标记 —— 那玩意会和数据分家,
     * 而「同一件事两份判据」正是这一整个 bug 家族的形状。让<b>数据自己说</b>。</p>
     */
    @Test
    void 第二视角_补回之后同一条痕迹自动降级() {
        var events = List.of(ev("-40000.00", 12, 0));
        var flows = List.of(flow("40000.00", 11, 0));
        // 补之前:余额没动
        assertThat(svc(events, flows, AccountType.WEALTH, holdings(), bal("500000.00", "500000.00"))
                .scan(FAM).findings().get(0).stillMissing()).isTrue();
        // 补之后:现金行补上 40,000 → 期末余额跟上了
        assertThat(svc(events, flows, AccountType.WEALTH, holdings(), bal("500000.00", "540000.00"))
                .scan(FAM).findings().get(0).stillMissing())
                .as("钱已经补回来了,这条历史痕迹不该再催人补第二次").isFalse();
    }

    /**
     * 查不到期初(比如账户建仓的第一期)时,第二视角<b>算不出来</b> ——
     * 那就老实返回 null + false,不许拿 0 冒充「算过了」。
     *
     * <p>把缺失当成 0 会让 residual 恰好等于 missing、进而随机地判成两边中的一边 ——
     * 而页面上看不出这个数是"算出来的"还是"编出来的"。宁可显示「—」。</p>
     */
    @Test
    void 第二视角_期初缺失时不许编数() {
        var r = svc(List.of(ev("-40000.00", 12, 0)), List.of(flow("40000.00", 11, 0)),
                AccountType.WEALTH, holdings(), null).scan(FAM);
        assertThat(r.findings()).hasSize(1);
        var f = r.findings().get(0);
        assertThat(f.impliedPnl()).isNull();
        assertThat(f.residual()).isNull();
        assertThat(f.balanceChange()).isNull();
        assertThat(f.stillMissing()).as("算不出来 ≠ 确定还缺着").isFalse();
    }

    /** 排序:确定要动手的排在前面,别让人照着「可能只是历史痕迹」的那条去删钱。 */
    @Test
    void 第二视角_期末仍对不上的排在前面() {
        // 两个账户:小额但确定 / 大额但存疑。旧排序只按金额,大额存疑的会排第一。
        Account a1 = new Account();
        a1.setId(ACC); a1.setFamilyId(FAM); a1.setDisplayName("确定缺"); a1.setType(AccountType.WEALTH); a1.setCurrency("CNY");
        Account a2 = new Account();
        a2.setId(8L); a2.setFamilyId(FAM); a2.setDisplayName("存疑"); a2.setType(AccountType.WEALTH); a2.setCurrency("CNY");

        Family f = new Family();
        f.setId(FAM); f.setPeriodType(PeriodType.MONTHLY); f.setBaseCurrency("CNY");
        when(familyMapper.findById(anyLong())).thenReturn(java.util.Optional.of(f));
        when(accountMapper.findActiveByFamily(anyLong())).thenReturn(List.of(a1, a2));
        when(holdingMapper.findActiveByAccount(anyLong())).thenReturn(holdings());
        when(configService.getDouble(anyLong(), anyString(), anyDouble())).thenReturn(0.01);
        when(auditMapper.findByFamily(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(eventMapper.findEventsForReconcile(anyLong())).thenReturn(List.of(
                new ReconEvent(ACC, PERIOD, P_START, new BigDecimal("-5000.00"), LocalDateTime.of(2026, 8, 18, 12, 0)),
                new ReconEvent(8L, PERIOD, P_START, new BigDecimal("-90000.00"), LocalDateTime.of(2026, 8, 18, 12, 0))));
        when(eventMapper.findFlowsForReconcile(anyLong())).thenReturn(List.of(
                new ReconFlow(ACC, PERIOD, new BigDecimal("5000.00"), LocalDateTime.of(2026, 8, 18, 11, 0)),
                new ReconFlow(8L, PERIOD, new BigDecimal("90000.00"), LocalDateTime.of(2026, 8, 18, 11, 0))));
        when(eventMapper.findBalancesForReconcile(anyLong())).thenReturn(List.of(
                new StockValuationEventMapper.ReconBalance(ACC, PERIOD,
                        new BigDecimal("100000.00"), new BigDecimal("100000.00")),   // 残留 0 → 确定
                new StockValuationEventMapper.ReconBalance(8L, PERIOD,
                        new BigDecimal("690000.00"), new BigDecimal("600000.00"))));  // 残留 90000 → 存疑

        var r = new ReconciliationScanService(accountMapper, familyMapper, holdingMapper, eventMapper,
                configService, auditMapper).scan(FAM);
        assertThat(r.findings()).hasSize(2);
        assertThat(r.findings().get(0).accountName())
                .as("确定要动手的排第一,哪怕金额小得多").isEqualTo("确定缺");
        assertThat(r.findings().get(0).stillMissing()).isTrue();
        assertThat(r.findings().get(1).stillMissing()).isFalse();
    }
}
