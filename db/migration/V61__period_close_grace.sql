-- ============================================================
-- V61 · 关账宽限:账期什么时候关,交给家庭自己定(v1.23 FR-610 / FR-613)
-- ============================================================
--
-- 为什么改:
--   `PeriodOpener.openIfDue()` 的 cron 是写死的 `0 30 0 * * *` —— 次月 1 日凌晨 00:30
--   就把上月 force-close 了。而微信 / 支付宝 / 信用卡最方便拿到的是「上月整月支出」,
--   那个数要等月底过完才有。用户 9/1 白天打开账单准备填时,8 月已经 CLOSED、硬拦写入。
--   关账时机本来就该是这个家庭的节奏,没有理由由开发者定。
--   来源:issue #20(@Pengyang233)· 维护者改判 2026-09-18。
--
-- 两列的语义:
--   close_delay_days   = 账期自然结束后,再保持 OPEN 几天(0 / 2 / 5)
--   auto_close_enabled = 关掉 = 手动关账(但仍受 FR-612 兜底:下下期一开就强制关)
--
-- 为什么 close_delay_days 上限锁 5:
--   宽限的意义是「等账单出来」,2–5 天足够。**上限必须小于一个账期的长度** ——
--   否则会出现三期同时 OPEN,而本版所有判据都是按「最多两期」写的(PRD §5)。
--   用 CHECK 从 schema 层保证这件事,而不是只在应用层校验:应用层能绕过(直接改库),
--   而三期 OPEN 会让收益类锚点、月均支出窗口、余额传导全部进入未定义状态。
--   WEEKLY 家庭一期只有 7 天,5 天宽限仍在同期内(第 6 天才开新期)。
--
-- 对线上现有数据的影响(prod 跑着真实家庭数据,每版必答):
--   **零**。只加列、带默认值,不 UPDATE / DELETE 任何行。
--   default 0 + enabled 1 = 完全等于今天的行为:
--     关账截止日 = period_end + 0 = period_end,而新期起始日必然 > period_end,
--     所以「今天 > 截止日」在新期第一天就成立 —— 与现在「开新期时关上期」等价。
--   这一点由零差异基线脚本对着已发布 tag v1.22.5 逐分验证(PRD §11 第 16 条)。
--
-- 老 jar 见到这个 schema 能跑吗:
--   **能**。老 jar 的 SELECT 不含这两列,多出来的列对它完全无感。
--   (项目惯例:回滚只回 jar 不回 DB。)
--
-- 放开之后谁要跟着表态:
--   30 处 `findCurrentOpen` 调用点 —— 双 OPEN 下它静默返回最新期,**不报错**。
--   分类与逐处处置见 prd/v1.23.md §1.1 与 §4,护栏 v1230-DUAL-OPEN-SWEEP。
-- ============================================================

ALTER TABLE family
    ADD COLUMN close_delay_days  SMALLINT    NOT NULL DEFAULT 0
        COMMENT '关账宽限天数:账期自然结束后再保持 OPEN 几天(0=立即关,与 v1.22 前行为一致)',
    ADD COLUMN auto_close_enabled TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '是否自动关账:0=手动关(仍受「下下期一开强制关」兜底)';

ALTER TABLE family
    ADD CONSTRAINT ck_family_close_delay CHECK (close_delay_days BETWEEN 0 AND 5);
