package com.family.finance.service.penetration;

import com.family.finance.domain.lens.IndustryTag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** v1.5 · 穿透纯函数单测(不联网):归一化 / 名归一 / 东财行业映射。 */
class FundPenetrationUnitTest {

    private final EastMoneyFundClient client = new EastMoneyFundClient(new RestTemplateBuilder());

    @Test
    void normName_strips_suffix_bracket_alias() {
        assertEquals("兴全合润混合", EastMoneyFundClient.normName("兴全合润混合(LOF)A"));
        assertEquals("交银中证海外中国互联网指数", EastMoneyFundClient.normName("交银施罗德中证海外中国互联网指数(QDII-LOF)A"));
        assertEquals("易方达龙宝货币", EastMoneyFundClient.normName("易方达龙宝货币A"));
    }

    @Test
    void mapIndustry_keyword_to_swan_level1() {
        assertEquals(IndustryTag.FOOD_BEVERAGE, client.mapIndustry("白酒Ⅱ"));
        assertEquals(IndustryTag.HOME_APPLIANCE, client.mapIndustry("白色家电"));
        assertEquals(IndustryTag.SEMICONDUCTOR, client.mapIndustry("半导体"));
        assertEquals(IndustryTag.BANK, client.mapIndustry("股份制银行Ⅱ"));
        assertEquals(IndustryTag.NONBANK_FIN, client.mapIndustry("证券Ⅱ"));
        assertEquals(IndustryTag.AUTO_MOBILITY, client.mapIndustry("乘用车"));
        assertEquals(IndustryTag.PHARMA_BIO, client.mapIndustry("化学制药"));
        assertEquals(IndustryTag.OTHER, client.mapIndustry(""));
        assertEquals(IndustryTag.OTHER, client.mapIndustry(null));
    }

    @Test
    void scaleTo_sums_to_target_exactly() {
        List<FundPenetrationService.AllocPart> parts = List.of(
                new FundPenetrationService.AllocPart("STOCK", "EQUITY", "SEMICONDUCTOR", 2650),
                new FundPenetrationService.AllocPart("STOCK", "EQUITY", "ELECTRONICS", 1110),
                new FundPenetrationService.AllocPart("BOND", "FIXED_INCOME", "FIXED_BOND", 500),
                new FundPenetrationService.AllocPart("CASH", "CASH_EQ", "MONEY_CASH", 460));
        var norm = FundPenetrationService.normalize(parts);
        int sum = norm.stream().mapToInt(FundPenetrationService.AllocPart::weightBp).sum();
        assertEquals(10000, sum, "归一化后合计必须恰好 10000 万分比");
    }

    @Test
    void scaleTo_respects_manual_budget() {
        List<FundPenetrationService.AllocPart> parts = List.of(
                new FundPenetrationService.AllocPart("STOCK", "EQUITY", "FOOD_BEVERAGE", 6000),
                new FundPenetrationService.AllocPart("CASH", "CASH_EQ", "MONEY_CASH", 4000));
        var scaled = FundPenetrationService.scaleTo(parts, 8000);   // MANUAL 已占 2000
        int sum = scaled.stream().mapToInt(FundPenetrationService.AllocPart::weightBp).sum();
        assertEquals(8000, sum);
    }
}
