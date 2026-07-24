-- V52 · 账户模板补「平台」默认值(v1.5.2 · 打标维度一致性)
-- 背景:打标维度「平台」= 持有该账户的机构 / App(招商银行 / 支付宝 / 蚂蚁财富…),
--       原为账户级自由文本、建户后需手标或靠 AI 建议,导致「平台」常与真实账户不一致(用户 item6 反馈)。
-- 方案:给账户模板加一个 nullable 的 platform 默认值;建户时若用户未填,自动带出该模板的平台 → 默认即一致。
-- 兼容:附加 nullable 列 + 仅对「平台明确」的模板 seed 值;通用/因人而异的模板(信用卡通用、证券通用、
--       银行理财、房产、贷款、加密、贵金属、保险、自定义)留 NULL,不臆测。存量账户不受影响。

ALTER TABLE account_template ADD COLUMN platform VARCHAR(60) NULL AFTER icon;

-- 平台可唯一确定的模板(按 code 精确更新,幂等)
UPDATE account_template SET platform = '招商银行' WHERE code = 'cmb_savings';
UPDATE account_template SET platform = '工商银行' WHERE code = 'icbc_savings';
UPDATE account_template SET platform = '建设银行' WHERE code = 'ccb_savings';
UPDATE account_template SET platform = '中国银行' WHERE code = 'boc_savings';
UPDATE account_template SET platform = '支付宝'   WHERE code = 'alipay';
UPDATE account_template SET platform = '微信'     WHERE code = 'wechat_pay';
UPDATE account_template SET platform = '蚂蚁财富' WHERE code = 'ant_fortune';
