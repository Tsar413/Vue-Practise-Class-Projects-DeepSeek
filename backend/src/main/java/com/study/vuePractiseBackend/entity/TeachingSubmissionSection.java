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
 * 成果章节，用于承载截图。
 * is_draft=1 的草稿章节可以被后续草稿替换（软删除，保留行）；
 * 一旦随正式提交冻结（is_draft=0 且 version_id 非空），其截图不再允许删除。
 */
@Entity
@Table(name = "teaching_submission_section")
@TableName("teaching_submission_section")
@Data
public class TeachingSubmissionSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "submission_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("submission_id")
    private Long submissionId;

    /** 属于某个正式版本时非空。 */
    @Column(name = "version_id", columnDefinition = "BIGINT")
    @TableField("version_id")
    private Long versionId;

    @Column(nullable = false, length = 200, columnDefinition = "VARCHAR(200)")
    private String title;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "sort_order", nullable = false, columnDefinition = "INTEGER")
    @TableField("sort_order")
    private Integer sortOrder;

    @Column(name = "is_draft", nullable = false, columnDefinition = "TINYINT")
    @TableField("is_draft")
    private Integer isDraft;

    /** 草稿章节软删除时间；非空表示已从前端视图移除，但其附件关联记录仍保留。 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "deleted_at", columnDefinition = "DATETIME")
    @TableField("deleted_at")
    private LocalDateTime deletedAt;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "update_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("update_time")
    private LocalDateTime updateTime;
}
