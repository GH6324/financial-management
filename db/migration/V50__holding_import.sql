-- =========================================================
-- V50 · v1.4 持仓截图导入任务 + 逐项 + 估值事件挂钩(2026-07-23)
-- =========================================================
-- 导入是一个有状态的持久任务(承评审第 6 点断点续看 / 第 8 点原图持久化 / 第 9 点明细回看):
--   holding_import      一次导入(账户/期/状态机/模型/成本估)
--   holding_import_item 逐项识别结果 + 三态匹配(匹配更新/新增/卖出)+ 旧→新 + 来源原图
-- 确认导入后走 AccountValuationService(HOLDING_CHANGE)自动写回快照 + 产生 stock_valuation_event
-- (ledger 的「△ 估值变动」流水);给该事件加 ref_import_id 链到本次导入,供 ledger 展开看明细+原图。
-- 对应 prd/v1.4.md §7 · tech-design/v1.4.md §2②③ / D9-D11
-- =========================================================

CREATE TABLE holding_import (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  family_id    BIGINT       NOT NULL,
  account_id   BIGINT       NOT NULL,
  period_id    BIGINT       NOT NULL   COMMENT '导入落在哪个开账期',
  status       VARCHAR(12)  NOT NULL   COMMENT 'UPLOADING / SCANNING / REVIEW / CONFIRMED / ABANDONED',
  vision_model VARCHAR(32)  NULL       COMMENT '本次用的视觉模型',
  cost_est     DECIMAL(10,4) NULL      COMMENT '前端预估成本(留痕)',
  img_count    INT          NOT NULL DEFAULT 0,
  scan_error   VARCHAR(255) NULL       COMMENT '识别失败原因(友好归类 · 不含 key)',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  confirmed_at DATETIME(3)  NULL,
  PRIMARY KEY (id),
  INDEX idx_acct_status (account_id, status),
  INDEX idx_family (family_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='持仓截图导入任务 · 状态机 · v1.4';

CREATE TABLE holding_import_item (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  import_id       BIGINT        NOT NULL,
  parsed_name     VARCHAR(120)  NULL   COMMENT '识别出的名称(原文)',
  parsed_code     VARCHAR(16)   NULL   COMMENT '基金代码(若识别到)',
  market_value    DECIMAL(20,4) NULL   COMMENT '识别市值(账户币种)',
  confidence      VARCHAR(8)    NULL   COMMENT 'high / low',
  match_state     VARCHAR(10)   NOT NULL COMMENT 'UPDATE(匹配更新) / NEW(新增) / SOLD(卖出/未出现)',
  matched_hid     BIGINT        NULL   COMMENT '匹配到的已有 stock_holding.id',
  old_value       DECIMAL(20,4) NULL   COMMENT 'UPDATE 时旧市值(展示旧→新)',
  asset_class_tag VARCHAR(20)   NULL,
  industry_tag    VARCHAR(20)   NULL,
  platform_tag    VARCHAR(40)   NULL,
  shot_path       VARCHAR(255)  NULL   COMMENT '该项来自哪张压缩原图 family-{id}/holdingshots/…',
  user_decision   VARCHAR(10)   NULL   COMMENT 'SOLD 项用户定夺:KEEP / ARCHIVE',
  selected        TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '用户是否勾选导入本项',
  sort_no         INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  INDEX idx_import (import_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='持仓截图导入逐项(三态匹配 + 原图) · v1.4';

-- 估值事件挂钩导入:ledger「△ 估值变动」可展开看本次导入明细 + 原图
ALTER TABLE stock_valuation_event
  ADD COLUMN ref_import_id BIGINT NULL COMMENT '指向 holding_import · 截图导入触发的估值事件 · ledger 展开明细用';
