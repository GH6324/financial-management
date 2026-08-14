package com.family.finance.factview;

import com.family.finance.domain.family.Family;
import com.family.finance.repository.PeriodMemberCashflowMapper.SinglePeriodAggregate;
import com.family.finance.service.expense.ExpenseLedgerService.PeriodExpense;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * v1.12 FR-352 · 一次 GET 请求内的「事实切片 + 按期取数」缓存。
 *
 * <h3>要解决的是什么</h3>
 * FR-351 的归因清单摆明了三件事(beta 实测 · 报表页一次请求 586 条 SQL / DB 2320 ms):
 * <ul>
 *   <li>{@code FactMapper.queryBase} 被执行 <b>10 次</b>,占掉 1413 ms —— 一次请求里同一个筛选
 *       被反复加载(HouseholdCashflowService 的 4 个公开方法各自 {@code loadDefault} 一遍)。</li>
 *   <li>{@code FamilyMapper.findById} <b>194 次</b> —— {@code baseToViewFactor} 每调一次查一次家庭。</li>
 *   <li>{@code findFamilyAggregateForPeriod} <b>180 次</b> / {@code sumRealExpenseByPeriod}
 *       <b>101 次</b> —— 逐期点查(净流入按期算,每期两条)。</li>
 * </ul>
 * 这些查询在一次请求内<b>结果必然相同</b>(GET 不写库),所以缓存的是「同一请求内的重复取数」,
 * 不是「跨请求的业务缓存」。
 *
 * <h3>为什么只给 GET 请求做切片记忆</h3>
 * 写请求里「先写后读」必须读到新值。POST 改了余额/收支再算一遍指标,这时候拿旧切片就是<b>静默算错</b>。
 * 而重复加载的痛点全在 GET 渲染路径上(仪表盘/报表/体检),所以边界就划在方法上:
 * <ul>
 *   <li><b>GET 请求</b>:缓存挂在请求属性上({@link #ATTR}),整个请求共用一份,请求结束随之消失
 *       —— 不占用 Tomcat 线程池的常驻内存。切片本身也记忆({@code memoSlices = true})。</li>
 *   <li><b>其它请求 / 非 Web 线程(cron、启动任务)</b>:每次 {@code load()} 新建一份、只在这次
 *       load 的后续计算里有效,<b>不记忆切片</b>。等于「v1.12 之前的行为 + 一次 load 内的按期批量」。</li>
 * </ul>
 *
 * <h3>为什么按 (familyId, periodId) 做 key</h3>
 * 一次页面请求只有一个家庭,但**管理页手动触发定时任务**那类请求会在同一个请求里循环所有家庭。
 * 全部按家庭区分 key,这类路径就结构上不可能串账 —— 比"约定只有一个家庭"可靠。
 *
 * <h3>缺 key 与值为 null 是两件事</h3>
 * {@code pmc} / {@code expense} 用 {@link HashMap} 而不是 {@code Optional} 包装:
 * <b>{@code containsKey} = 查过了</b>,值为 {@code null} = 查过且该期没有手填收支。
 * 少了这个区分就没法判断「要不要去查」,会把「查过是空」当成「还没查」,批量的意义就没了。
 *
 * <h3>切片可以共享是有前提的</h3>
 * {@link FactSlice} 的三个列表都是 {@code List.copyOf}/{@code stream().toList()} 的不可变列表,
 * {@link AccountPeriodFact} 是 record —— 所以同一请求内多个调用方拿到同一个实例是安全的。
 * 哪天有人给 FactSlice 加了可变字段,这里的记忆就得跟着重新想。
 */
final class FactLoadCache {

    /** 请求属性 key(GET 请求专用)。 */
    static final String ATTR = FactLoadCache.class.getName();

    /** 按 (家庭, 账期) 定位一期取数结果。 */
    record PeriodKey(long familyId, long periodId) {}

    /** 只有请求级(GET)缓存才记忆切片;load 级缓存只共享按期取数。 */
    private final boolean memoSlices;

    /**
     * 这份缓存<b>属于哪个请求</b>(非 Web 线程为 null)。
     *
     * <p>为什么需要:load 级缓存挂在 ThreadLocal 上,而 ThreadLocal 在请求结束时<b>没人清</b> ——
     * Tomcat 线程复用,下一个请求若不走 {@code load()} 却读了缓存,拿到的就是上一个请求的数据。
     * 读取侧比对 {@code owner == 当前 RequestAttributes}(同一性),不是同一个请求就当没有缓存、
     * 走原查询。这样「跨请求读到旧值」不是靠约定避免,而是结构上不可能。</p>
     */
    private final Object owner;

    final Map<FactFilter, FactSlice> slices = new HashMap<>();
    final Map<Long, Family> families = new HashMap<>();
    /** familyId → (期 → 该期首次出现的账户 id 集合)。见 FactViewServiceImpl#firstAppearingIn */
    final Map<Long, Map<Long, Set<Long>>> firstAppear = new HashMap<>();
    /** 值可为 null = 该期没有手填收支行(与点查返回一行 NULL 合计等价)。 */
    final Map<PeriodKey, SinglePeriodAggregate> pmc = new HashMap<>();
    final Map<PeriodKey, PeriodExpense> expense = new HashMap<>();

    FactLoadCache(boolean memoSlices, Object owner) {
        this.memoSlices = memoSlices;
        this.owner = owner;
    }

    boolean memoSlices() {
        return memoSlices;
    }

    boolean ownedBy(Object requestAttributes) {
        return owner == requestAttributes;
    }
}
