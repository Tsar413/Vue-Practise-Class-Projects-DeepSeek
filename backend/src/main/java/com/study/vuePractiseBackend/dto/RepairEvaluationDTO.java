package com.study.vuePractiseBackend.dto;

import lombok.Data;

/** 工单评价请求。 */
@Data
public class RepairEvaluationDTO {
    private Long operatorId;
    private Integer score;
    private String content;
}
