-- =========================================================
-- 家庭账房 v0.1 · 种子数据
-- 1 个 family + 2 个 member + 13 + 1 模板 + 8 现金流类别
-- =========================================================
SET NAMES utf8mb4;

-- ---------------------------------------------------------
-- 1 个家庭
-- ---------------------------------------------------------
INSERT INTO family (id, name, brand_text, base_currency, period_type)
VALUES (1, '我们家', '账房', 'CNY', 'MONTHLY');

-- ---------------------------------------------------------
-- 2 个种子成员
-- password_hash 为 PLACEHOLDER:DevSeedRunner(dev profile)启动时检测并自动设为 bcrypt('demo1234')
-- 生产部署时,请用 admin/members 的"重置密码"功能在屏幕上生成临时密码后告诉对方
-- ---------------------------------------------------------
INSERT INTO member (id, family_id, username, password_hash, display_name, role_label, must_change_pw)
VALUES
  (1, 1, 'zhangwei', 'PLACEHOLDER_REPLACED_BY_DEV_SEED_RUNNER_OR_ADMIN', 'Alice', '丈夫', 1),
  (2, 1, 'lijing',   'PLACEHOLDER_REPLACED_BY_DEV_SEED_RUNNER_OR_ADMIN', 'Bob', '妻子', 1);

-- ---------------------------------------------------------
-- 13 个内置账户模板 + 1 个"自定义账户"占位
-- ---------------------------------------------------------
INSERT INTO account_template (code, display_name, type, default_currency, sort_order, is_custom_slot) VALUES
  ('cmb_savings',     '招商银行',                  'CASH',     'CNY',  1, 0),
  ('icbc_savings',    '工商银行',                  'CASH',     'CNY',  2, 0),
  ('ccb_savings',     '建设银行',                  'CASH',     'CNY',  3, 0),
  ('boc_savings',     '中国银行',                  'CASH',     'CNY',  4, 0),
  ('credit_card',     '信用卡(通用)',             'LOAN',     'CNY',  5, 0),
  ('alipay',          '支付宝(余额+余额宝)',      'CASH',     'CNY',  6, 0),
  ('wechat_pay',      '微信(零钱+零钱通)',        'CASH',     'CNY',  7, 0),
  ('brokerage',       '证券账户(通用)',           'STOCK',    'CNY',  8, 0),
  ('ant_fortune',     '蚂蚁财富 / 京东金融(基金)','WEALTH',   'CNY',  9, 0),
  ('bank_wealth',     '银行理财(R3 以下产品)',    'WEALTH',   'CNY', 10, 0),
  ('property',        '住宅(房产)',               'PROPERTY', 'CNY', 11, 0),
  ('mortgage_loan',   '贷款(房贷/车贷)',          'LOAN',     'CNY', 12, 0),
  ('custom_slot',     '自定义账户',                'OTHER',    'CNY', 99, 1);

-- ---------------------------------------------------------
-- 8 个现金流类别
-- ---------------------------------------------------------
INSERT INTO cash_flow_category (code, display_name, kind, sort_order) VALUES
  ('salary',          '工资',              'INCOME',  1),
  ('bonus',           '奖金',              'INCOME',  2),
  ('other_income',    '其他收入',          'INCOME',  3),
  ('interest_income', '利息收入',          'INCOME',  4),
  ('consumption',     '消费',              'EXPENSE', 10),
  ('loan_payment',    '还贷',              'EXPENSE', 11),
  ('interest_paid',   '利息支出',          'EXPENSE', 12),
  ('to_relatives',    '转账给亲属',        'EXPENSE', 13);
