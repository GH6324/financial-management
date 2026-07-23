package com.family.finance.calc.lens;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * v1.1 · 维度 / 度量注册表 · tech-design v1.1 决策 2。
 *
 * <p><b>加维度 / 度量只在这里登记一处</b>,网关、旭日、透视表、自定义构建器、看板全部自动支持
 * (承 feedback_enum_add_sweep「一处网住整类」)。维度取值 = Position 字段 getter(标签已由
 * LensQueryService 算好);holdingLevel=true 的维度会把持仓账户拆开 → 账户级收益度量在该类
 * 维度下不可精确归因(见 {@link PivotEngine} 规则)。</p>
 */
public final class LensRegistry {
    private LensRegistry() {}

    /** 维度定义:key(spec 用)· 中文 label · 是否持仓级 · 取值 */
    public record Dimension(String key, String label, boolean holdingLevel, Function<Position, String> extract) {}

    /** 度量定义:key · 中文 label · 语义由 PivotEngine 按 key 实现(金额求和 / 占比派生 / 收益归因规则) */
    public record Measure(String key, String label) {}

    public static final Map<String, Dimension> DIMENSIONS = new LinkedHashMap<>();
    public static final Map<String, Measure> MEASURES = new LinkedHashMap<>();

    static {
        dim("risk",       "风险",   false, Position::risk);
        dim("assetClass", "资产类型", false, Position::assetClass);
        dim("industry",   "行业",   true,  Position::industry);
        dim("platform",   "平台",   false, Position::platform);
        dim("type",       "账户类型", false, Position::type);
        dim("owner",      "主理人", false, Position::owner);
        dim("purpose",    "用途",   false, Position::purpose);
        dim("region",     "地域",   true,  Position::region);
        dim("currency",   "币种",   false, Position::currency);
        dim("liquidity",  "流动性", false, Position::liquidity);

        measure("value",       "总资产");
        measure("netPrincipal","净投入");
        measure("share",       "占比");
        measure("latestPnl",   "本期收益额");
        measure("latestReturn","本期收益率");
        measure("cumPnl",      "累计收益额");
        measure("cumReturn",   "累计收益率");
    }

    private static void dim(String key, String label, boolean holdingLevel, Function<Position, String> f) {
        DIMENSIONS.put(key, new Dimension(key, label, holdingLevel, f));
    }

    private static void measure(String key, String label) {
        MEASURES.put(key, new Measure(key, label));
    }

    public static Dimension requireDim(String key) {
        Dimension d = DIMENSIONS.get(key);
        if (d == null) throw new IllegalArgumentException("未知维度: " + key);
        return d;
    }

    public static Measure requireMeasure(String key) {
        Measure m = MEASURES.get(key);
        if (m == null) throw new IllegalArgumentException("未知度量: " + key);
        return m;
    }
}
