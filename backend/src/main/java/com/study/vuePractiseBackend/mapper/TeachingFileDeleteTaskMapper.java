package com.study.vuePractiseBackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.study.vuePractiseBackend.entity.TeachingFileDeleteTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeachingFileDeleteTaskMapper extends BaseMapper<TeachingFileDeleteTask> {

    /** 是否已有未处理的删除任务（避免重复排队）。 */
    @Select("""
            SELECT COUNT(*) FROM teaching_file_delete_task
             WHERE image_url = #{imageUrl} AND processed = 0
            """)
    long countPendingByUrl(@Param("imageUrl") String imageUrl);
}
