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
 * 版本与截图的不可变关联：只增不改不删。
 * 重交时新增关联行，因此旧版本的历史截图永远可读。
 */
@Entity
@Table(name = "teaching_version_attachment")
@TableName("teaching_version_attachment")
@Data
public class TeachingVersionAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "version_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("version_id")
    private Long versionId;

    @Column(name = "attachment_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("attachment_id")
    private Long attachmentId;

    @Column(name = "submission_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("submission_id")
    private Long submissionId;

    @Column(name = "section_id", columnDefinition = "BIGINT")
    @TableField("section_id")
    private Long sectionId;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;
}
