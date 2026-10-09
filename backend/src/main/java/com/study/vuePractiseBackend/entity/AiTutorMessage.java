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
 * AI 辅导消息。
 * content 只保存已脱敏并截断后的文本；contextRefs 保存本次回答引用的真实来源（JSON 数组字符串）。
 */
@Entity
@Table(name = "ai_tutor_message")
@TableName("ai_tutor_message")
@Data
public class AiTutorMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_SYSTEM = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "conversation_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("conversation_id")
    private Long conversationId;

    /** 冗余的归属学生，用于每次请求校验归属。 */
    @Column(name = "student_id", nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    @TableField("student_id")
    private String studentId;

    /** USER / ASSISTANT / SYSTEM。 */
    @Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    private String role;

    /** 已脱敏、已截断的文本，不保存原始敏感值。 */
    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 本次回答引用的结构化来源，JSON 数组字符串；用户提问为空。 */
    @Lob
    @Column(name = "context_refs", columnDefinition = "TEXT")
    @TableField("context_refs")
    private String contextRefs;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;
}
