package com.study.vuePractiseBackend.dto;

import lombok.Data;

/** 系统用户新建 / 修改请求。 */
@Data
public class SysUserDTO {
    private String id;
    private String username;
    private String realName;
    private String classId;
    private String role;
}
