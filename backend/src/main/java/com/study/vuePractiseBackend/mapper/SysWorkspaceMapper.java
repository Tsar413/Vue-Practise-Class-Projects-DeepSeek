package com.study.vuePractiseBackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.study.vuePractiseBackend.entity.SysWorkspace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysWorkspaceMapper extends BaseMapper<SysWorkspace> {

    /**
     * 按学号加行锁读取工作空间。
     * 初始化、重置、删除与各业务写操作共用这把锁，保证串行。
     */
    @Select("SELECT * FROM sys_workspace WHERE student_id = #{studentId} FOR UPDATE")
    SysWorkspace selectByStudentIdForUpdate(@Param("studentId") String studentId);
}
