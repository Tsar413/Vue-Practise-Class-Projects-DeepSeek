package com.study.vuePractiseBackend.exception;

/** Excel 文件格式或学生数据校验失败。 */
public class ExcelImportException extends RuntimeException {

    public ExcelImportException(String message) {
        super(message);
    }

    public ExcelImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
