package com.study.vuePractiseBackend.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** AI 辅导消息视图对象。 */
@Data
public class AiTutorMessageVO {

    private Long id;

    /** USER / ASSISTANT / SYSTEM。 */
    private String role;

    /** 已脱敏、截断后的内容。 */
    private String content;

    /** 引用的真实来源；历史数据解析失败时为空列表。 */
    private List<AiTutorContextRefVO> contextRefs;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
