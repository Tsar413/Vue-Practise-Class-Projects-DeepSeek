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
 * 实训任务。
 * project 决定学生要用哪个实训项目（TICKET 工会抢票 / REPAIR 校园设备报修）的接口完成成果。
 */
@Entity
@Table(name = "teaching_task")
@TableName("teaching_task")
@Data
public class TeachingTask {

    public static final String PROJECT_TICKET = "TICKET";
    public static final String PROJECT_REPAIR = "REPAIR";

    /** 0 草稿：仅创建教师可见；1 已发布：分配给班级的学生可见；2 已关闭：学生只读。 */
    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_CLOSED = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(nullable = false, length = 200, columnDefinition = "VARCHAR(200)")
    private String title;

    /** TICKET 或 REPAIR。 */
    @Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    private String project;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String objective;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String requirement;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String acceptance;

    /** 参考链接，只允许 http/https。 */
    @Column(name = "reference_url", length = 500, columnDefinition = "VARCHAR(500)")
    @TableField("reference_url")
    private String referenceUrl;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime deadline;

    @Column(name = "full_score", nullable = false, columnDefinition = "INTEGER")
    @TableField("full_score")
    private Integer fullScore;

    /** 1 允许逾期提交，0 不允许。 */
    @Column(name = "allow_late", nullable = false, columnDefinition = "TINYINT")
    @TableField("allow_late")
    private Integer allowLate;

    @Column(nullable = false, columnDefinition = "TINYINT")
    private Integer status;

    @Column(name = "teacher_id", nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    @TableField("teacher_id")
    private String teacherId;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "update_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("update_time")
    private LocalDateTime updateTime;
}
