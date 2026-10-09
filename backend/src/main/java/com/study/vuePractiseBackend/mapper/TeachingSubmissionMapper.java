package com.study.vuePractiseBackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.study.vuePractiseBackend.entity.TeachingSubmission;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeachingSubmissionMapper extends BaseMapper<TeachingSubmission> {

    /**
     * 对「任务 + 学生」的成果行加排他锁。
     *
     * 必须在事务中调用：同一学生同一任务的提交因此串行执行，
     * 版本号在同一把锁内递增，重复点击不会生成两个版本。
     * 行不存在时返回 null，调用方需要先插入再重新加锁。
     */
    @Select("SELECT * FROM teaching_submission WHERE task_id = #{taskId} AND student_id = #{studentId} FOR UPDATE")
    TeachingSubmission selectForUpdate(@Param("taskId") Long taskId,
                                      @Param("studentId") String studentId);

    /**
     * 保证「任务 + 学生」的成果行存在。
     * 使用 INSERT IGNORE：并发首次写入时只有一条成功，其余忽略，
     * 不会因为「先查后插」撞唯一键，也不会出现 INSERT IGNORE 后再加锁的等待环。
     */
    @Insert("""
            INSERT IGNORE INTO teaching_submission
                (task_id, student_id, class_id, status, version_no, late, create_time, update_time)
            VALUES
                (#{taskId}, #{studentId}, #{classId}, 0, 0, 0, #{now}, #{now})
            """)
    int insertIgnore(@Param("taskId") Long taskId,
                     @Param("studentId") String studentId,
                     @Param("classId") String classId,
                     @Param("now") java.time.LocalDateTime now);
}
