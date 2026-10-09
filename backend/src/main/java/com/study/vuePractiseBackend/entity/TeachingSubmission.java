package com.study.vuePractiseBackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学生对某个任务的成果记录：每个「任务 + 学生」只有一条。
 * 正式提交内容放在 teaching_submission_version，这里只保存流转状态与最新版本号。
 *
 * 并发说明：本行的行锁是「同一学生同一任务」提交串行化的依据，
 * 版本号在同一把锁内递增，因此不会重复生成版本。
 */
@Entity
@Table(name = "teaching_submission")
@TableName("teaching_submission")
@Data
public class TeachingSubmission {

    /** 0 草稿（尚未正式提交过）；1 已提交待评价；2 已评价通过；3 需修改（被退回）。 */
    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_SUBMITTED = 1;
    public static final int STATUS_EVALUATED = 2;
    public static final int STATUS_REVISING = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "task_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("task_id")
    private Long taskId;

    @Column(name = "student_id", nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    @TableField("student_id")
    private String studentId;

    /** 提交时的班级快照，用于教师按班级统计。 */
    @Column(name = "class_id", nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    @TableField("class_id")
    private String classId;

    @Column(nullable = false, columnDefinition = "TINYINT")
    private Integer status;

    /** 已生成的最新正式版本号；从未正式提交过为 0。 */
    @Column(name = "version_no", nullable = false, columnDefinition = "INTEGER")
    @TableField("version_no")
    private Integer versionNo;

    /** 最新版本是否逾期提交。 */
    @Column(nullable = false, columnDefinition = "TINYINT")
    private Integer late;

    // ---------------- 草稿字段（完整持久化，刷新/重新登录后可恢复）----------------

    @Column(name = "draft_project_url", length = 500, columnDefinition = "VARCHAR(500)")
    @TableField("draft_project_url")
    private String draftProjectUrl;

    @Lob
    @Column(name = "draft_content", columnDefinition = "TEXT")
    @TableField("draft_content")
    private String draftContent;

    @Lob
    @Column(name = "draft_process", columnDefinition = "TEXT")
    @TableField("draft_process")
    private String draftProcess;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "draft_update_time", columnDefinition = "DATETIME")
    @TableField("draft_update_time")
    private LocalDateTime draftUpdateTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "update_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("update_time")
    private LocalDateTime updateTime;
}
