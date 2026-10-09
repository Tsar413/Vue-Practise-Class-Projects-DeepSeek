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
 * 提交幂等键：同一提交行 + 同一 requestKey 只允许一条记录。
 * 重复点击（并发或重放）会因唯一键冲突被识别为「已经提交过」，
 * 从而不会重复生成版本。
 */
@Entity
@Table(name = "teaching_submit_request")
@TableName("teaching_submit_request")
@Data
public class TeachingSubmitRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "submission_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("submission_id")
    private Long submissionId;

    @Column(name = "request_key", nullable = false, length = 64, columnDefinition = "VARCHAR(64)")
    @TableField("request_key")
    private String requestKey;

    @Column(name = "version_id", columnDefinition = "BIGINT")
    @TableField("version_id")
    private Long versionId;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;
}
