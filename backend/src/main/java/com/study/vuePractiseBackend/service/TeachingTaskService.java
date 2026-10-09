package com.study.vuePractiseBackend.service;

import com.study.vuePractiseBackend.dto.TeachingTaskDTO;
import com.study.vuePractiseBackend.entity.SysUser;
import com.study.vuePractiseBackend.entity.TeachingTask;
import com.study.vuePractiseBackend.vo.TeacherSubmissionRowVO;
import com.study.vuePractiseBackend.vo.TeacherTaskStatsVO;
import com.study.vuePractiseBackend.vo.TeachingTaskVO;

import java.util.List;

/** 实训任务服务。 */
public interface TeachingTaskService {

    /** 教师角色校验；返回教师工号。 */
    String requireTeacher(String actorId, String actorRole);

    /** 学生身份校验；返回学生账号（含班级）。 */
    SysUser requireStudent(String actorId, String actorRole);

    List<TeachingTaskVO> listForTeacher(String teacherId);

    List<TeachingTaskVO> listForStudent(SysUser student);

    TeachingTaskVO detailForTeacher(Long taskId);

    TeachingTaskVO detailForStudent(SysUser student, Long taskId);

    /** 学生访问任务时的统一校验：存在、非草稿、已分配到该生班级。 */
    TeachingTask requireVisibleToStudent(Long taskId, String classId);

    /** 教师访问任务。 */
    TeachingTask requireTaskForTeacher(Long taskId);

    TeachingTaskVO createTask(String teacherId, TeachingTaskDTO dto);

    TeachingTaskVO updateTask(String teacherId, TeachingTaskDTO dto);

    TeachingTaskVO changeStatus(Long taskId, String teacherId, String action);

    List<TeacherSubmissionRowVO> listSubmissions(Long taskId, String classId);

    TeacherTaskStatsVO stats(Long taskId);
}
