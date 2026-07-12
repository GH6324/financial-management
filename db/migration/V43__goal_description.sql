-- v0.16.x · 目标可选描述(纯加列可空 · 存量零影响)
ALTER TABLE family_goal
    ADD COLUMN description VARCHAR(255) NULL COMMENT '目标可选描述' AFTER name;
