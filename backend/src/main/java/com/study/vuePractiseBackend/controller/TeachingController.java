package com.study.vuePractiseBackend.controller;

import com.study.vuePractiseBackend.common.Result;
import com.study.vuePractiseBackend.dto.TeachingEvaluationDTO;
import com.study.vuePractiseBackend.dto.TeachingStatusDTO;
import com.study.vuePractiseBackend.dto.TeachingSubmissionDTO;
import com.study.vuePractiseBackend.dto.TeachingTaskDTO;
import com.study.vuePractiseBackend.entity.SysUser;
import com.study.vuePractiseBackend.interceptor.SysLoginInterceptor;
import com.study.vuePractiseBackend.service.TeachingSubmissionService;
import com.study.vuePractiseBackend.service.TeachingTaskService;
import com.study.vuePractiseBackend.vo.EvaluationVO;
import com.study.vuePractiseBackend.vo.TeacherSubmissionRowVO;
import com.study.vuePractiseBackend.vo.TeacherTaskStatsVO;
import com.study.vuePractiseBackend.vo.TeachingAttachmentVO;
import com.study.vuePractiseBackend.vo.TeachingSubmissionVO;
import com.study.vuePractiseBackend.vo.TeachingTaskVO;
import jakarta.annotation.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 实训任务、学生成果、教师评价与截图接口。
 *
 * 身份与班级一律来自登录 Token（请求属性），不接受客户端提交的学号；
 * 教师接口与学生会话（AI 辅导）严格分开。
 */
@RestController
@RequestMapping("/api/teaching")
public class TeachingController {

    @Resource
    private TeachingTaskService taskService;

    @Resource
    private TeachingSubmissionService submissionService;

    // ==================================================================
    // 教师：任务管理
    // ==================================================================

    @GetMapping("/tasks")
    public ResponseEntity<Result<List<TeachingTaskVO>>> listTasks(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role) {
        if ("TEACHER".equals(role)) {
            String teacherId = taskService.requireTeacher(userId, role);
            return ResponseEntity.ok(Result.success(taskService.listForTeacher(teacherId)));
        }
        SysUser student = taskService.requireStudent(userId, role);
        return ResponseEntity.ok(Result.success(taskService.listForStudent(student)));
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<Result<TeachingTaskVO>> taskDetail(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id) {
        if ("TEACHER".equals(role)) {
            taskService.requireTeacher(userId, role);
            return ResponseEntity.ok(Result.success(taskService.detailForTeacher(id)));
        }
        SysUser student = taskService.requireStudent(userId, role);
        return ResponseEntity.ok(Result.success(taskService.detailForStudent(student, id)));
    }

    @PostMapping("/tasks")
    public ResponseEntity<Result<TeachingTaskVO>> createTask(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @RequestBody TeachingTaskDTO dto) {
        String teacherId = taskService.requireTeacher(userId, role);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new Result<>(201, "任务已创建（草稿）", taskService.createTask(teacherId, dto)));
    }

    @PutMapping("/tasks/{id}")
    public ResponseEntity<Result<TeachingTaskVO>> updateTask(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id,
            @RequestBody TeachingTaskDTO dto) {
        String teacherId = taskService.requireTeacher(userId, role);
        dto.setId(id);
        return ResponseEntity.ok(Result.success(taskService.updateTask(teacherId, dto)));
    }

    /** 发布 / 关闭。关闭只改状态，已有提交、版本、截图与评价全部保留。 */
    @PostMapping("/tasks/{id}/status")
    public ResponseEntity<Result<TeachingTaskVO>> changeTaskStatus(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id,
            @RequestBody TeachingStatusDTO dto) {
        String teacherId = taskService.requireTeacher(userId, role);
        return ResponseEntity.ok(Result.success(
                taskService.changeStatus(id, teacherId, dto == null ? null : dto.getAction())));
    }

    @GetMapping("/tasks/{id}/stats")
    public ResponseEntity<Result<TeacherTaskStatsVO>> taskStats(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id) {
        taskService.requireTeacher(userId, role);
        return ResponseEntity.ok(Result.success(taskService.stats(id)));
    }

    /** 按班级查看学生提交情况；classId 省略时返回全部分配班级。 */
    @GetMapping("/tasks/{id}/submissions")
    public ResponseEntity<Result<List<TeacherSubmissionRowVO>>> taskSubmissions(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id,
            @RequestParam(value = "classId", required = false) String classId) {
        taskService.requireTeacher(userId, role);
        return ResponseEntity.ok(Result.success(taskService.listSubmissions(id, classId)));
    }

    /** 教师查看某个学生成果的全部历史版本与评价。 */
    @GetMapping("/submissions/{id}")
    public ResponseEntity<Result<TeachingSubmissionVO>> submissionDetail(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id) {
        taskService.requireTeacher(userId, role);
        return ResponseEntity.ok(Result.success(submissionService.detailForTeacher(id)));
    }

    /** 教师给分或退回；实际评分教师取自登录身份。 */
    @PostMapping("/evaluations")
    public ResponseEntity<Result<EvaluationVO>> evaluate(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @RequestBody TeachingEvaluationDTO dto) {
        String teacherId = taskService.requireTeacher(userId, role);
        return ResponseEntity.ok(Result.success(submissionService.evaluate(teacherId, dto)));
    }

    // ==================================================================
    // 学生：成果、草稿、提交、截图
    // ==================================================================

    @GetMapping("/tasks/{id}/submission")
    public ResponseEntity<Result<TeachingSubmissionVO>> mySubmission(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id) {
        SysUser student = taskService.requireStudent(userId, role);
        return ResponseEntity.ok(Result.success(submissionService.detailForStudent(student, id)));
    }

    @PutMapping("/tasks/{id}/draft")
    public ResponseEntity<Result<TeachingSubmissionVO>> saveDraft(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id,
            @RequestBody TeachingSubmissionDTO dto) {
        SysUser student = taskService.requireStudent(userId, role);
        return ResponseEntity.ok(Result.success(submissionService.saveDraft(student, id, dto)));
    }

    /** 正式提交：生成不可覆盖的新版本；requestKey 用于幂等。 */
    @PostMapping("/tasks/{id}/submissions")
    public ResponseEntity<Result<TeachingSubmissionVO>> submit(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id,
            @RequestBody TeachingSubmissionDTO dto) {
        SysUser student = taskService.requireStudent(userId, role);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new Result<>(201, "提交成功，等待教师评价",
                        submissionService.submit(student, id, dto)));
    }

    @PostMapping("/tasks/{id}/attachments")
    public ResponseEntity<Result<TeachingAttachmentVO>> uploadAttachment(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        SysUser student = taskService.requireStudent(userId, role);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new Result<>(201, "上传成功", submissionService.uploadAttachment(student, id, file)));
    }

    @GetMapping("/tasks/{id}/attachments")
    public ResponseEntity<Result<List<TeachingAttachmentVO>>> myAttachments(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id) {
        SysUser student = taskService.requireStudent(userId, role);
        return ResponseEntity.ok(Result.success(submissionService.listMyTempAttachments(student, id)));
    }

    @DeleteMapping("/attachments/{id}")
    public ResponseEntity<Result<Void>> deleteAttachment(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id) {
        SysUser student = taskService.requireStudent(userId, role);
        submissionService.deleteTempAttachment(student, id);
        return ResponseEntity.ok(new Result<>(200, "已删除", null));
    }

    /**
     * 截图读取：学生仅能读自己的，教师可读。
     * 响应带 nosniff 与固定的图片 Content-Type，避免被当作可执行内容。
     */
    @GetMapping("/attachments/{id}/content")
    public ResponseEntity<byte[]> attachmentContent(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String userId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable Long id) {
        TeachingAttachmentVO meta = submissionService.attachmentMeta(userId, role, id);
        byte[] bytes = submissionService.readAttachment(userId, role, id);
        MediaType type;
        try {
            type = MediaType.parseMediaType(meta.getContentType());
        } catch (RuntimeException e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(bytes);
    }
}
