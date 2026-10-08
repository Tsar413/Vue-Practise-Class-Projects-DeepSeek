-- ---------------------------------------------------------------------------
-- 01-schema.sql  数据库与表结构
--
-- 说明：
--   本文件由复现实现启动后 Hibernate(ddl-auto=update) 依据实体生成的结构导出，
--   与后端实体的表名、列名和类型一一对应，可直接重复执行。
--   参考项目未在仓库中提供初始化 SQL，因此这里补齐了可复现的建库脚本。
--
-- 用法：
--   mysql -uroot -p < db/01-schema.sql
--   或使用 scripts/init-db.sh（会同时导入 02-seed-demo-data.sql）
-- ---------------------------------------------------------------------------

CREATE DATABASE IF NOT EXISTS `vue_practise_backend`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE `vue_practise_backend`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE `repair_attachment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content_type` varchar(100) COLLATE utf8mb4_general_ci NOT NULL,
  `create_time` datetime NOT NULL,
  `file_size` bigint NOT NULL,
  `image_type` int NOT NULL,
  `image_url` varchar(500) COLLATE utf8mb4_general_ci NOT NULL,
  `original_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL,
  `status` int NOT NULL,
  `update_time` datetime NOT NULL,
  `uploader_id` bigint NOT NULL,
  `workspace_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `repair_device` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `campus` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `device_name` varchar(100) COLLATE utf8mb4_general_ci NOT NULL,
  `device_no` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  `device_type` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  `location` varchar(200) COLLATE utf8mb4_general_ci NOT NULL,
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `status` int NOT NULL,
  `update_time` datetime NOT NULL,
  `workspace_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `repair_evaluation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `order_id` bigint NOT NULL,
  `score` int NOT NULL,
  `update_time` datetime NOT NULL,
  `user_id` bigint NOT NULL,
  `workspace_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `repair_file_delete_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `create_time` datetime NOT NULL,
  `image_url` varchar(500) COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `repair_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `campus` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `cancel_time` datetime DEFAULT NULL,
  `completed_time` datetime DEFAULT NULL,
  `contact_phone` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `description` text COLLATE utf8mb4_general_ci NOT NULL,
  `device_id` bigint DEFAULT NULL,
  `device_name` varchar(100) COLLATE utf8mb4_general_ci NOT NULL,
  `device_type` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  `location` varchar(200) COLLATE utf8mb4_general_ci NOT NULL,
  `maintainer_id` bigint DEFAULT NULL,
  `order_no` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `repair_result` text COLLATE utf8mb4_general_ci,
  `reporter_id` bigint NOT NULL,
  `status` int NOT NULL,
  `title` varchar(100) COLLATE utf8mb4_general_ci NOT NULL,
  `update_time` datetime NOT NULL,
  `workspace_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `repair_order_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `attachment_id` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `image_type` int NOT NULL,
  `image_url` varchar(500) COLLATE utf8mb4_general_ci NOT NULL,
  `order_id` bigint NOT NULL,
  `process_record_id` bigint DEFAULT NULL,
  `sort_order` int NOT NULL,
  `update_time` datetime NOT NULL,
  `uploader_id` bigint NOT NULL,
  `workspace_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `repair_process_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action` varchar(30) COLLATE utf8mb4_general_ci NOT NULL,
  `content` text COLLATE utf8mb4_general_ci,
  `create_time` datetime NOT NULL,
  `from_status` int DEFAULT NULL,
  `operator_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `target_user_id` bigint DEFAULT NULL,
  `to_status` int NOT NULL,
  `update_time` datetime NOT NULL,
  `workspace_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `repair_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `create_time` datetime NOT NULL,
  `department` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `phone` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `real_name` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  `role` varchar(20) COLLATE utf8mb4_general_ci NOT NULL,
  `status` int NOT NULL,
  `update_time` datetime NOT NULL,
  `user_no` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  `workspace_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `sys_class` (
  `id` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  `class_name` varchar(100) COLLATE utf8mb4_general_ci NOT NULL,
  `create_time` datetime DEFAULT NULL,
  `status` int NOT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `sys_login_token` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `api_access_code` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `expire_time` datetime DEFAULT NULL,
  `revoke_time` datetime DEFAULT NULL,
  `token_hash` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `user_id` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6chftf4bygyfp7vdwe15q8lpw` (`token_hash`),
  UNIQUE KEY `UK6napt3jiq6dp0ymps4uwcfp6f` (`user_id`),
  UNIQUE KEY `UKt1mfhjsy5tx210sv44i8ni9rs` (`api_access_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `sys_user` (
  `id` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  `class_id` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `last_login_time` datetime DEFAULT NULL,
  `password_algorithm` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `password_hash` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `password_salt` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `real_name` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  `role` varchar(20) COLLATE utf8mb4_general_ci NOT NULL,
  `status` int NOT NULL,
  `update_time` datetime DEFAULT NULL,
  `username` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `sys_workspace` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `create_time` datetime DEFAULT NULL,
  `status` int NOT NULL,
  `student_id` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKry1nmewtno2rjb0sxxqb9st2a` (`student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `ticket_activity` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_end_time` datetime DEFAULT NULL,
  `activity_name` varchar(100) COLLATE utf8mb4_general_ci NOT NULL,
  `activity_start_time` datetime DEFAULT NULL,
  `booked_count` int NOT NULL,
  `booking_end_time` datetime DEFAULT NULL,
  `booking_start_time` datetime DEFAULT NULL,
  `campus` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `cover_url` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `description` longtext COLLATE utf8mb4_general_ci,
  `location` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `quota` int NOT NULL,
  `status` int NOT NULL,
  `workspace_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `ticket_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_id` bigint NOT NULL,
  `booking_time` datetime NOT NULL,
  `cancel_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `status` int NOT NULL,
  `ticket_no` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `update_time` datetime NOT NULL,
  `user_id` bigint NOT NULL,
  `verify_time` datetime DEFAULT NULL,
  `verify_user_id` bigint DEFAULT NULL,
  `workspace_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `ticket_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `campus` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `department` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `phone` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `real_name` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  `role` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `status` int NOT NULL,
  `user_no` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  `workspace_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

SET FOREIGN_KEY_CHECKS = 1;
