package com.family.finance.observability;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

/**
 * v1.12 FR-351 · MyBatis 拦截器 · 把每次 mapper 调用归因到<b>方法名</b>。
 *
 * <p>{@code MappedStatement.getId()} = mapper 方法全限定名(如
 * {@code com.family.finance.repository.FactMapper.queryBase})—— 这正是「这条 SQL 是谁发的」
 * 的答案。tech-design §5 因此选它而不是 p6spy:p6spy 给的是 <b>SQL 文本</b>,
 * 761 条里大量是同一条 SQL 反复执行,按文本聚合只能说「这条跑了 400 次」,
 * 还得人肉回代码里找调用点。</p>
 *
 * <p><b>为什么拦 {@code Executor} 而不是 {@code StatementHandler}</b>:
 * {@code Executor.query} 是<b>一级缓存之前</b>的入口,能看到「mapper 方法被调了几次」;
 * {@code StatementHandler} 只看到真正打到 DB 的那些。找 N+1 要的是前者 ——
 * 一个被调 400 次但 399 次命中 session 缓存的方法,依然是需要被批量化掉的循环。</p>
 *
 * <p>两个 {@code query} 签名都要拦:4 参是普通调用,6 参是 MyBatis 内部带
 * {@code CacheKey}/{@code BoundSql} 的重载(开二级缓存 / 嵌套查询时走它)。只拦一个会漏计。</p>
 *
 * <p>被 Spring Boot 的 {@code MybatisAutoConfiguration} 自动装配 —— 容器里任何
 * {@code Interceptor} 类型的 bean 都会被加进 {@code SqlSessionFactory} 的插件链,
 * 不需要额外注册代码。</p>
 */
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class SqlCountInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 关闭时的快速出口:一次 ThreadLocal 读,不进统计路径。
        if (!SqlProfileContext.active()) return invocation.proceed();

        long t0 = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            // finally 记账:抛异常的调用也发生了、也耗了时间,漏掉会让「归因清单」和实际 SQL 数对不上。
            Object[] args = invocation.getArgs();
            String id = (args.length > 0 && args[0] instanceof MappedStatement ms) ? ms.getId() : "<unknown>";
            SqlProfileContext.record(id, System.nanoTime() - t0);
        }
    }
}
