-- V13: 测试账户 username lijing → wangergou(用户偏好);display_name 保留"Bob"。
-- 跟 V9(zhangwei → diwa)同一性质的纯改名,不动密码不动权限。

UPDATE member SET username = 'wangergou' WHERE username = 'lijing';
