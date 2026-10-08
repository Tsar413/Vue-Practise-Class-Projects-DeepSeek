-- ---------------------------------------------------------------------------
-- 02-seed-demo-data.sql  虚构演示数据
--
-- 重要：本文件只包含虚构数据，不含任何真实学生姓名、学号或联系方式。
--       手机号统一使用 138000000xx 形式的示例号段。
--
-- 内容：
--   1 个班级、1 个教师账号、3 个学生账号（初始密码均为 123456）
--   学生账号会自动获得工作空间，但抢票 / 报修基准数据需由教师按班级初始化，
--   或由学生在“数据重置”中生成。
--
-- 口令散列规则与后端一致：SHA-256(盐 + ":" + 口令)，十六进制小写。
-- 这里直接用 MySQL 的 SHA2 函数计算，因此不需要预先写入固定的散列值。
--
-- 用法：
--   mysql -uroot -p vue_practise_backend < db/02-seed-demo-data.sql
-- ---------------------------------------------------------------------------

USE `vue_practise_backend`;

SET NAMES utf8mb4;

-- ---------------------------- 班级 --------------------------------------
INSERT INTO `sys_class` (`id`, `class_name`, `status`, `create_time`, `update_time`)
VALUES ('DEMO2026', 'Vue 实训示范班', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE `class_name` = VALUES(`class_name`), `status` = 1, `update_time` = NOW();

-- ---------------------------- 系统账号 ----------------------------------
-- 教师账号
INSERT INTO `sys_user`
  (`id`, `username`, `real_name`, `class_id`, `role`, `password_hash`, `password_salt`,
   `password_algorithm`, `status`, `last_login_time`, `create_time`, `update_time`)
VALUES
  ('DEMO_TEACHER', '示范教师', '示范教师', NULL, 'TEACHER',
   SHA2(CONCAT('a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90', ':', '123456'), 256),
   'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
   'SHA-256', 1, NULL, NOW(), NOW())
ON DUPLICATE KEY UPDATE `real_name` = VALUES(`real_name`), `status` = 1, `update_time` = NOW();

-- 学生账号（每名学生一个独立工作空间）
INSERT INTO `sys_user`
  (`id`, `username`, `real_name`, `class_id`, `role`, `password_hash`, `password_salt`,
   `password_algorithm`, `status`, `last_login_time`, `create_time`, `update_time`)
VALUES
  ('DEMO2026001', '学生甲', '学生甲', 'DEMO2026', 'STUDENT',
   SHA2(CONCAT('b1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90', ':', '123456'), 256),
   'b1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
   'SHA-256', 1, NULL, NOW(), NOW()),
  ('DEMO2026002', '学生乙', '学生乙', 'DEMO2026', 'STUDENT',
   SHA2(CONCAT('c1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90', ':', '123456'), 256),
   'c1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
   'SHA-256', 1, NULL, NOW(), NOW()),
  ('DEMO2026003', '学生丙', '学生丙', 'DEMO2026', 'STUDENT',
   SHA2(CONCAT('d1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90', ':', '123456'), 256),
   'd1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
   'SHA-256', 1, NULL, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `real_name` = VALUES(`real_name`),
  `class_id` = VALUES(`class_id`),
  `status` = 1,
  `update_time` = NOW();

-- ---------------------------- 工作空间 --------------------------------
-- 数据隔离的边界：一名学生一条记录，所有业务数据都挂在 workspace_id 上。
INSERT INTO `sys_workspace` (`student_id`, `status`, `create_time`, `update_time`)
VALUES
  ('DEMO2026001', 1, NOW(), NOW()),
  ('DEMO2026002', 1, NOW(), NOW()),
  ('DEMO2026003', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE `status` = 1, `update_time` = NOW();

-- 说明：
--   sys_login_token 不预置。学生首次登录时后端生成网页登录 Token 与长期 API 访问码。
--   ticket_* / repair_* 业务表不预置。教师可在“工作空间”页按班级初始化，
--   或调用 POST /api/sys-workspace/classes/DEMO2026/ticket/initialize 与
--   POST /api/sys-workspace/classes/DEMO2026/repair/initialize。
