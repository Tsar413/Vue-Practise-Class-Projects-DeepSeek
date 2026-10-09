package com.study.vuePractiseBackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 正式提交版本：一经生成不可覆盖、不可修改、不可删除。 */
@Entity
@Table(name = "teaching_submission_version")
@TableName("teaching_submission_version")
@Data
public class TeachingSubmissionVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "submission_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("submission_id")
    private Long submissionId;

    /** 冗余字段：便于按任务统计与归属校验。 */
    @Column(name = "task_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("task_id")
    private Long taskId;

    @Column(name = "student_id", nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    @TableField("student_id")
    private String studentId;

    @Column(name = "version_no", nullable = false, columnDefinition = "INTEGER")
    @TableField("version_no")
    private Integer versionNo;

    /** 成果项目或仓库链接，只允许 http/https。 */
    @Column(name = "project_url", length = 500, columnDefinition = "VARCHAR(500)")
    @TableField("project_url")
    private String projectUrl;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String process;

    @Column(nullable = false, columnDefinition = "TINYINT")
    private Integer late;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;
}
