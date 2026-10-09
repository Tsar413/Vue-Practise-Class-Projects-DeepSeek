package com.study.vuePractiseBackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 章节与截图的当前归属（草稿使用，可被替换）。 */
@Entity
@Table(name = "teaching_section_attachment")
@TableName("teaching_section_attachment")
@Data
public class TeachingSectionAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "section_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("section_id")
    private Long sectionId;

    @Column(name = "attachment_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("attachment_id")
    private Long attachmentId;

    @Column(name = "submission_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("submission_id")
    private Long submissionId;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;
}
