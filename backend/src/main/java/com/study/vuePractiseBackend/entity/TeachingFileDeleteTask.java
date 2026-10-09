package com.study.vuePractiseBackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 成果截图待删除文件：与业务事务一起提交，后台重试删除。 */
@Entity
@Table(name = "teaching_file_delete_task")
@TableName("teaching_file_delete_task")
@Data
public class TeachingFileDeleteTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "image_url", nullable = false, length = 500, columnDefinition = "VARCHAR(500)")
    @TableField("image_url")
    private String imageUrl;

    @Column(name = "attachment_id", columnDefinition = "BIGINT")
    @TableField("attachment_id")
    private Long attachmentId;

    /** 0 待处理 1 已处理；避免重复排队与重复删除。 */
    @Column(nullable = false, columnDefinition = "TINYINT")
    private Integer processed;

    @Column(length = 200, columnDefinition = "VARCHAR(200)")
    private String reason;

    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;
}
