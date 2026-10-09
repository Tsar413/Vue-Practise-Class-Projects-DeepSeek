package com.study.vuePractiseBackend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 业务异常工厂，统一使用 ResponseStatusException 携带 HTTP 语义。 */
public final class BusinessExceptions {

    private BusinessExceptions() {
    }

    public static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    public static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    public static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    /**
     * 依赖的外部能力暂不可用（例如 AI 辅导尚未配置或上游异常）。
     * 刻意不使用 401：上游 AI 服务的 401 不代表本系统登录失效，
     * 返回 401 会让前端误以为需要重新登录。
     */
    public static ResponseStatusException serviceUnavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
