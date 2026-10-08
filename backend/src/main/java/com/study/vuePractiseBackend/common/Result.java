package com.study.vuePractiseBackend.common;

/**
 * 统一响应结构：code + message + data。
 * 与参考项目保持完全一致的字段名与取值约定。
 */
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public Result() {
    }

    public Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功返回，code 固定 200。 */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data);
    }

    /** 业务失败返回，code 固定 500。 */
    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }

    /** 未登录 / 凭证错误。 */
    public static <T> Result<T> unauthorized(String message) {
        return new Result<>(401, message, null);
    }

    /** 参数错误。 */
    public static <T> Result<T> badRequest(String message) {
        return new Result<>(400, message, null);
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "Result{" +
                "code=" + code +
                ", message='" + message + '\'' +
                ", data=" + data +
                '}';
    }
}
