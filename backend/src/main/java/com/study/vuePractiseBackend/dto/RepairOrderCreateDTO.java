package com.study.vuePractiseBackend.dto;

import lombok.Data;

import java.util.List;

/** 创建报修工单请求。deviceId 为空时，设备名称 / 类型 / 校区 / 地点必填。 */
@Data
public class RepairOrderCreateDTO {

    private Long operatorId;

    private Long deviceId;

    private String deviceName;
    private String deviceType;
    private String campus;
    private String location;

    private String title;
    private String description;
    private String contactPhone;

    /** repair_attachment.id 列表。 */
    private List<Long> imageIds;
}
