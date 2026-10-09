package com.study.vuePractiseBackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 任务分配给哪些班级（多对多）。 */
@Entity
@Table(name = "teaching_task_class")
@TableName("teaching_task_class")
@Data
public class TeachingTaskClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "task_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("task_id")
    private Long taskId;

    @Column(name = "class_id", nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    @TableField("class_id")
    private String classId;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;
}
