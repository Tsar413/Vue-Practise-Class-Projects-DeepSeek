package com.study.vuePractiseBackend.dto;

import lombok.Data;

/** AI 辅导会话新建请求。 */
@Data
public class AiTutorConversationDTO {

    /** TICKET 或 REPAIR，必填。 */
    private String project;

    /** 关联实训任务，可空。 */
    private Long taskId;

    /** 会话标题，可空；为空时使用默认标题。 */
    private String title;
}
