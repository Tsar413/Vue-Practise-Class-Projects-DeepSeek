package com.study.vuePractiseBackend.dto;

import lombok.Data;

/** 设备新建 / 修改请求。 */
@Data
public class RepairDeviceDTO {
    /** 模拟管理员数据库 ID。 */
    private Long operatorId;
    private String deviceNo;
    private String deviceName;
    private String deviceType;
    private String campus;
    private String location;
    private String remark;
}
