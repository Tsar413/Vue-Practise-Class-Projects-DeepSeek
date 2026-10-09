package com.study.vuePractiseBackend.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 辅导会话视图对象。 */
@Data
public class AiTutorConversationVO {

    private Long id;

    /** TICKET 或 REPAIR。 */
    private String project;

    /** 关联实训任务，可空。 */
    private Long taskId;

    private String title;

    private Integer messageCount;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
