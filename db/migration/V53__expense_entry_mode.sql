-- v1.8 · 支出链路改造:支出录入方式开关 + 类目显示名
--
-- 兼容红线(memory feedback_prod_backward_compat):
--   · 只 ADD COLUMN 且带默认值 → 老 jar 回滚后忽略该列,所有家庭行为退回「总额」模式
--   · 不迁移 period_member_cashflow.total_expense_input,一行不动
--   · 类目只改 display_name,code 保持 'consumption' → 历史 cash_flow 行零影响,立刻显示新名字

ALTER TABLE family
  ADD COLUMN expense_entry_mode VARCHAR(16) NOT NULL DEFAULT 'TOTAL'
  COMMENT 'TOTAL=每人一个月一个总数(现状) / ITEMIZED=逐笔录入并落到账户';

-- 「消费」→「日常开支」(用户拍板:类目停在性质层,不做餐饮这类消费品类)
UPDATE cash_flow_category SET display_name = '日常开支' WHERE code = 'consumption';
