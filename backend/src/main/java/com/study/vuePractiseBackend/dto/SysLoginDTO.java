package com.study.vuePractiseBackend.dto;

import lombok.Data;

/** 网页登录请求：id 为系统用户编号（学生为学号）。 */
@Data
public class SysLoginDTO {
    private String id;
    private String password;
}
