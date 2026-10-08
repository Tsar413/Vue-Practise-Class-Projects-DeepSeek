package com.study.vuePractiseBackend.dto;

import lombok.Data;

import java.util.List;

/** 提交维修结果请求，至少 1 张维修后图片。 */
@Data
public class RepairSubmitDTO {
    private Long operatorId;
    private String repairResult;
    private List<Long> imageIds;
}
