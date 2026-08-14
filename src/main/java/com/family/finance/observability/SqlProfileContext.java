package com.family.finance.observability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.12 FR-351 · 单次请求内的 SQL 归因累加器(mapper 方法 → 次数 / 耗时)。
 *
 * <p><b>为什么是 ThreadLocal 而不是请求属性</b>:发 SQL 的是
 * {@link SqlCountInterceptor}(MyBatis 层),它拿不到 {@code HttpServletRequest} ——
 * MyBatis 的 {@code Invocation} 里只有 {@code MappedStatement} 和参数。要把「谁发的」
 * 和「哪个请求」缝起来,唯一不侵入 mapper 签名的接缝就是线程。Servlet 容器一个请求一个线程
 * (本项目无 WebFlux、无 {@code @Async} 渲染),这个假设成立。</p>
 *
 * <p><b>清理是硬要求</b>:Tomcat 线程池复用线程,不清就会串账 —— 下一个请求会看到上一个请求的
 * 计数,而且是<b>越滚越大</b>的假数据。所以 {@link #clear()} 必须在
 * {@code afterCompletion} 的 {@code finally} 里调,不能只在正常路径调。</p>
 *
 * <p><b>关闭时的开销</b>:{@link #active()} 是一次 ThreadLocal 读 + 一次 null 判断。
 * 这不是「零开销」(tech-design §5 已经把 PRD 里那句话改掉了),但它在 MyBatis 本来就有的
 * interceptor 链上,量级是噪声。诚实说法:关闭时不进入统计路径。</p>
 */
public final class SqlProfileContext {

    private SqlProfileContext() {}

    /** 一个 mapper 方法的累计:次数 + 总耗时(纳秒)。 */
    public record Stat(String statementId, int count, long totalNanos) {
        public long totalMillis() { return totalNanos / 1_000_000L; }
    }

    /** 可变累加桶(只在持有它的那个线程里改,不需要同步)。 */
    private static final class Bucket {
        int count;
        long nanos;
    }

    private static final ThreadLocal<Map<String, Bucket>> HOLDER = new ThreadLocal<>();

    /** 开始统计(幂等:重复调用会重置)。只有 web 层判定开关为 on 时才调。 */
    public static void start() {
        HOLDER.set(new HashMap<>());
    }

    /** 当前线程是否在统计中 —— MyBatis 拦截器的快速出口。 */
    public static boolean active() {
        return HOLDER.get() != null;
    }

    /** 记一次 mapper 调用。未开启时静默丢弃(不应该被调到,防御性)。 */
    public static void record(String statementId, long nanos) {
        Map<String, Bucket> m = HOLDER.get();
        if (m == null) return;
        Bucket b = m.computeIfAbsent(statementId, k -> new Bucket());
        b.count++;
        b.nanos += nanos;
    }

    /** 本次请求的 SQL 总条数(0 = 没统计到 / 未开启)。 */
    public static int totalCount() {
        Map<String, Bucket> m = HOLDER.get();
        if (m == null) return 0;
        int n = 0;
        for (Bucket b : m.values()) n += b.count;
        return n;
    }

    /**
     * 快照 · 按<b>次数</b>降序(次数相同按耗时降序)。
     *
     * <p>排序键刻意选次数而不是耗时:FR-351 要找的是 N+1(同一个方法被调了几百次),
     * 而 N+1 的单次耗时往往极小 —— 按耗时排会把它排到一条慢查询后面,正好藏住要找的东西。</p>
     */
    public static List<Stat> snapshotSorted() {
        Map<String, Bucket> m = HOLDER.get();
        if (m == null) return List.of();
        List<Stat> out = new ArrayList<>(m.size());
        m.forEach((id, b) -> out.add(new Stat(id, b.count, b.nanos)));
        out.sort(Comparator.comparingInt(Stat::count).reversed()
                .thenComparing(Comparator.comparingLong(Stat::totalNanos).reversed()));
        return out;
    }

    /** 清理 —— 必须在请求收尾的 finally 里调,否则线程池复用会串账。 */
    public static void clear() {
        HOLDER.remove();
    }
}
