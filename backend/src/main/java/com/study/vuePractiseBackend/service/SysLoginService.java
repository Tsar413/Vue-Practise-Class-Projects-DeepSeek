package com.study.vuePractiseBackend.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.study.vuePractiseBackend.dto.SysLoginDTO;
import com.study.vuePractiseBackend.dto.SysLoginReturnDTO;
import com.study.vuePractiseBackend.entity.SysLoginToken;

public interface SysLoginService extends IService<SysLoginToken> {

    SysLoginReturnDTO login(SysLoginDTO sysLoginDTO);

    Integer logout(String rawToken);

    /** 账号停用时撤销网页登录，不影响长期 API 访问码。 */
    void revokeByUserId(String userId);

    /** 删除账号时一并删除登录记录与访问码。 */
    void deleteByUserId(String userId);
}
