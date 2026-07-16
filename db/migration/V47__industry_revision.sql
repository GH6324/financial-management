-- =========================================================
-- V47 · 行业(投向)清单修订(v1.1.x · 2026-07-16 拍板)
-- =========================================================
-- 枚举 17→18:新增 MONEY_CASH 货币现金;FINANCE_ESTATE 金融地产拆为
--   FINANCE 银行券商保险 + ESTATE_CONSTRUCTION 地产建筑;删 OVERSEAS 海外市场
--   (与地域维度完全重复:标普500 = 宽基综合 × 美股);
--   label 改名(白酒消费→消费食饮 / 医药→医药健康)为 Java 枚举,免迁移。
-- 存量数据迁移(backward-compat:改完老值全部仍可解析,不出现裸 code):
--   FINANCE_ESTATE → FINANCE(存量标金融地产的绝大多数是银行/券商/保险股);
--   OVERSEAS → NULL(未分类;地域信息由 region 维度承载,不造假投向)。

UPDATE account       SET industry_tag = 'FINANCE' WHERE industry_tag = 'FINANCE_ESTATE';
UPDATE stock_holding SET industry_tag = 'FINANCE' WHERE industry_tag = 'FINANCE_ESTATE';

UPDATE account       SET industry_tag = NULL WHERE industry_tag = 'OVERSEAS';
UPDATE stock_holding SET industry_tag = NULL WHERE industry_tag = 'OVERSEAS';
