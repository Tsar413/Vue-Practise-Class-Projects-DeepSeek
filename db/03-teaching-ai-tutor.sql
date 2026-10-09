-- ---------------------------------------------------------------------------
-- db/03-teaching-ai-tutor.sql
-- 教学任务 / 成果版本 / 教师评价 / AI 辅导 增量迁移（纯结构定义）
--
-- 版本：2026-10-09-v3（含草稿字段、版本-附件关联、清理任务状态）
--
-- 重要：
--   1. 本脚本**不含 USE、不建库、不写 schema_migration**。
--      目标库与迁移记录一律由执行器 scripts/migrate.py 负责，
--      避免出现「记录里 checksum 为空」或「切错库」的情况。
--      请通过执行器应用：
--        python3 scripts/migrate.py --database <目标库> --dry-run
--        python3 scripts/migrate.py --database <目标库>
--   2. 只创建新增结构，不修改、不删除任何既有表或既有数据。
--   3. 全部使用 IF NOT EXISTS，可重复执行；结构是否完整由执行器逐项校验
--      （表、关键列、类型/排序规则、唯一约束），缺项即报错退出。
--   4. 外键列类型与排序规则与既有 sys_user.id、sys_class.id 一致
--      （实测均为 varchar(50) / utf8mb4_general_ci）。
--   5. 教学档案不随账号删除级联清除：外键为默认 RESTRICT，
--      应用层在删除教师/学生前给出明确的业务提示。
--   6. 不依赖 Hibernate ddl-auto 自动建表。
--
-- 应用到生产库前须由 Codex 复核并备份。
-- ---------------------------------------------------------------------------

SET NAMES utf8mb4;

-- ===========================================================================
-- 1. 实训任务
-- ===========================================================================
CREATE TABLE IF NOT EXISTS `teaching_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL COMMENT '任务标题',
  `project` varchar(20) NOT NULL COMMENT 'TICKET 或 REPAIR',
  `objective` text NULL COMMENT '教学目标',
  `requirement` text NOT NULL COMMENT '任务要求',
  `acceptance` text NULL COMMENT '验收标准',
  `reference_url` varchar(500) NULL COMMENT '参考链接，仅 http/https',
  `deadline` datetime NOT NULL COMMENT '截止时间',
  `full_score` int NOT NULL DEFAULT 100 COMMENT '满分',
  `allow_late` tinyint NOT NULL DEFAULT 0 COMMENT '1 允许逾期提交',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0 草稿 1 已发布 2 已关闭',
  `teacher_id` varchar(50) NOT NULL COMMENT '创建/负责教师',
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_teaching_task_status_deadline` (`status`, `deadline`),
  KEY `idx_teaching_task_teacher` (`teacher_id`),
  CONSTRAINT `fk_teaching_task_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='实训任务';

-- ===========================================================================
-- 2. 任务与班级的多对多分配
-- ===========================================================================
CREATE TABLE IF NOT EXISTS `teaching_task_class` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `class_id` varchar(50) NOT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teaching_task_class` (`task_id`, `class_id`),
  KEY `idx_teaching_task_class_class` (`class_id`),
  CONSTRAINT `fk_teaching_task_class_task` FOREIGN KEY (`task_id`) REFERENCES `teaching_task` (`id`),
  CONSTRAINT `fk_teaching_task_class_class` FOREIGN KEY (`class_id`) REFERENCES `sys_class` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='任务分配到的班级';

-- ===========================================================================
-- 3. 学生成果（每任务每学生一条）+ 不可覆盖的正式版本
-- ===========================================================================
CREATE TABLE IF NOT EXISTS `teaching_submission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `student_id` varchar(50) NOT NULL COMMENT '学号，由登录身份确定',
  `class_id` varchar(50) NOT NULL COMMENT '提交时的班级快照',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0 草稿 1 已提交 2 已评价 3 需修改',
  `version_no` int NOT NULL DEFAULT 0 COMMENT '最新正式版本号，草稿为 0',
  `late` tinyint NOT NULL DEFAULT 0 COMMENT '最新版本是否逾期',
  `draft_project_url` varchar(500) NULL COMMENT '草稿：成果项目/仓库链接',
  `draft_content` text NULL COMMENT '草稿：完成说明',
  `draft_process` text NULL COMMENT '草稿：问题与解决过程',
  `draft_update_time` datetime NULL COMMENT '草稿最后保存时间',
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teaching_submission_task_student` (`task_id`, `student_id`),
  KEY `idx_teaching_submission_task_status` (`task_id`, `status`),
  KEY `idx_teaching_submission_student` (`student_id`),
  CONSTRAINT `fk_teaching_submission_task` FOREIGN KEY (`task_id`) REFERENCES `teaching_task` (`id`),
  CONSTRAINT `fk_teaching_submission_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='学生对任务的成果记录';

CREATE TABLE IF NOT EXISTS `teaching_submission_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `task_id` bigint NOT NULL COMMENT '冗余，便于按任务统计与校验',
  `student_id` varchar(50) NOT NULL COMMENT '冗余，便于校验归属',
  `version_no` int NOT NULL COMMENT '从 1 递增',
  `project_url` varchar(500) NULL COMMENT '成果项目或仓库链接，仅 http/https',
  `content` text NOT NULL COMMENT '完成说明',
  `process` text NULL COMMENT '问题与解决过程',
  `late` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teaching_version_no` (`submission_id`, `version_no`),
  KEY `idx_teaching_version_task` (`task_id`),
  CONSTRAINT `fk_teaching_version_submission` FOREIGN KEY (`submission_id`) REFERENCES `teaching_submission` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='正式提交版本，生成后不可覆盖';

-- ===========================================================================
-- 4. 成果章节（承载截图）
-- ===========================================================================
CREATE TABLE IF NOT EXISTS `teaching_submission_section` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `version_id` bigint NULL COMMENT '属于某个正式版本时非空',
  `title` varchar(200) NOT NULL,
  `content` text NOT NULL,
  `sort_order` int NOT NULL DEFAULT 1,
  `is_draft` tinyint NOT NULL DEFAULT 0 COMMENT '1 草稿章节 0 已冻结章节',
  `deleted_at` datetime NULL COMMENT '草稿章节软删除时间；非空表示已从前端视图移除',
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_teaching_section_submission` (`submission_id`),
  KEY `idx_teaching_section_version` (`version_id`),
  KEY `idx_teaching_section_deleted` (`deleted_at`),
  CONSTRAINT `fk_teaching_section_submission` FOREIGN KEY (`submission_id`) REFERENCES `teaching_submission` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='成果章节';

-- ===========================================================================
-- 5. 截图附件（独立表与独立存储目录 TEACHING_FILES_ROOT）
-- ===========================================================================
CREATE TABLE IF NOT EXISTS `teaching_attachment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `student_id` varchar(50) NOT NULL COMMENT '上传者学号',
  `section_id` bigint NULL,
  `version_id` bigint NULL,
  `image_url` varchar(500) NOT NULL COMMENT '相对 TEACHING_FILES_ROOT 的路径',
  `original_name` varchar(255) NOT NULL,
  `content_type` varchar(100) NOT NULL,
  `file_size` bigint NOT NULL,
  `deleted_at` datetime NULL COMMENT '软删除时间；仅用于前端不再展示，历史版本关联仍可读',
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_teaching_attachment_task_student` (`task_id`, `student_id`),
  KEY `idx_teaching_attachment_section` (`section_id`),
  KEY `idx_teaching_attachment_deleted` (`deleted_at`),
  CONSTRAINT `fk_teaching_attachment_task` FOREIGN KEY (`task_id`) REFERENCES `teaching_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='成果截图附件';

CREATE TABLE IF NOT EXISTS `teaching_file_delete_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `image_url` varchar(500) NOT NULL,
  `attachment_id` bigint NULL COMMENT '来源附件，便于诊断',
  `processed` tinyint NOT NULL DEFAULT 0 COMMENT '0 待处理 1 已处理',
  `reason` varchar(200) NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_teaching_delete_pending` (`processed`, `id`),
  KEY `idx_teaching_delete_url` (`image_url`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='成果截图待删除文件';

-- ===========================================================================
-- 5.1 版本与截图的不可变关联
--     一个文件可以被多个版本引用；重交时新增关联行，
--     绝不把旧版本的附件「移动」到新版本，历史版本始终可读。
-- ===========================================================================
CREATE TABLE IF NOT EXISTS `teaching_version_attachment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `version_id` bigint NOT NULL,
  `attachment_id` bigint NOT NULL,
  `submission_id` bigint NOT NULL COMMENT '冗余，便于按成果校验与查询',
  `section_id` bigint NULL COMMENT '该版本中所在的章节（已被软删的章节其关联仍保留）',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teaching_version_attachment` (`version_id`, `attachment_id`),
  KEY `idx_teaching_va_attachment` (`attachment_id`),
  KEY `idx_teaching_va_submission` (`submission_id`),
  CONSTRAINT `fk_teaching_va_version` FOREIGN KEY (`version_id`) REFERENCES `teaching_submission_version` (`id`),
  CONSTRAINT `fk_teaching_va_attachment` FOREIGN KEY (`attachment_id`) REFERENCES `teaching_attachment` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='版本与截图的不可变关联';

-- ===========================================================================
-- 5.2 章节与截图的当前归属（草稿使用；可被替换）
--     与版本关联分开：草稿可以改，版本关联不可变。
-- ===========================================================================
CREATE TABLE IF NOT EXISTS `teaching_section_attachment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `section_id` bigint NOT NULL,
  `attachment_id` bigint NOT NULL,
  `submission_id` bigint NOT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teaching_section_attachment` (`section_id`, `attachment_id`),
  KEY `idx_teaching_sa_attachment` (`attachment_id`),
  KEY `idx_teaching_sa_submission` (`submission_id`),
  CONSTRAINT `fk_teaching_sa_section` FOREIGN KEY (`section_id`) REFERENCES `teaching_submission_section` (`id`),
  CONSTRAINT `fk_teaching_sa_attachment` FOREIGN KEY (`attachment_id`) REFERENCES `teaching_attachment` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='章节与截图的当前归属（草稿可替换）';

-- ===========================================================================
-- 6. 教师评价（绑定到具体版本）
-- ===========================================================================
CREATE TABLE IF NOT EXISTS `teaching_evaluation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `version_id` bigint NOT NULL COMMENT '被评价的版本',
  `version_no` int NOT NULL COMMENT '冗余，便于展示',
  `task_id` bigint NOT NULL,
  `student_id` varchar(50) NOT NULL,
  `teacher_id` varchar(50) NOT NULL COMMENT '实际评分教师，由登录身份确定',
  `decision` varchar(20) NOT NULL COMMENT 'PASS 通过 / REVISE 退回修改',
  `score` int NULL COMMENT 'PASS 时必填，0..满分',
  `comment` text NULL COMMENT '评语',
  `is_current` tinyint NOT NULL DEFAULT 1 COMMENT '是否为最新版本的评价',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_teaching_eval_submission` (`submission_id`, `id`),
  KEY `idx_teaching_eval_version` (`version_id`),
  KEY `idx_teaching_eval_teacher` (`teacher_id`),
  CONSTRAINT `fk_teaching_eval_submission` FOREIGN KEY (`submission_id`) REFERENCES `teaching_submission` (`id`),
  CONSTRAINT `fk_teaching_eval_version` FOREIGN KEY (`version_id`) REFERENCES `teaching_submission_version` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='教师对成果版本的评分/退回记录';

-- ===========================================================================
-- 7. AI 辅导会话与消息
-- ===========================================================================
CREATE TABLE IF NOT EXISTS `ai_tutor_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `student_id` varchar(50) NOT NULL COMMENT '归属学生，每次请求由登录身份核验',
  `project` varchar(20) NOT NULL COMMENT 'TICKET / REPAIR',
  `task_id` bigint NULL COMMENT '可关联实训任务',
  `title` varchar(200) NOT NULL DEFAULT '新的辅导会话',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1 正常 0 已删除（软删）',
  `message_count` int NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_conv_student` (`student_id`, `status`, `id`),
  KEY `idx_ai_conv_task` (`task_id`),
  CONSTRAINT `fk_ai_conv_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 辅导会话（学生私有）';

CREATE TABLE IF NOT EXISTS `ai_tutor_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `student_id` varchar(50) NOT NULL COMMENT '冗余，用于每次请求校验归属',
  `role` varchar(20) NOT NULL COMMENT 'USER / ASSISTANT / SYSTEM',
  `content` text NOT NULL COMMENT '已脱敏内容，不保存原始敏感值',
  `context_refs` text NULL COMMENT '本次回答引用的结构化来源（JSON 数组字符串）',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_msg_conversation` (`conversation_id`, `id`),
  CONSTRAINT `fk_ai_msg_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `ai_tutor_conversation` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 辅导消息';

-- ===========================================================================
-- 8. 提交幂等键（并发/重复点击不重复生成版本）
-- ===========================================================================
CREATE TABLE IF NOT EXISTS `teaching_submit_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `request_key` varchar(64) NOT NULL COMMENT '客户端一次性键，服务端唯一',
  `version_id` bigint NULL COMMENT '该请求生成的版本',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teaching_submit_request` (`submission_id`, `request_key`),
  KEY `idx_teaching_submit_request_time` (`create_time`),
  CONSTRAINT `fk_teaching_submit_request_submission` FOREIGN KEY (`submission_id`) REFERENCES `teaching_submission` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='提交幂等键';
