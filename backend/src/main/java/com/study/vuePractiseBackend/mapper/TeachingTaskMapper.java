package com.study.vuePractiseBackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.study.vuePractiseBackend.entity.TeachingTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeachingTaskMapper extends BaseMapper<TeachingTask> {

    /** 对任务行加排他锁，用于「编辑/关闭」与「学生提交」之间的串行化。 */
    @Select("SELECT * FROM teaching_task WHERE id = #{id} FOR UPDATE")
    TeachingTask selectForUpdate(@Param("id") Long id);
}
