-- v1.5 · 基金持仓穿透:持仓方向层 + 全局共享穿透缓存 + stock_holding 穿透键
-- 承 v1.4(截图导入)。无穿透持仓不建方向行,lens 回落隐式 100% 单标签 → 老数据零迁移。

-- 1) 持仓方向(融合打标与穿透的落点):一支基金 = N 个方向(股按行业/债/现金)
CREATE TABLE IF NOT EXISTS holding_allocation (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  holding_id    BIGINT NOT NULL,
  weight_bp     INT NOT NULL,                          -- 万分比,同持仓合计 10000
  asset_class   VARCHAR(20) NULL,                      -- EQUITY/FIXED_INCOME/CASH_EQ...(L1 桶)
  industry      VARCHAR(24) NULL,                      -- 行业维值(仅权益有意义)
  kind          VARCHAR(12) NOT NULL DEFAULT 'STOCK',  -- STOCK/BOND/CASH/OTHER
  source        VARCHAR(12) NOT NULL DEFAULT 'PENETRATED', -- PENETRATED/MANUAL/DEFAULT
  report_period VARCHAR(12) NULL,                      -- 报告期 如 2025Q4
  created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_alloc_holding (holding_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2) 全局共享穿透缓存(无 family_id · 只按公开基金代码 · 金额不入表)
CREATE TABLE IF NOT EXISTS fund_penetration_cache (
  fund_code     VARCHAR(12) PRIMARY KEY,
  report_period VARCHAR(12) NULL,
  stock_pct     DECIMAL(6,2) NULL,                     -- 股票占净比
  bond_pct      DECIMAL(6,2) NULL,                     -- 债券占净比
  cash_pct      DECIMAL(6,2) NULL,                     -- 现金占净比
  covered_pct   DECIMAL(6,2) NULL,                     -- 前十大覆盖股票仓位比例
  alloc_json    TEXT NULL,                             -- 行业权重明细 [{industry,weightBp}]
  fund_name     VARCHAR(80) NULL,
  fund_type     VARCHAR(24) NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'OK',     -- OK/UNPENETRABLE/FAILED
  fetched_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) stock_holding 穿透键
ALTER TABLE stock_holding ADD COLUMN fund_code VARCHAR(12) NULL;
ALTER TABLE stock_holding ADD COLUMN penetrate_state VARCHAR(16) NULL;  -- PENDING/RESOLVED/MANUAL/UNPENETRATED
