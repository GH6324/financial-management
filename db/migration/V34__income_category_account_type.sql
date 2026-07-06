-- v0.12 · 收入类目绑定账户类型(FR-143/147)+ 新增股票类收入类目
-- backward-compat:全 ADD COLUMN NULL + 新行,对线上现有 cash_flow / snapshot 0 破坏。

ALTER TABLE cash_flow_category
    ADD COLUMN account_type VARCHAR(16) NULL;   -- 该收入类目绑定的账户类型(CASH/STOCK/...);NULL=不限

-- 现有 INCOME 类目回填绑定(现金类)
UPDATE cash_flow_category SET account_type = 'CASH'
 WHERE code IN ('salary', 'bonus', 'interest_income');

-- 收入类目排序:工资(10)→ 薪资-股票(20,见下 INSERT)→ 股息(30)→ 卖出回款(40)→ 奖金/利息/其他
UPDATE cash_flow_category SET sort_order = 10 WHERE code = 'salary';
UPDATE cash_flow_category SET sort_order = 50 WHERE code = 'bonus';
UPDATE cash_flow_category SET sort_order = 60 WHERE code = 'interest_income';
UPDATE cash_flow_category SET sort_order = 70 WHERE code = 'other_income';

-- 新增股票类收入类目(薪资-股票 / 股息·分红 / 卖出回款·赎回)
INSERT INTO cash_flow_category (code, display_name, kind, sort_order, account_type) VALUES
    ('stock_salary', '薪资-股票',        'INCOME', 20, 'STOCK'),
    ('dividend',     '股息 / 分红',      'INCOME', 30, 'STOCK'),
    ('stock_sell',   '卖出回款 / 赎回',  'INCOME', 40, 'STOCK')
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    account_type = VALUES(account_type),
    sort_order   = VALUES(sort_order);
