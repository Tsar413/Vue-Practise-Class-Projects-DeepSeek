package com.study.vuePractiseBackend.dto;

import lombok.Data;

/** 报修模拟用户新建 / 修改请求。 */
@Data
public class RepairUserDTO {

    /** 当前管理员数据库 ID。 */
    private Long operatorId;

    /** 创建时填写，修改时不允许改变。 */
    private String userNo;

    private String realName;

    private String phone;

    private String department;

    /** REPORTER、MAINTAINER、ADMIN。 */
    private String role;

    /** 0 停用、1 启用。 */
    private Integer status;
}
