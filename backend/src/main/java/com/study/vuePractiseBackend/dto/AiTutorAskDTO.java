package com.study.vuePractiseBackend.dto;

import lombok.Data;

/**
 * AI 辅导提问请求。
 * conversationId 为空表示新建会话；project 与 taskId 只在新建时生效，
 * 已有会话一律以数据库中的值为准，客户端传值不一致会被拒绝。
 */
@Data
public class AiTutorAskDTO {

    /** 为空表示新建会话。 */
    private Long conversationId;

    /** TICKET 或 REPAIR；新建会话时必填。 */
    private String project;

    /** 关联实训任务，可空；新建会话时生效。 */
    private Long taskId;

    /** 学生提问，必填。 */
    private String question;

    /** 相关代码片段，可空。 */
    private String codeSnippet;

    /** 报错信息，可空。 */
    private String errorText;

    /**
     * 幂等键，可空；建议前端每次提问生成一个（8..64 位字母、数字、下划线或短横线）。
     * 同一会话 + 同一 requestKey 的重复或并发请求只会处理一次，直接复用既有回答。
     */
    private String requestKey;
}
