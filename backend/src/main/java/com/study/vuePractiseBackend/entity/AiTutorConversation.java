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
 * AI 辅导会话（学生私有）。
 * 归属学号只由登录身份写入，客户端提交的 project / taskId 不能替换库中已有值。
 * status=0 为软删，历史消息保留但不再可读。
 */
@Entity
@Table(name = "ai_tutor_conversation")
@TableName("ai_tutor_conversation")
@Data
public class AiTutorConversation {

    /** 与 teaching_task.project 保持同一取值。 */
    public static final String PROJECT_TICKET = "TICKET";
    public static final String PROJECT_REPAIR = "REPAIR";

    /** 1 正常。 */
    public static final int STATUS_NORMAL = 1;

    /** 0 已删除（软删）。 */
    public static final int STATUS_DELETED = 0;

    /** 未指定标题时的默认标题，与建表语句的默认值一致。 */
    public static final String DEFAULT_TITLE = "新的辅导会话";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属学生，对应 sys_user.id；每次请求都按登录身份校验。 */
    @Column(name = "student_id", nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    @TableField("student_id")
    private String studentId;

    /** TICKET 或 REPAIR。 */
    @Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    private String project;

    /** 可关联实训任务，对应 teaching_task.id；未关联时为空。 */
    @Column(name = "task_id", columnDefinition = "BIGINT")
    @TableField("task_id")
    private Long taskId;

    @Column(nullable = false, length = 200, columnDefinition = "VARCHAR(200)")
    private String title;

    /** 1 正常，0 已删除（软删）。 */
    @Column(nullable = false, columnDefinition = "TINYINT")
    private Integer status;

    /** 会话内消息条数，保存消息后按实际条数刷新。 */
    @Column(name = "message_count", nullable = false, columnDefinition = "INTEGER")
    @TableField("message_count")
    private Integer messageCount;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "update_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("update_time")
    private LocalDateTime updateTime;
}
