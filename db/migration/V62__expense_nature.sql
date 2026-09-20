-- v1.24 · 支出分析:性质(刚性/弹性/一次性)+ 单笔「这笔是一次性的」覆盖。
--
-- 两条 ALTER 都是【只加不改】:零 MODIFY、零 DROP、零既有行重写。
-- 回滚 = 回 jar,数据一个字节不用动:一列可空、一列有默认值,
-- 老 jar 的显式列 INSERT 不含它们,照常跑(联动链 L7)。

-- FR-610 · 类目的支出性质。
-- NULL = 继承父级;父级也 NULL = 按弹性读(FR-616)。
-- 所以「UPDATE 只跑了一半」的最坏情况是【退化成上一版行为】,不是算错。
ALTER TABLE expense_category
  ADD COLUMN expense_nature VARCHAR(8) NULL
  COMMENT 'RIGID=刚性 / FLEX=弹性 / ONE_OFF=一次性;NULL=继承父级,父级也空则按弹性';

-- FR-613 · 单笔「这笔是一次性的」覆盖。
--
-- 【纯分析期标记】:不参与余额 / 轧差 / NAV 任何一条链。
-- affects_balance 有过「一个标记要同时退出三条链」的教训(联动链 L13 硬约束⑤),
-- one_off 不重蹈:它只决定这笔进不进「常态月均」,一分钱的语义都不碰。
-- 护栏 v1240-ONEOFF-NOT-BALANCE 守这条。
ALTER TABLE cash_flow
  ADD COLUMN one_off TINYINT(1) NOT NULL DEFAULT 0
  COMMENT '1=这笔是一次性支出,不进常态月均;只影响分析,不影响任何金额语义';

-- FR-611 · 起步包 10 个大类按名字批量填。
--
-- 按【名字】填,不按 id:改过名的类目填不上 —— 这是对的,它已经不是起步包那一类了,
-- 它的性质该由用户自己定,而不是被一条迁移替他决定。
-- 三条 UPDATE 各自幂等(按名字 + 固定值),重跑无副作用。
UPDATE expense_category SET expense_nature = 'RIGID'
 WHERE parent_id IS NULL AND name IN ('住房物业','充值缴费','教育培训','医疗健康');

UPDATE expense_category SET expense_nature = 'ONE_OFF'
 WHERE parent_id IS NULL AND name = '数码电器';

UPDATE expense_category SET expense_nature = 'FLEX'
 WHERE parent_id IS NULL AND name IN ('餐饮美食','日用百货','服饰装扮','交通出行','文化休闲');
