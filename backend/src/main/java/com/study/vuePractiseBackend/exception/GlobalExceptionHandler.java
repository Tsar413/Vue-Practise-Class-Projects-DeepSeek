package com.study.vuePractiseBackend.exception;

import com.study.vuePractiseBackend.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常处理。
 * 关键约定：响应体 code 与 HTTP 状态码保持一致，message 为可直接展示的中文提示。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 路由层错误属于客户端错误，不应记录为未处理的服务端异常
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求Content-Type不受支持");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleUnsupportedMethod(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不受支持");
    }

    /** 请求体为空、JSON 格式错误或字段类型错误。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "请求体不能为空，请检查JSON格式和字段类型");
    }

    /** 缺少必填请求参数，例如没有传 status。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        return error(HttpStatus.BAD_REQUEST, "缺少必填参数：" + e.getParameterName());
    }

    /** 请求参数类型错误，例如 status=abc。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleParameterTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "参数类型错误：" + e.getName());
    }

    /** 主键或唯一字段重复。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("数据库唯一键冲突", e);
        return error(HttpStatus.CONFLICT, "记录已存在，请检查编号或其他唯一字段");
    }

    /** 数据库约束异常，例如必填字段缺失、字段长度超限。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        // 外键约束（删除被引用的账号，例如已有实训任务或成果档案）给明确业务提示，
        // 不泛化成 500，也不级联删除教学档案。
        if (isForeignKeyViolation(e)) {
            log.warn("外键约束阻止了删除操作");
            return error(HttpStatus.CONFLICT,
                    "该账号仍被业务数据引用（如实训任务、成果提交或辅导会话），请先处理关联数据后再删除");
        }
        log.error("数据库数据约束异常", e);
        return error(HttpStatus.CONFLICT, "数据不符合数据库约束，操作失败");
    }

    /** 判断是否为外键约束失败；只读取异常链中的错误码，不输出 SQL 或参数值。 */
    private boolean isForeignKeyViolation(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 8) {
            if (cur instanceof java.sql.SQLIntegrityConstraintViolationException sqlEx) {
                String state = sqlEx.getSQLState();
                int code = sqlEx.getErrorCode();
                // MySQL: 1451 父行被引用 / 1452 子行引用缺失；SQLState 23000 为完整性约束
                if (code == 1451 || code == 1452 || "23000".equals(state)) {
                    return true;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /** Service 主动抛出的状态异常，例如创建用户或工作空间失败。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> handleIllegalState(IllegalStateException e) {
        log.error("业务执行异常", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "操作失败，请稍后重试");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Object>> handleResponseStatusException(ResponseStatusException exception) {
        int code = exception.getStatusCode().value();
        return ResponseEntity.status(exception.getStatusCode())
                .body(new Result<>(code, exception.getReason(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /** Excel 格式或学生数据校验失败。 */
    @ExceptionHandler(ExcelImportException.class)
    public ResponseEntity<Result<Void>> handleExcelImport(ExcelImportException e) {
        // 文件解析的底层异常记录到后端日志
        if (e.getCause() != null) {
            log.warn("Excel文件解析失败", e);
        }
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 没有上传名为 file 的文件。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingFile(MissingServletRequestPartException e) {
        return error(HttpStatus.BAD_REQUEST, "缺少上传文件：" + e.getRequestPartName());
    }

    /** 文件超过上传大小限制。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件或请求超过大小限制，单个文件不能超过5MB");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result<Void>> handleMultipart(MultipartException e) {
        return error(HttpStatus.BAD_REQUEST, "请使用multipart/form-data上传，文件字段名为file");
    }

    /** 未单独处理的异常。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统未处理异常", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "系统异常，请稍后重试");
    }

    /** 保持 HTTP 状态码与响应体 code 一致。 */
    private ResponseEntity<Result<Void>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new Result<>(status.value(), message, null));
    }
}
