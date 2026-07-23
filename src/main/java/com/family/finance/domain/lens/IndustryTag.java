package com.family.finance.domain.lens;

/**
 * v1.1 · 行业/主题维度(资产透视)· prd/v1.1 FR-1 · 已拍板 D3=粗分 · v1.1.x 修订(2026-07-16 拍板)。
 *
 * <p><b>18 个家庭友好粗行业 · 「投向」语义</b>(非 GICS 全集,防选择过载)。修订史:12 → 17(补电商/半导体/汽车/游戏/能化)
 * → 18(新增 货币现金;金融地产拆分;删 海外市场)→ 20(2026-07-17 按 prod 真实数据+业界常见持仓复盘:
 * 货币现金→「货币基金/存款」更直白;新增 混合配置(固收+/FOF/目标日期,家庭理财大宗,标固收债不诚实)+ 红利公用(长江电力/中证红利/水电燃气))。
 * 账户级粗标(基金/理财 · 近似)与个股持仓级细标(准)共用本枚举;存 {@link #name()},DB 不加 CHECK(加值免迁移;
 * 删值/改 code 需迁移,见 V47)。未打标 = 「未分类」照常参与透视,不做基金成分穿透、不给假精确。</p>
 */
public enum IndustryTag {
    BROAD_INDEX("宽基综合", "kuan ji zong he"),
    MONEY_CASH("货币基金/存款", "huo bi ji jin cun kuan"),
    FIXED_BOND("固收债", "gu shou zhai"),
    MIXED_ALLOC("混合配置", "hun he pei zhi"),
    CONSUMER("消费食饮", "xiao fei shi yin"),
    TECH_INTERNET("科技互联网", "ke ji hu lian wang"),
    ECOMMERCE_RETAIL("电商零售", "dian shang ling shou"),
    SEMICONDUCTOR("半导体芯片", "ban dao ti xin pian"),
    AUTO_MOBILITY("汽车出行", "qi che chu xing"),
    GAME_MEDIA("游戏传媒", "you xi chuan mei"),
    ENERGY_CHEM("能源化工", "neng yuan hua gong"),
    NEW_ENERGY("新能源电力", "xin neng yuan dian li"),
    HEALTHCARE("医药健康", "yi yao jian kang"),
    FINANCE("银行券商保险", "yin hang quan shang bao xian"),
    ESTATE_CONSTRUCTION("地产建筑", "di chan jian zhu"),
    MANUFACTURING("制造军工", "zhi zao jun gong"),
    DIVIDEND_UTIL("红利公用", "hong li gong yong"),
    METAL_COMMODITY("贵金属商品", "gui jin shu shang pin"),
    CRYPTO_ASSET("加密", "jia mi"),
    // v1.5 · 基金穿透扩容:对齐申万一级细行业(东财 f127 细行业 → 这些)· 旧粗值保留兼容,新值细到家电/白酒
    HOME_APPLIANCE("家用电器", "jia yong dian qi"),
    FOOD_BEVERAGE("食品饮料", "shi pin yin liao"),
    ELECTRONICS("电子", "dian zi"),
    POWER_EQUIP("电力设备", "dian li she bei"),
    MACHINERY("机械设备", "ji xie she bei"),
    NONFERROUS("有色金属", "you se jin shu"),
    CHEMICALS("基础化工", "ji chu hua gong"),
    STEEL("钢铁", "gang tie"),
    COAL("煤炭", "mei tan"),
    PETROCHEM("石油石化", "shi you shi hua"),
    BANK("银行", "yin hang"),
    NONBANK_FIN("非银金融", "fei yin jin rong"),
    REAL_ESTATE_IND("房地产", "fang di chan"),
    CONSTRUCTION("建筑", "jian zhu"),
    AGRICULTURE("农林牧渔", "nong lin mu yu"),
    TEXTILE("纺织服饰", "fang zhi fu shi"),
    LIGHT_MFG("轻工制造", "qing gong zhi zao"),
    TRANSPORT("交通运输", "jiao tong yun shu"),
    UTILITIES("公用事业", "gong yong shi ye"),
    TELECOM("通信", "tong xin"),
    COMPUTER("计算机", "ji suan ji"),
    MEDIA("传媒", "chuan mei"),
    DEFENSE("国防军工", "guo fang jun gong"),
    ENVIRONMENT("环保", "huan bao"),
    BEAUTY_CARE("美容护理", "mei rong hu li"),
    SOCIAL_SVC("社会服务", "she hui fu wu"),
    COMMERCE_RETAIL("商贸零售", "shang mao ling shou"),
    PHARMA_BIO("医药生物", "yi yao sheng wu"),
    CONGLOMERATE("综合", "zong he"),
    OTHER("其他", "qi ta");

    private final String label;
    private final String pinyin;   // 全拼(空格分节)· 自研下拉搜索用,首字母由分节推导

    IndustryTag(String label, String pinyin) { this.label = label; this.pinyin = pinyin; }

    public String getLabel() { return label; }

    public String getPinyin() { return pinyin; }

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
