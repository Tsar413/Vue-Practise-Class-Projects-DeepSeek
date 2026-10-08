package com.study.vuePractiseBackend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 登录结果。apiAccessCode 只对学生返回。 */
@Data
@NoArgsConstructor
public class SysLoginReturnDTO {
    /** 内部状态：1 成功，负数表示各类失败原因。 */
    private Integer status;
    private String userId;
    private String token;
    private String apiAccessCode;
    private String realName;
    private String role;
}
