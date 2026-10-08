package com.study.vuePractiseBackend.dto;

import lombok.Data;

/** 派单请求。 */
@Data
public class RepairAssignDTO {
    private Long operatorId;
    private Long maintainerId;
    private String content;
}
