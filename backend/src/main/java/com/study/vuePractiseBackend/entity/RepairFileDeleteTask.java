package com.study.vuePractiseBackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件清理任务。
 * 与业务事务一起提交，后台删除失败时保留任务下次重试。
 */
@Entity
@Table(name = "repair_file_delete_task")
@TableName("repair_file_delete_task")
@Data
public class RepairFileDeleteTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "image_url", nullable = false, length = 500)
    @TableField("image_url")
    private String imageUrl;

    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;
}
