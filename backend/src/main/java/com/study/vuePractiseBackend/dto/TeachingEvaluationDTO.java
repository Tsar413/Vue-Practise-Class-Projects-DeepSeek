package com.study.vuePractiseBackend.dto;

import lombok.Data;

/**
 * 教师评价入参。
 * teacherId 不接受客户端提交：实际评分教师取自登录身份。
 */
@Data
public class TeachingEvaluationDTO {

    private Long submissionId;

    /** 被评价的版本 ID，必须属于该成果。 */
    private Long versionId;

    /** PASS 通过 / REVISE 退回修改。 */
    private String decision;

    /** PASS 时必填。 */
    private Integer score;

    private String comment;
}
