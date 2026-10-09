package com.study.vuePractiseBackend.service;

import com.study.vuePractiseBackend.dto.TeachingEvaluationDTO;
import com.study.vuePractiseBackend.dto.TeachingSubmissionDTO;
import com.study.vuePractiseBackend.entity.SysUser;
import com.study.vuePractiseBackend.vo.EvaluationVO;
import com.study.vuePractiseBackend.vo.TeachingAttachmentVO;
import com.study.vuePractiseBackend.vo.TeachingSubmissionVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 学生成果、截图与教师评价服务。 */
public interface TeachingSubmissionService {

    /** 学生查看本人在某任务的成果（含草稿与全部历史版本、每版评价）。 */
    TeachingSubmissionVO detailForStudent(SysUser student, Long taskId);

    /** 教师查看某个学生的成果（沿用教师范围规则）。 */
    TeachingSubmissionVO detailForTeacher(Long submissionId);

    /** 保存草稿。 */
    TeachingSubmissionVO saveDraft(SysUser student, Long taskId, TeachingSubmissionDTO dto);

    /**
     * 正式提交：生成不可覆盖的新版本。
     * requestKey 用于幂等——同一键的并发/重复请求不会生成第二个版本。
     */
    TeachingSubmissionVO submit(SysUser student, Long taskId, TeachingSubmissionDTO dto);

    /** 教师评价（给分或退回）。 */
    EvaluationVO evaluate(String teacherId, TeachingEvaluationDTO dto);

    TeachingAttachmentVO uploadAttachment(SysUser student, Long taskId, MultipartFile file);

    /** 学号与任务归属都校验后才返回附件内容。 */
    byte[] readAttachment(String actorId, String actorRole, Long attachmentId);

    TeachingAttachmentVO attachmentMeta(String actorId, String actorRole, Long attachmentId);

    void deleteTempAttachment(SysUser student, Long attachmentId);

    List<TeachingAttachmentVO> listMyTempAttachments(SysUser student, Long taskId);
}
