package com.study.vuePractiseBackend.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 学生批量导入结果。details 只记录跳过和失败的行。 */
@Data
public class StudentImportResultDTO {

    private int total;

    private int successCount;

    private int skippedCount;

    private int failedCount;

    private List<RowResult> details = new ArrayList<>();

    public record RowResult(int rowNumber, String studentId, String status, String message) {
    }
}
