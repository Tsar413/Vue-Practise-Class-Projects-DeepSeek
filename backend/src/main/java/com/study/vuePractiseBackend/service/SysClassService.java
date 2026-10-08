package com.study.vuePractiseBackend.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.study.vuePractiseBackend.dto.SysClassDTO;
import com.study.vuePractiseBackend.entity.SysClass;

public interface SysClassService extends IService<SysClass> {

    Integer addNewClass(SysClassDTO sysClassDTO);

    Integer changeClass(SysClassDTO sysClassDTO);

    /** 删除班级时级联删除关联学生、凭证、工作空间与项目数据。 */
    Integer deleteById(String id);

    /** 启停班级时同步关联学生与其工作空间状态。 */
    Integer changeClassStatus(String id, int status);
}
