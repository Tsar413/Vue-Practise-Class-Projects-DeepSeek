package com.study.vuePractiseBackend.dto;

import lombok.Data;

import java.util.List;

/** 追加维修记录请求。 */
@Data
public class RepairProcessDTO {
    private Long operatorId;
    private String content;
    private List<Long> imageIds;
}
