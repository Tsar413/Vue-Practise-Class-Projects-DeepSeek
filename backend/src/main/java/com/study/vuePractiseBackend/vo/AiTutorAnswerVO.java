package com.study.vuePractiseBackend.vo;

import lombok.Data;

/**
 * AI 辅导回答结果。
 * configured=false 表示模型未配置：不调用模型、不生成任何答案，
 * message 为 null，notice 给前端展示提示；学生提问本身照常保存。
 */
@Data
public class AiTutorAnswerVO {

    private Long conversationId;

    /** 本轮真实模型回答；未配置或失败时为 null。 */
    private AiTutorMessageVO message;

    /** 模型是否已配置（baseUrl、model、apiKey 三项齐备）。 */
    private boolean configured;

    /** 未配置时的提示；已配置且成功时为 null。 */
    private String notice;
}
