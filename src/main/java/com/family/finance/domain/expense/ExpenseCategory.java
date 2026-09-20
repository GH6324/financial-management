package com.family.finance.domain.expense;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v1.21 · 支出类目 —— <b>一棵两层树</b>的一个节点。
 *
 * <p>{@code parentId == null} = 一级(大类);非 null = 挂在该大类下的二级(细类)。
 * <b>层级封顶两层</b>,不做三层 —— 我们的粒度是「月度汇总」,逐笔软件才需要更深的树来收纳几百条流水
 * (调研:随手记默认树把餐饮拆成早/午/晚餐,用户反馈过细)。</p>
 *
 * <h3>「简单版 / 复杂版」不是两棵树</h3>
 *
 * <p>是<b>同一棵树的两个深度</b>:简单版只用一级,复杂版用到二级,而复杂版的一级与简单版<b>完全相同</b>。
 * 于是「两版互相映射」<b>不需要映射表</b> —— 映射就是这里的 {@code parentId} 这条父子边,
 * 天生存在、永不漂移。切换深度只改「以后怎么填」,历史行一行不动。</p>
 *
 * <h3>名字是展示属性,不是数据身份</h3>
 *
 * <p>改名之后历史数据跟着新名字显示 —— 因为 {@code expense_split} 引用的是 {@code id} 而非名字。
 * 这也是不用「物化路径」表示树的原因:那样改名要级联改 path。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseCategory {

    /** 每家一条的兜底类目。不可删、不可停用、不可改名 —— 删除语义的最终落点。 */
    public static final String SYSTEM_OTHER = "OTHER";

    private Long id;
    private Long familyId;
    private Long parentId;
    private String name;
    private String systemCode;
    private Integer sortOrder;

    /**
     * v1.24 FR-610 · 这一类的支出性质。
     *
     * <p><b>可空,而且空是有意义的</b>:NULL = 继承父级;父级也空 = 按弹性读(FR-616)。
     * 别在这里做兜底 —— 兜底只在 {@code ExpenseNatureService.natureOf} 一处,
     * 两处兜底就会有两套规则(护栏 v1240-ONEOFF-SINGLE-JUDGE)。</p>
     */
    private String expenseNature;

    private LocalDateTime archivedAt;
    private LocalDateTime createdAt;

    public boolean isTopLevel() { return parentId == null; }

    public boolean isOther() { return SYSTEM_OTHER.equals(systemCode); }

    public boolean isArchived() { return archivedAt != null; }
}
