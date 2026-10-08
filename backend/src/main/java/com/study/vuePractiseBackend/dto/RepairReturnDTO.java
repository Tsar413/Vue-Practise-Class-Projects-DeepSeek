package com.study.vuePractiseBackend.dto;

import lombok.Data;

/** 报修人退回维修请求。 */
@Data
public class RepairReturnDTO {
    private Long operatorId;
    private String content;
}
