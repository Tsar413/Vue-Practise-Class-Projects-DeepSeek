package com.study.vuePractiseBackend.controller;

import com.study.vuePractiseBackend.common.Result;
import com.study.vuePractiseBackend.dto.AiTutorAskDTO;
import com.study.vuePractiseBackend.dto.AiTutorConversationDTO;
import com.study.vuePractiseBackend.interceptor.SysLoginInterceptor;
import com.study.vuePractiseBackend.service.AiTutorService;
import com.study.vuePractiseBackend.vo.AiTutorAnswerVO;
import com.study.vuePractiseBackend.vo.AiTutorConversationVO;
import com.study.vuePractiseBackend.vo.AiTutorMessageVO;
import jakarta.annotation.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.study.vuePractiseBackend.exception.BusinessExceptions.forbidden;

/**
 * AI 辅导接口（学生私有）。
 * 身份一律取自登录拦截器写入的 request attribute，不从请求体读取；
 * 教师不能查看学生私聊，因此这里只放行 STUDENT，其余角色统一 403。
 */
@RestController
@RequestMapping("/api/ai-tutor")
public class AiTutorController {

    private static final String STUDENT_ROLE = "STUDENT";

    /** 非学生访问时的统一提示。 */
    private static final String ONLY_STUDENT = "仅学生可以使用 AI 辅导";

    @Resource
    private AiTutorService aiTutorService;

    @GetMapping("/conversations")
    public ResponseEntity<Result<List<AiTutorConversationVO>>> listConversations(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String studentId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role) {
        requireStudent(role);
        return ResponseEntity.ok(Result.success(aiTutorService.listConversations(studentId)));
    }

    @PostMapping("/conversations")
    public ResponseEntity<Result<AiTutorConversationVO>> createConversation(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String studentId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @RequestBody AiTutorConversationDTO dto) {
        requireStudent(role);
        return ResponseEntity.ok(Result.success(aiTutorService.createConversation(studentId, dto)));
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Result<Void>> deleteConversation(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String studentId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable("id") Long id) {
        requireStudent(role);
        aiTutorService.deleteConversation(studentId, id);
        return ResponseEntity.ok(Result.<Void>success(null));
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<Result<List<AiTutorMessageVO>>> listMessages(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String studentId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @PathVariable("id") Long id) {
        requireStudent(role);
        return ResponseEntity.ok(Result.success(aiTutorService.listMessages(studentId, id)));
    }

    @PostMapping("/ask")
    public ResponseEntity<Result<AiTutorAnswerVO>> ask(
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ID) String studentId,
            @RequestAttribute(SysLoginInterceptor.LOGIN_USER_ROLE) String role,
            @RequestBody AiTutorAskDTO dto) {
        requireStudent(role);
        return ResponseEntity.ok(Result.success(aiTutorService.ask(studentId, dto)));
    }

    /** 教师或未知角色一律 403；不区分「会话不存在」，避免泄露他人会话是否存在。 */
    private void requireStudent(String role) {
        if (!STUDENT_ROLE.equals(role)) {
            throw forbidden(ONLY_STUDENT);
        }
    }
}
