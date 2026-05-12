package com.family.finance.domain.goal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 目标 params_json 的类型化包装 · 三类目标共用一个 POJO(NULL 字段按 type 决定使用范围)。
 *
 * <p>Service 层用 Jackson 反序列化 Goal.paramsJson → GoalParams,然后按 GoalType 取相应字段。</p>
 *
 * <p>未知字段自动忽略(forward-compat:未来新加字段不破坏老 Goal)。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoalParams {

    // ---------- RETIREMENT ----------
    @JsonProperty("retire_age")
    private Integer retireAge;

    @JsonProperty("current_age")
    private Integer currentAge;

    @JsonProperty("monthly_expense")
    private BigDecimal monthlyExpense;

    @JsonProperty("inflation_rate")
    private BigDecimal inflationRate;

    @JsonProperty("withdrawal_rate")
    private BigDecimal withdrawalRate;

    // ---------- EDUCATION ----------
    @JsonProperty("child_member_id")
    private Long childMemberId;

    @JsonProperty("child_birth_year")
    private Integer childBirthYear;

    @JsonProperty("target_year_offset")
    private Integer targetYearOffset;

    @JsonProperty("target_amount")
    private BigDecimal targetAmount;

    // ---------- EMERGENCY ----------
    @JsonProperty("months_target")
    private Integer monthsTarget;

    @JsonProperty("auto_baseline")
    private Boolean autoBaseline;

    @JsonProperty("fixed_baseline")
    private BigDecimal fixedBaseline;
}
