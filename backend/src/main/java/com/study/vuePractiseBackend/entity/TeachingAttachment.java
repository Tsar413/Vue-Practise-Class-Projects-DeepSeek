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
 * 成果截图附件。
 *
 * 独立于报修附件表，落盘在 TEACHING_FILES_ROOT，不复用 repair-files 目录。
 *
 * 历史安全设计：附件记录本身是不可变的文件引用。
 *   * 版本与附件的对应关系放在 teaching_version_attachment（不可变，只增不改）；
 *   * 草稿与附件的对应关系放在 teaching_section_attachment（可替换）；
 *   * 因此重复提交可以引用同一份截图，而不会把旧版本的截图「移动」到新版本；
 *   * 附件行只有软删除标记，磁盘文件仅在「没有任何版本关联」时才可被清理任务删除。
 */
@Entity
@Table(name = "teaching_attachment")
@TableName("teaching_attachment")
@Data
public class TeachingAttachment {

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

    @Column(name = "section_id", columnDefinition = "BIGINT")
    @TableField("section_id")
    private Long sectionId;

    /** 首次随版本冻结时的版本 ID（仅作来源提示；版本关系以 teaching_version_attachment 为准）。 */
    @Column(name = "version_id", columnDefinition = "BIGINT")
    @TableField("version_id")
    private Long versionId;

    /** 相对 TEACHING_FILES_ROOT 的路径。 */
    @Column(name = "image_url", nullable = false, length = 500, columnDefinition = "VARCHAR(500)")
    @TableField("image_url")
    private String imageUrl;

    @Column(name = "original_name", nullable = false, length = 255, columnDefinition = "VARCHAR(255)")
    @TableField("original_name")
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 100, columnDefinition = "VARCHAR(100)")
    @TableField("content_type")
    private String contentType;

    @Column(name = "file_size", nullable = false, columnDefinition = "BIGINT")
    @TableField("file_size")
    private Long fileSize;

    /** 软删除时间：仅表示前端不再展示；被历史版本引用的文件不会被删除。 */
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
