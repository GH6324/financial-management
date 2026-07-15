package com.family.finance.domain.lens;

/**
 * v1.1 · 行业/主题维度(资产透视)· prd/v1.1 FR-1 · 已拍板 D3=粗分。
 *
 * <p><b>17 个家庭友好粗行业</b>(v1.1 评审后从 12 扩充:补电商零售/半导体/汽车出行/游戏传媒/能源化工)(非 GICS 全集,防选择过载)。账户级粗标(基金/理财 · 近似)
 * 与个股持仓级细标(准)共用本枚举;存 {@link #name()},DB 不加 CHECK(加值免迁移)。
 * 未打标 = 「未分类」照常参与透视,不做基金成分穿透、不给假精确。</p>
 */
public enum IndustryTag {
    BROAD_INDEX("宽基综合"),
    CONSUMER("白酒消费"),
    TECH_INTERNET("科技互联网"),
    ECOMMERCE_RETAIL("电商零售"),
    SEMICONDUCTOR("半导体芯片"),
    AUTO_MOBILITY("汽车出行"),
    GAME_MEDIA("游戏传媒"),
    ENERGY_CHEM("能源化工"),
    NEW_ENERGY("新能源电力"),
    HEALTHCARE("医药"),
    FINANCE_ESTATE("金融地产"),
    MANUFACTURING("制造军工"),
    FIXED_BOND("固收债"),
    METAL_COMMODITY("贵金属商品"),
    CRYPTO_ASSET("加密"),
    OVERSEAS("海外市场"),
    OTHER("其他");

    private final String label;

    IndustryTag(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** 安全解析 · 非法/空返回 null(脏值不抛) */
    public static IndustryTag fromName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** name → 中文 label · 模板/JSON 用 */
    public static String labelOf(String name) {
        IndustryTag t = fromName(name);
        return t == null ? "" : t.getLabel();
    }
}
