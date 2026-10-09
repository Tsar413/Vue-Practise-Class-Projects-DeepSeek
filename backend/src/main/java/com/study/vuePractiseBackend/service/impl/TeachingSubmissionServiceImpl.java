package com.study.vuePractiseBackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.study.vuePractiseBackend.dto.SubmissionSectionDTO;
import com.study.vuePractiseBackend.dto.TeachingEvaluationDTO;
import com.study.vuePractiseBackend.dto.TeachingSubmissionDTO;
import com.study.vuePractiseBackend.entity.SysUser;
import com.study.vuePractiseBackend.entity.TeachingAttachment;
import com.study.vuePractiseBackend.entity.TeachingEvaluation;
import com.study.vuePractiseBackend.entity.TeachingFileDeleteTask;
import com.study.vuePractiseBackend.entity.TeachingSectionAttachment;
import com.study.vuePractiseBackend.entity.TeachingSubmission;
import com.study.vuePractiseBackend.entity.TeachingSubmissionSection;
import com.study.vuePractiseBackend.entity.TeachingSubmissionVersion;
import com.study.vuePractiseBackend.entity.TeachingSubmitRequest;
import com.study.vuePractiseBackend.entity.TeachingTask;
import com.study.vuePractiseBackend.entity.TeachingVersionAttachment;
import com.study.vuePractiseBackend.exception.BusinessExceptions;
import com.study.vuePractiseBackend.mapper.SysUserMapper;
import com.study.vuePractiseBackend.mapper.TeachingAttachmentMapper;
import com.study.vuePractiseBackend.mapper.TeachingEvaluationMapper;
import com.study.vuePractiseBackend.mapper.TeachingFileDeleteTaskMapper;
import com.study.vuePractiseBackend.mapper.TeachingSectionAttachmentMapper;
import com.study.vuePractiseBackend.mapper.TeachingSubmissionMapper;
import com.study.vuePractiseBackend.mapper.TeachingSubmissionSectionMapper;
import com.study.vuePractiseBackend.mapper.TeachingSubmissionVersionMapper;
import com.study.vuePractiseBackend.mapper.TeachingSubmitRequestMapper;
import com.study.vuePractiseBackend.mapper.TeachingTaskMapper;
import com.study.vuePractiseBackend.mapper.TeachingVersionAttachmentMapper;
import com.study.vuePractiseBackend.service.TeachingSubmissionService;
import com.study.vuePractiseBackend.service.TeachingTaskService;
import com.study.vuePractiseBackend.util.TeachingFileUtil;
import com.study.vuePractiseBackend.vo.EvaluationVO;
import com.study.vuePractiseBackend.vo.SubmissionSectionVO;
import com.study.vuePractiseBackend.vo.SubmissionVersionVO;
import com.study.vuePractiseBackend.vo.TeachingAttachmentVO;
import com.study.vuePractiseBackend.vo.TeachingSubmissionVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 学生成果、截图与教师评价。
 *
 * 事务：写操作统一通过 TransactionTemplate 显式开启。
 * 同一个类内部的 this 调用不经过 Spring 代理，注解式 @Transactional 自调用不会生效，
 * 因此不能依赖注解。
 *
 * 锁顺序统一为 teaching_task -> teaching_submission -> 版本/评价。
 *
 * 历史不可变性：
 *   * 版本与截图的关系写在 teaching_version_attachment，只增不改；
 *     重交引用同一张截图只会新增关联行，绝不把旧版本的截图移动到新版本；
 *   * 草稿与截图的关系写在 teaching_section_attachment，可被替换；
 *   * 附件行只做软删除；磁盘文件仅在「已无任何版本引用」时才进入清理队列，
 *     清理任务执行前还会再确认一次没有版本引用。
 */
@Slf4j
@Service
public class TeachingSubmissionServiceImpl implements TeachingSubmissionService {

    private static final int MAX_CONTENT = 20000;
    private static final int MAX_SECTIONS = 20;
    private static final int MAX_SECTION_TITLE = 200;
    private static final int MAX_COMMENT = 2000;
    private static final int MAX_ATTACHMENTS = 60;

    @Resource
    private TeachingTaskService taskService;

    @Resource
    private TeachingTaskMapper taskMapper;

    @Resource
    private TeachingSubmissionMapper submissionMapper;

    @Resource
    private TeachingSubmissionVersionMapper versionMapper;

    @Resource
    private TeachingSubmissionSectionMapper sectionMapper;

    @Resource
    private TeachingAttachmentMapper attachmentMapper;

    @Resource
    private TeachingVersionAttachmentMapper versionAttachmentMapper;

    @Resource
    private TeachingSectionAttachmentMapper sectionAttachmentMapper;

    @Resource
    private TeachingFileDeleteTaskMapper deleteTaskMapper;

    @Resource
    private TeachingEvaluationMapper evaluationMapper;

    @Resource
    private TeachingSubmitRequestMapper submitRequestMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private TeachingFileUtil fileUtil;

    @Resource
    private PlatformTransactionManager transactionManager;

    // ==================================================================
    // 查询
    // ==================================================================

    @Override
    public TeachingSubmissionVO detailForStudent(SysUser student, Long taskId) {
        TeachingTask task = taskService.requireVisibleToStudent(taskId, student.getClassId());
        TeachingSubmission submission = findSubmission(task.getId(), student.getId());
        if (submission == null) {
            TeachingSubmissionVO vo = new TeachingSubmissionVO();
            vo.setTaskId(task.getId());
            vo.setStudentId(student.getId());
            vo.setStudentName(student.getRealName());
            vo.setClassId(student.getClassId());
            vo.setStatus(TeachingSubmission.STATUS_DRAFT);
            vo.setVersionNo(0);
            vo.setLate(false);
            vo.setVersions(List.of());
            return vo;
        }
        return buildVO(submission);
    }

    @Override
    public TeachingSubmissionVO detailForTeacher(Long submissionId) {
        TeachingSubmission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw BusinessExceptions.notFound("成果提交不存在");
        }
        return buildVO(submission);
    }

    private TeachingSubmissionVO buildVO(TeachingSubmission submission) {
        TeachingSubmissionVO vo = new TeachingSubmissionVO();
        vo.setId(submission.getId());
        vo.setTaskId(submission.getTaskId());
        vo.setStudentId(submission.getStudentId());
        vo.setClassId(submission.getClassId());
        vo.setStatus(submission.getStatus());
        vo.setVersionNo(submission.getVersionNo());
        vo.setLate(Integer.valueOf(1).equals(submission.getLate()));
        vo.setCreateTime(submission.getCreateTime());
        vo.setUpdateTime(submission.getUpdateTime());

        SysUser student = sysUserMapper.selectById(submission.getStudentId());
        if (student != null) {
            vo.setStudentName(student.getRealName());
        }

        List<TeachingSubmissionSection> liveSections = sectionMapper.selectList(
                new QueryWrapper<TeachingSubmissionSection>()
                        .eq("submission_id", submission.getId())
                        .isNull("deleted_at")
                        .orderByAsc("sort_order").orderByAsc("id"));
        Map<Long, List<TeachingAttachmentVO>> bySection = attachmentsBySection(liveSections);

        // 草稿：字段与章节都要能完整恢复（刷新、重新登录后仍在）
        List<TeachingSubmissionSection> draftSections = liveSections.stream()
                .filter(s -> Integer.valueOf(1).equals(s.getIsDraft())).toList();
        boolean hasDraftContent = submission.getDraftContent() != null
                || submission.getDraftProjectUrl() != null
                || submission.getDraftProcess() != null;
        if (!draftSections.isEmpty() || hasDraftContent) {
            SubmissionVersionVO draft = new SubmissionVersionVO();
            draft.setVersionNo(0);
            draft.setProjectUrl(submission.getDraftProjectUrl());
            draft.setContent(submission.getDraftContent());
            draft.setProcess(submission.getDraftProcess());
            draft.setCreateTime(submission.getDraftUpdateTime());
            draft.setSections(draftSections.stream().map(s -> toSectionVO(s, bySection)).toList());
            vo.setDraft(draft);
        }

        List<TeachingSubmissionVersion> versions = versionMapper.selectList(
                new QueryWrapper<TeachingSubmissionVersion>()
                        .eq("submission_id", submission.getId())
                        .orderByDesc("version_no"));
        Map<Long, List<EvaluationVO>> evalByVersion = evaluationsOf(submission.getId());
        List<SubmissionVersionVO> versionVOs = new ArrayList<>();
        for (TeachingSubmissionVersion version : versions) {
            SubmissionVersionVO v = new SubmissionVersionVO();
            v.setId(version.getId());
            v.setVersionNo(version.getVersionNo());
            v.setProjectUrl(version.getProjectUrl());
            v.setContent(version.getContent());
            v.setProcess(version.getProcess());
            v.setLate(Integer.valueOf(1).equals(version.getLate()));
            v.setCreateTime(version.getCreateTime());
            v.setEvaluations(evalByVersion.getOrDefault(version.getId(), List.of()));
            v.setSections(frozenSectionsOf(version));
            versionVOs.add(v);
        }
        vo.setVersions(versionVOs);

        TeachingEvaluation current = evaluationMapper.selectOne(
                new QueryWrapper<TeachingEvaluation>()
                        .eq("submission_id", submission.getId())
                        .eq("is_current", 1)
                        .orderByDesc("id").last("LIMIT 1"));
        if (current != null) {
            vo.setCurrentEvaluation(toEvaluationVO(current));
        }
        return vo;
    }

    /**
     * 历史版本展示以 teaching_version_attachment 的不可变关联为准：
     * 即使章节后来被软删、附件被软删，历史版本仍完整可读。
     */
    private List<SubmissionSectionVO> frozenSectionsOf(TeachingSubmissionVersion version) {
        List<TeachingVersionAttachment> links = versionAttachmentMapper.selectList(
                new QueryWrapper<TeachingVersionAttachment>()
                        .eq("version_id", version.getId())
                        .orderByAsc("id"));
        if (links.isEmpty()) {
            return List.of();
        }
        Set<Long> sectionIds = new LinkedHashSet<>();
        Set<Long> attachmentIds = new LinkedHashSet<>();
        for (TeachingVersionAttachment link : links) {
            attachmentIds.add(link.getAttachmentId());
            if (link.getSectionId() != null) {
                sectionIds.add(link.getSectionId());
            }
        }
        Map<Long, TeachingSubmissionSection> sectionById = new LinkedHashMap<>();
        if (!sectionIds.isEmpty()) {
            for (TeachingSubmissionSection section : sectionMapper.selectBatchIds(sectionIds)) {
                sectionById.put(section.getId(), section);
            }
        }
        Map<Long, TeachingAttachmentVO> attachmentById = new LinkedHashMap<>();
        for (TeachingAttachment attachment : attachmentMapper.selectBatchIds(attachmentIds)) {
            attachmentById.put(attachment.getId(), toAttachmentVO(attachment));
        }

        Map<Long, SubmissionSectionVO> grouped = new LinkedHashMap<>();
        List<Long> order = new ArrayList<>();
        for (TeachingVersionAttachment link : links) {
            Long key = link.getSectionId() == null ? 0L : link.getSectionId();
            SubmissionSectionVO sectionVO = grouped.get(key);
            if (sectionVO == null) {
                sectionVO = new SubmissionSectionVO();
                TeachingSubmissionSection section = sectionById.get(key);
                sectionVO.setId(key == 0L ? null : key);
                sectionVO.setTitle(section == null ? "成果截图" : section.getTitle());
                sectionVO.setContent(section == null ? "" : section.getContent());
                sectionVO.setSortOrder(section == null ? 0 : section.getSortOrder());
                sectionVO.setAttachments(new ArrayList<>());
                grouped.put(key, sectionVO);
                order.add(key);
            }
            TeachingAttachmentVO attachmentVO = attachmentById.get(link.getAttachmentId());
            if (attachmentVO != null) {
                sectionVO.getAttachments().add(attachmentVO);
            }
        }
        List<SubmissionSectionVO> result = new ArrayList<>();
        for (Long key : order) {
            result.add(grouped.get(key));
        }
        return result;
    }

    private Map<Long, List<TeachingAttachmentVO>> attachmentsBySection(
            List<TeachingSubmissionSection> sections) {
        if (sections.isEmpty()) {
            return Map.of();
        }
        List<Long> sectionIds = sections.stream().map(TeachingSubmissionSection::getId).toList();
        List<TeachingSectionAttachment> links = sectionAttachmentMapper.selectList(
                new QueryWrapper<TeachingSectionAttachment>()
                        .in("section_id", sectionIds).orderByAsc("id"));
        if (links.isEmpty()) {
            return Map.of();
        }
        Set<Long> attachmentIds = new LinkedHashSet<>();
        for (TeachingSectionAttachment link : links) {
            attachmentIds.add(link.getAttachmentId());
        }
        Map<Long, TeachingAttachmentVO> attachmentById = new LinkedHashMap<>();
        for (TeachingAttachment attachment : attachmentMapper.selectBatchIds(attachmentIds)) {
            attachmentById.put(attachment.getId(), toAttachmentVO(attachment));
        }
        Map<Long, List<TeachingAttachmentVO>> result = new HashMap<>();
        for (TeachingSectionAttachment link : links) {
            TeachingAttachmentVO vo = attachmentById.get(link.getAttachmentId());
            if (vo != null) {
                result.computeIfAbsent(link.getSectionId(), k -> new ArrayList<>()).add(vo);
            }
        }
        return result;
    }

    private Map<Long, List<EvaluationVO>> evaluationsOf(Long submissionId) {
        List<TeachingEvaluation> evaluations = evaluationMapper.selectList(
                new QueryWrapper<TeachingEvaluation>()
                        .eq("submission_id", submissionId).orderByAsc("id"));
        Map<String, String> teacherNames = teacherNames(evaluations);
        Map<Long, List<EvaluationVO>> result = new LinkedHashMap<>();
        for (TeachingEvaluation evaluation : evaluations) {
            EvaluationVO vo = toEvaluationVO(evaluation);
            vo.setTeacherName(teacherNames.get(evaluation.getTeacherId()));
            result.computeIfAbsent(evaluation.getVersionId(), k -> new ArrayList<>()).add(vo);
        }
        return result;
    }

    private Map<String, String> teacherNames(List<TeachingEvaluation> evaluations) {
        Set<String> ids = new LinkedHashSet<>();
        for (TeachingEvaluation evaluation : evaluations) {
            if (evaluation.getTeacherId() != null) {
                ids.add(evaluation.getTeacherId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (SysUser teacher : sysUserMapper.selectBatchIds(ids)) {
            names.put(teacher.getId(), teacher.getRealName());
        }
        return names;
    }

    private EvaluationVO toEvaluationVO(TeachingEvaluation evaluation) {
        EvaluationVO vo = new EvaluationVO();
        vo.setId(evaluation.getId());
        vo.setVersionId(evaluation.getVersionId());
        vo.setVersionNo(evaluation.getVersionNo());
        vo.setTeacherId(evaluation.getTeacherId());
        vo.setDecision(evaluation.getDecision());
        vo.setScore(evaluation.getScore());
        vo.setComment(evaluation.getComment());
        vo.setCurrent(Integer.valueOf(1).equals(evaluation.getIsCurrent()));
        vo.setCreateTime(evaluation.getCreateTime());
        return vo;
    }

    private SubmissionSectionVO toSectionVO(TeachingSubmissionSection section,
                                            Map<Long, List<TeachingAttachmentVO>> attachments) {
        SubmissionSectionVO vo = new SubmissionSectionVO();
        vo.setId(section.getId());
        vo.setTitle(section.getTitle());
        vo.setContent(section.getContent());
        vo.setSortOrder(section.getSortOrder());
        vo.setAttachments(attachments.getOrDefault(section.getId(), List.of()));
        return vo;
    }

    private TeachingAttachmentVO toAttachmentVO(TeachingAttachment attachment) {
        TeachingAttachmentVO vo = new TeachingAttachmentVO();
        vo.setId(attachment.getId());
        vo.setOriginalName(attachment.getOriginalName());
        vo.setContentType(attachment.getContentType());
        vo.setFileSize(attachment.getFileSize());
        vo.setImageUrl(attachment.getImageUrl());
        vo.setCreateTime(attachment.getCreateTime());
        return vo;
    }

    // ==================================================================
    // 草稿与正式提交
    // ==================================================================

    @Override
    public TeachingSubmissionVO saveDraft(SysUser student, Long taskId, TeachingSubmissionDTO dto) {
        return inTransaction(() -> writeSubmission(student, taskId, dto, false));
    }

    @Override
    public TeachingSubmissionVO submit(SysUser student, Long taskId, TeachingSubmissionDTO dto) {
        try {
            return inTransaction(() -> writeSubmission(student, taskId, dto, true));
        } catch (DuplicateSubmitSignal | DuplicateKeyException e) {
            // 幂等命中或并发建行冲突：事务已完整回滚，重新读取当前成果返回，不生成第二个版本
            TeachingSubmission submission = findSubmission(taskId, student.getId());
            if (submission == null) {
                throw BusinessExceptions.conflict("提交正在处理中，请稍后刷新查看结果");
            }
            return buildVO(submission);
        }
    }

    /**
     * 显式事务包装（泛型返回，供成果、截图、评价等不同返回类型复用）。
     *
     * 隔离级别固定为 READ_COMMITTED：MySQL 默认 REPEATABLE READ 下，
     * 事务内第一次普通 SELECT 就会建立快照，之后即使拿到了行锁，
     * 普通 SELECT 仍然是快照读，会看不到其他事务刚提交的变更。
     * 统一用 READ_COMMITTED 后，锁内校验读取的都是最新已提交数据。
     */
    private <T> T inTransaction(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template.execute(status -> action.get());
    }

    /** 无返回值的显式事务包装。 */
    private void inTransactionVoid(Runnable action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.execute(status -> {
            action.run();
            return null;
        });
    }

    private TeachingSubmissionVO writeSubmission(SysUser student, Long taskId,
                                                 TeachingSubmissionDTO dto, boolean formal) {
        // 身份与任务可见范围初筛（锁外快速失败）
        TeachingTask task = taskService.requireVisibleToStudent(taskId, student.getClassId());

        // 1) 锁任务行（与教师编辑/关闭/取消班级分配串行）
        TeachingTask locked = taskMapper.selectForUpdate(task.getId());
        if (locked == null) {
            throw BusinessExceptions.notFound("任务不存在");
        }

        // 1.1) 锁内重新校验：初筛与加锁之间，教师可能已经改项目/取消本班分配/关闭任务。
        //      这里必须用「加锁后当前读」的结果再判一次，否则会出现
        //      「先通过校验、后被移出班级仍然写入成果」的竞态。
        taskService.requireVisibleToStudent(locked.getId(), student.getClassId());
        if (formal && Integer.valueOf(TeachingTask.STATUS_CLOSED).equals(locked.getStatus())) {
            throw BusinessExceptions.conflict("任务已关闭，不能再提交");
        }
        // 草稿策略：任务关闭后统一不允许再保存草稿（与前端提示保持一致），
        // 关闭的任务对学生只读，需要修改请由教师重新发布或另建任务。
        if (!formal && Integer.valueOf(TeachingTask.STATUS_CLOSED).equals(locked.getStatus())) {
            throw BusinessExceptions.conflict("任务已关闭，不能再保存草稿");
        }

        // 2) 保证成果行存在，并在锁内当前读
        insertIgnoreSubmission(locked.getId(), student);
        TeachingSubmission submission = submissionMapper.selectForUpdate(locked.getId(), student.getId());
        if (submission == null) {
            throw new IllegalStateException("成果记录创建后未查询到");
        }

        if (!formal) {
            saveDraft(submission, dto);
            return buildVO(submission);
        }

        // 3) 幂等优先：同一 requestKey 直接返回既有成果，不再校验新内容与附件
        String requestKey = requireRequestKey(dto.getRequestKey());
        TeachingSubmitRequest existingRequest = submitRequestMapper.selectOne(
                new QueryWrapper<TeachingSubmitRequest>()
                        .eq("submission_id", submission.getId())
                        .eq("request_key", requestKey));
        if (existingRequest != null) {
            return buildVO(submission);
        }

        // 4) 提交资格与内容校验（此时才校验附件，避免重试被已冻结截图挡住）
        requireSubmitAllowed(locked);
        List<SubmissionSectionDTO> sections = normalizeSections(dto.getSections());
        AttachmentPlan plan = loadReusableAttachments(student, locked.getId(), sections);
        String content = requireContent(dto.getContent());
        String projectUrl = TeachingTaskServiceImpl.requireHttpUrl(dto.getProjectUrl(), "成果链接", false);
        String process = optionalText(dto.getProcess(), "问题与解决过程", MAX_CONTENT);
        if ((projectUrl == null || projectUrl.isBlank()) && plan.attachments().isEmpty()) {
            throw BusinessExceptions.badRequest("请至少填写有效的成果链接或上传一张成果截图");
        }

        boolean late = locked.getDeadline() != null && LocalDateTime.now().isAfter(locked.getDeadline());
        int nextVersion = (submission.getVersionNo() == null ? 0 : submission.getVersionNo()) + 1;
        LocalDateTime now = LocalDateTime.now();

        // 5) 占幂等键：重复请求在这里撞唯一键
        TeachingSubmitRequest request = new TeachingSubmitRequest();
        request.setSubmissionId(submission.getId());
        request.setRequestKey(requestKey);
        request.setCreateTime(now);
        try {
            submitRequestMapper.insert(request);
        } catch (DuplicateKeyException e) {
            throw new DuplicateSubmitSignal();
        }

        // 6) 生成不可覆盖的版本
        TeachingSubmissionVersion version = new TeachingSubmissionVersion();
        version.setSubmissionId(submission.getId());
        version.setTaskId(locked.getId());
        version.setStudentId(student.getId());
        version.setVersionNo(nextVersion);
        version.setProjectUrl(projectUrl);
        version.setContent(content);
        version.setProcess(process);
        version.setLate(late ? 1 : 0);
        version.setCreateTime(now);
        if (versionMapper.insert(version) != 1) {
            throw new IllegalStateException("创建提交版本失败");
        }

        // 7) 冻结章节并写入不可变版本-附件关联（同一文件可被多个版本引用）
        Map<Integer, Long> sectionIdByOrder = insertFrozenSections(submission, version, sections, now);
        Set<Long> usedAttachmentIds = new LinkedHashSet<>();
        for (TeachingAttachment attachment : plan.attachments()) {
            Long sectionId = sectionIdByOrder.get(plan.sectionOrderOf().get(attachment.getId()));
            insertVersionAttachment(version, submission, attachment, sectionId, now);
            usedAttachmentIds.add(attachment.getId());
        }

        // 8) 草稿字段与草稿章节在正式提交后清空（内容已进入不可变版本）
        clearDraft(submission, now);

        // 9) 旧评价保留为历史，但不再是「当前评价」
        evaluationMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<TeachingEvaluation>()
                        .eq("submission_id", submission.getId())
                        .set("is_current", 0));
        submission.setStatus(TeachingSubmission.STATUS_SUBMITTED);
        submission.setVersionNo(nextVersion);
        submission.setLate(late ? 1 : 0);
        submission.setUpdateTime(now);
        if (submissionMapper.updateById(submission) != 1) {
            throw new IllegalStateException("更新成果状态失败");
        }

        request.setVersionId(version.getId());
        submitRequestMapper.updateById(request);

        // 10) 事务提交后再回收「本次未使用且无人引用」的临时截图
        scheduleGarbageCollectionAfterCommit(submission, usedAttachmentIds);
        return buildVO(submission);
    }

    /** 幂等命中信号：由 submit 入口捕获，转为返回既有成果。 */
    private static final class DuplicateSubmitSignal extends RuntimeException {
        DuplicateSubmitSignal() {
            super("duplicate submit request");
        }
    }

    private void requireSubmitAllowed(TeachingTask task) {
        if (Integer.valueOf(TeachingTask.STATUS_DRAFT).equals(task.getStatus())) {
            throw BusinessExceptions.notFound("任务不存在");
        }
        if (Integer.valueOf(TeachingTask.STATUS_CLOSED).equals(task.getStatus())) {
            throw BusinessExceptions.conflict("任务已关闭，不能再提交");
        }
        boolean expired = task.getDeadline() != null && LocalDateTime.now().isAfter(task.getDeadline());
        if (expired && !Integer.valueOf(1).equals(task.getAllowLate())) {
            throw BusinessExceptions.conflict("已超过截止时间，且本任务不允许逾期提交");
        }
        // 已通过评价的成果仍可再次提交：重新进入待评价，旧评价作为历史保留
    }

    /** 保存草稿：字段 + 章节 + 章节截图归属；不动任何已冻结版本。 */
    private void saveDraft(TeachingSubmission submission, TeachingSubmissionDTO dto) {
        LocalDateTime now = LocalDateTime.now();
        SysUser student = sysUserMapper.selectById(submission.getStudentId());
        List<SubmissionSectionDTO> sections = normalizeSections(dto.getSections());
        AttachmentPlan plan = loadReusableAttachments(student, submission.getTaskId(), sections);

        // 草稿字段完整持久化，刷新或重新登录后可恢复
        submission.setDraftProjectUrl(
                TeachingTaskServiceImpl.requireHttpUrl(dto.getProjectUrl(), "成果链接", false));
        submission.setDraftContent(optionalText(dto.getContent(), "完成说明", MAX_CONTENT));
        submission.setDraftProcess(optionalText(dto.getProcess(), "问题与解决过程", MAX_CONTENT));
        submission.setDraftUpdateTime(now);
        submission.setUpdateTime(now);

        // 草稿章节软删除（保留行，历史截图的引用记录不丢）
        List<TeachingSubmissionSection> oldDrafts = sectionMapper.selectList(
                new QueryWrapper<TeachingSubmissionSection>()
                        .eq("submission_id", submission.getId())
                        .eq("is_draft", 1)
                        .isNull("deleted_at"));
        for (TeachingSubmissionSection old : oldDrafts) {
            old.setDeletedAt(now);
            old.setUpdateTime(now);
            sectionMapper.updateById(old);
            sectionAttachmentMapper.delete(new QueryWrapper<TeachingSectionAttachment>()
                    .eq("section_id", old.getId()));
        }

        Map<Integer, Long> sectionIdByOrder = new LinkedHashMap<>();
        int order = 1;
        for (SubmissionSectionDTO sectionDto : sections) {
            TeachingSubmissionSection section = new TeachingSubmissionSection();
            section.setSubmissionId(submission.getId());
            section.setVersionId(null);
            section.setTitle(sectionDto.getTitle().trim());
            section.setContent(sectionDto.getContent() == null ? "" : sectionDto.getContent().trim());
            section.setSortOrder(order);
            section.setIsDraft(1);
            section.setCreateTime(now);
            section.setUpdateTime(now);
            if (sectionMapper.insert(section) != 1) {
                throw new IllegalStateException("保存草稿章节失败");
            }
            sectionIdByOrder.put(order, section.getId());
            order++;
        }
        for (TeachingAttachment attachment : plan.attachments()) {
            Long sectionId = sectionIdByOrder.get(plan.sectionOrderOf().get(attachment.getId()));
            if (sectionId == null) {
                continue;
            }
            TeachingSectionAttachment link = new TeachingSectionAttachment();
            link.setSectionId(sectionId);
            link.setAttachmentId(attachment.getId());
            link.setSubmissionId(submission.getId());
            link.setCreateTime(now);
            try {
                sectionAttachmentMapper.insert(link);
            } catch (DuplicateKeyException e) {
                continue; // 同一章节重复引用同一截图：忽略
            }
            attachment.setSectionId(sectionId);
            attachment.setUpdateTime(now);
            attachmentMapper.updateById(attachment);
        }
        if (submissionMapper.updateById(submission) != 1) {
            throw new IllegalStateException("保存草稿失败");
        }
    }

    private void clearDraft(TeachingSubmission submission, LocalDateTime now) {
        List<TeachingSubmissionSection> drafts = sectionMapper.selectList(
                new QueryWrapper<TeachingSubmissionSection>()
                        .eq("submission_id", submission.getId())
                        .eq("is_draft", 1)
                        .isNull("deleted_at"));
        for (TeachingSubmissionSection draft : drafts) {
            draft.setDeletedAt(now);
            draft.setUpdateTime(now);
            sectionMapper.updateById(draft);
            sectionAttachmentMapper.delete(new QueryWrapper<TeachingSectionAttachment>()
                    .eq("section_id", draft.getId()));
        }
        // 注意：MyBatis-Plus 默认 NOT_NULL 更新策略会忽略实体里的 null，
        // 因此这里必须用 UpdateWrapper 显式 set null，否则草稿内容清不掉。
        submissionMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<TeachingSubmission>()
                        .eq("id", submission.getId())
                        .set("draft_project_url", null)
                        .set("draft_content", null)
                        .set("draft_process", null)
                        .set("draft_update_time", null));
        submission.setDraftProjectUrl(null);
        submission.setDraftContent(null);
        submission.setDraftProcess(null);
        submission.setDraftUpdateTime(null);
    }

    private Map<Integer, Long> insertFrozenSections(TeachingSubmission submission,
                                                    TeachingSubmissionVersion version,
                                                    List<SubmissionSectionDTO> sections,
                                                    LocalDateTime now) {
        Map<Integer, Long> sectionIdByOrder = new LinkedHashMap<>();
        int order = 1;
        for (SubmissionSectionDTO dto : sections) {
            TeachingSubmissionSection section = new TeachingSubmissionSection();
            section.setSubmissionId(submission.getId());
            section.setVersionId(version.getId());
            section.setTitle(dto.getTitle().trim());
            section.setContent(dto.getContent() == null ? "" : dto.getContent().trim());
            section.setSortOrder(order);
            section.setIsDraft(0);
            section.setCreateTime(now);
            section.setUpdateTime(now);
            if (sectionMapper.insert(section) != 1) {
                throw new IllegalStateException("保存成果章节失败");
            }
            sectionIdByOrder.put(order, section.getId());
            order++;
        }
        return sectionIdByOrder;
    }

    private void insertVersionAttachment(TeachingSubmissionVersion version, TeachingSubmission submission,
                                         TeachingAttachment attachment, Long sectionId, LocalDateTime now) {
        TeachingVersionAttachment link = new TeachingVersionAttachment();
        link.setVersionId(version.getId());
        link.setAttachmentId(attachment.getId());
        link.setSubmissionId(submission.getId());
        link.setSectionId(sectionId);
        link.setCreateTime(now);
        try {
            versionAttachmentMapper.insert(link);
        } catch (DuplicateKeyException e) {
            return; // 同一版本重复引用同一截图：忽略
        }
        if (attachment.getVersionId() == null) {
            attachment.setVersionId(version.getId());
        }
        attachment.setUpdateTime(now);
        attachmentMapper.updateById(attachment);
    }

    /** 附件归属校验结果：附件列表 + 「附件 ID -> 章节序号（从 1 开始）」。 */
    private record AttachmentPlan(List<TeachingAttachment> attachments,
                                  Map<Long, Integer> sectionOrderOf) {
    }

    /**
     * 校验并加载本次要使用的截图。
     * 允许复用此前上传（含已被旧版本引用）的截图：
     * 复用只新增版本关联，不移动也不删除旧版本的截图，因此「退回后只改说明、保留原截图」可用。
     */
    private AttachmentPlan loadReusableAttachments(SysUser student, Long taskId,
                                                   List<SubmissionSectionDTO> sections) {
        if (student == null) {
            throw BusinessExceptions.forbidden("学生账号不存在");
        }
        Map<Long, Integer> sectionOrderOf = new LinkedHashMap<>();
        List<Long> orderedIds = new ArrayList<>();
        int order = 1;
        for (SubmissionSectionDTO section : sections) {
            if (section.getAttachmentIds() != null) {
                for (Long id : section.getAttachmentIds()) {
                    if (id == null || sectionOrderOf.containsKey(id)) {
                        continue;
                    }
                    sectionOrderOf.put(id, order);
                    orderedIds.add(id);
                }
            }
            order++;
        }
        if (orderedIds.isEmpty()) {
            return new AttachmentPlan(List.of(), Map.of());
        }
        if (orderedIds.size() > MAX_ATTACHMENTS) {
            throw BusinessExceptions.badRequest("单次提交引用的截图不能超过 " + MAX_ATTACHMENTS + " 张");
        }
        List<TeachingAttachment> attachments = attachmentMapper.selectBatchIds(orderedIds);
        Map<Long, TeachingAttachment> byId = new HashMap<>();
        for (TeachingAttachment attachment : attachments) {
            byId.put(attachment.getId(), attachment);
        }
        List<TeachingAttachment> result = new ArrayList<>();
        for (Long id : orderedIds) {
            TeachingAttachment attachment = byId.get(id);
            if (attachment == null
                    || !Objects.equals(attachment.getStudentId(), student.getId())
                    || !Objects.equals(attachment.getTaskId(), taskId)) {
                throw BusinessExceptions.notFound("截图不存在或不属于当前学生：" + id);
            }
            // 软删的截图只有在没有任何版本引用时才不允许再使用
            if (attachment.getDeletedAt() != null
                    && versionAttachmentMapper.countVersionRefs(attachment.getId()) == 0) {
                throw BusinessExceptions.conflict("截图已被移除，请重新上传：" + id);
            }
            result.add(attachment);
        }
        return new AttachmentPlan(result, sectionOrderOf);
    }

    /**
     * 事务提交后回收未使用的临时截图。
     * 放在提交后执行，避免把失败事务的内容当成事实；
     * 真正删除磁盘文件前还要确认没有任何版本引用。
     */
    private void scheduleGarbageCollectionAfterCommit(TeachingSubmission submission,
                                                      Set<Long> usedAttachmentIds) {
        Long submissionId = submission.getId();
        Long taskId = submission.getTaskId();
        String studentId = submission.getStudentId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        // 独立事务：afterCommit 阶段原事务已提交，不能复用它尚未清理的连接
                        collectInNewTransaction(submissionId, taskId, studentId, usedAttachmentIds);
                    } catch (RuntimeException e) {
                        log.warn("回收未使用截图失败：submissionId={}", submissionId, e);
                    }
                }
            });
        } else {
            collectInNewTransaction(submissionId, taskId, studentId, usedAttachmentIds);
        }
    }

    /**
     * 在独立事务中回收，并先取任务行锁：
     * 避免与并发的「提交 / 保存草稿重新绑定同一截图」互相踩踏。
     */
    private void collectInNewTransaction(Long submissionId, Long taskId, String studentId,
                                          Set<Long> usedAttachmentIds) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.execute(status -> {
            TeachingTask locked = taskMapper.selectForUpdate(taskId);
            if (locked == null) {
                return null;
            }
            collectUnusedAttachments(submissionId, taskId, studentId, usedAttachmentIds);
            return null;
        });
    }

    /**
     * 把「既不被草稿章节引用、也不被任何版本引用」的附件排队删除。
     * 版本引用以 teaching_version_attachment 为准，因此历史截图永远不会进入队列。
     */
    private void collectUnusedAttachments(Long submissionId, Long taskId, String studentId,
                                          Set<Long> usedAttachmentIds) {
        List<TeachingAttachment> candidates = attachmentMapper.selectList(
                new QueryWrapper<TeachingAttachment>()
                        .eq("task_id", taskId)
                        .eq("student_id", studentId));
        for (TeachingAttachment attachment : candidates) {
            if (usedAttachmentIds.contains(attachment.getId())) {
                continue;
            }
            if (versionAttachmentMapper.countVersionRefs(attachment.getId()) > 0) {
                continue; // 被历史版本引用：永不删除
            }
            long sectionRefs = sectionAttachmentMapper.selectCount(
                    new QueryWrapper<TeachingSectionAttachment>()
                            .eq("attachment_id", attachment.getId()));
            if (sectionRefs > 0) {
                continue; // 仍被草稿章节引用
            }
            if (deleteTaskMapper.countPendingByUrl(attachment.getImageUrl()) > 0) {
                continue; // 已排队，避免重复
            }
            TeachingFileDeleteTask deleteTask = new TeachingFileDeleteTask();
            deleteTask.setImageUrl(attachment.getImageUrl());
            deleteTask.setAttachmentId(attachment.getId());
            deleteTask.setProcessed(0);
            deleteTask.setReason("未使用的临时截图");
            deleteTask.setCreateTime(LocalDateTime.now());
            deleteTaskMapper.insert(deleteTask);

            attachment.setDeletedAt(LocalDateTime.now());
            attachment.setUpdateTime(LocalDateTime.now());
            attachmentMapper.updateById(attachment);
        }
    }

    // ==================================================================
    // 截图
    // ==================================================================

    @Override
    public TeachingAttachmentVO uploadAttachment(SysUser student, Long taskId, MultipartFile file) {
        TeachingTask task = taskService.requireVisibleToStudent(taskId, student.getClassId());
        if (Integer.valueOf(TeachingTask.STATUS_CLOSED).equals(task.getStatus())) {
            throw BusinessExceptions.conflict("任务已关闭，不能再上传截图");
        }
        TeachingFileUtil.SavedFile saved = fileUtil.save(task.getId(), student.getId(), file);
        String imageUrl = saved.imageUrl();
        try {
            return inTransaction(() -> {
                TeachingAttachment attachment = new TeachingAttachment();
                attachment.setTaskId(task.getId());
                attachment.setStudentId(student.getId());
                attachment.setImageUrl(imageUrl);
                attachment.setOriginalName(saved.originalName());
                attachment.setContentType(saved.contentType());
                attachment.setFileSize(saved.fileSize());
                attachment.setCreateTime(LocalDateTime.now());
                attachment.setUpdateTime(LocalDateTime.now());
                if (attachmentMapper.insert(attachment) != 1) {
                    throw new IllegalStateException("保存截图记录失败");
                }
                return toAttachmentVO(attachment);
            });
        } catch (RuntimeException e) {
            // 事务已回滚（含提交阶段失败）：磁盘文件必须同步删除，避免孤儿文件
            fileUtil.deleteQuietly(imageUrl);
            throw e;
        }
    }

    @Override
    public TeachingAttachmentVO attachmentMeta(String actorId, String actorRole, Long attachmentId) {
        return toAttachmentVO(requireReadableAttachment(actorId, actorRole, attachmentId));
    }

    @Override
    public byte[] readAttachment(String actorId, String actorRole, Long attachmentId) {
        TeachingAttachment attachment = requireReadableAttachment(actorId, actorRole, attachmentId);
        return fileUtil.read(attachment.getImageUrl());
    }

    /**
     * 附件读取权限：学生只能读自己的；教师可读（沿用当前教师规则）。
     * 已软删但被历史版本引用的附件仍可读，保证历史版本完整。
     */
    private TeachingAttachment requireReadableAttachment(String actorId, String actorRole, Long attachmentId) {
        TeachingAttachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null) {
            throw BusinessExceptions.notFound("截图不存在");
        }
        if ("STUDENT".equals(actorRole)) {
            if (!Objects.equals(attachment.getStudentId(), actorId)) {
                throw BusinessExceptions.notFound("截图不存在");
            }
        } else if (!"TEACHER".equals(actorRole)) {
            throw BusinessExceptions.forbidden("无权读取该截图");
        }
        return attachment;
    }

    @Override
    public void deleteTempAttachment(SysUser student, Long attachmentId) {
        inTransactionVoid(() -> {
            // 与提交共用任务行锁：避免「读到临时状态 -> 并发提交冻结 -> 仍然排队删除磁盘文件」
            TeachingAttachment snapshot = attachmentMapper.selectById(attachmentId);
            if (snapshot == null || !Objects.equals(snapshot.getStudentId(), student.getId())) {
                throw BusinessExceptions.notFound("截图不存在");
            }
            TeachingTask locked = taskMapper.selectForUpdate(snapshot.getTaskId());
            if (locked == null) {
                throw BusinessExceptions.notFound("任务不存在");
            }
            submissionMapper.selectForUpdate(locked.getId(), student.getId());

            // 锁内当前读：此刻的状态才可信
            TeachingAttachment current = attachmentMapper.selectById(attachmentId);
            if (current == null || !Objects.equals(current.getStudentId(), student.getId())) {
                throw BusinessExceptions.notFound("截图不存在");
            }
            // 只要被任何版本引用，就不允许删除磁盘文件
            if (versionAttachmentMapper.countVersionRefs(attachmentId) > 0
                    || current.getVersionId() != null) {
                throw BusinessExceptions.conflict("该截图已随正式版本提交，不能删除");
            }
            sectionAttachmentMapper.delete(new QueryWrapper<TeachingSectionAttachment>()
                    .eq("attachment_id", attachmentId));
            if (deleteTaskMapper.countPendingByUrl(current.getImageUrl()) == 0) {
                TeachingFileDeleteTask task = new TeachingFileDeleteTask();
                task.setImageUrl(current.getImageUrl());
                task.setAttachmentId(attachmentId);
                task.setProcessed(0);
                task.setReason("学生删除临时截图");
                task.setCreateTime(LocalDateTime.now());
                deleteTaskMapper.insert(task);
            }
            current.setDeletedAt(LocalDateTime.now());
            current.setUpdateTime(LocalDateTime.now());
            attachmentMapper.updateById(current);
        });
    }

    @Override
    public List<TeachingAttachmentVO> listMyTempAttachments(SysUser student, Long taskId) {
        List<TeachingAttachment> attachments = attachmentMapper.selectList(
                new QueryWrapper<TeachingAttachment>()
                        .eq("student_id", student.getId())
                        .eq("task_id", taskId)
                        .isNull("deleted_at")
                        .orderByDesc("id"));
        return attachments.stream().map(this::toAttachmentVO).toList();
    }

    // ==================================================================
    // 教师评价
    // ==================================================================

    @Override
    public EvaluationVO evaluate(String teacherId, TeachingEvaluationDTO dto) {
        if (dto.getSubmissionId() == null) {
            throw BusinessExceptions.badRequest("缺少成果编号");
        }
        return inTransaction(() -> {
            TeachingSubmission snapshot = submissionMapper.selectById(dto.getSubmissionId());
            if (snapshot == null) {
                throw BusinessExceptions.notFound("成果提交不存在");
            }
            // 锁顺序与提交一致：先任务、后成果
            TeachingTask task = taskMapper.selectForUpdate(snapshot.getTaskId());
            if (task == null) {
                throw BusinessExceptions.notFound("任务不存在");
            }
            TeachingSubmission submission = submissionMapper.selectForUpdate(
                    snapshot.getTaskId(), snapshot.getStudentId());
            if (submission == null) {
                throw BusinessExceptions.notFound("成果提交不存在");
            }
            if (submission.getVersionNo() == null || submission.getVersionNo() == 0) {
                throw BusinessExceptions.conflict("该学生尚未正式提交，无法评价");
            }

            Long versionId = dto.getVersionId() != null ? dto.getVersionId()
                    : latestVersionId(submission.getId());
            TeachingSubmissionVersion version = versionMapper.selectById(versionId);
            if (version == null || !submission.getId().equals(version.getSubmissionId())) {
                throw BusinessExceptions.notFound("提交版本不存在");
            }

            String decision = dto.getDecision() == null ? ""
                    : dto.getDecision().trim().toUpperCase(java.util.Locale.ROOT);
            if (!TeachingEvaluation.DECISION_PASS.equals(decision)
                    && !TeachingEvaluation.DECISION_REVISE.equals(decision)) {
                throw BusinessExceptions.badRequest("评价结果只能是 PASS（通过）或 REVISE（退回修改）");
            }
            Integer score = dto.getScore();
            if (TeachingEvaluation.DECISION_PASS.equals(decision)) {
                if (score == null) {
                    throw BusinessExceptions.badRequest("通过时必须给出分数");
                }
                if (score < 0 || score > task.getFullScore()) {
                    throw BusinessExceptions.badRequest("分数必须在 0 到 " + task.getFullScore() + " 之间");
                }
            } else if (score != null) {
                throw BusinessExceptions.badRequest("退回修改时不应给出分数");
            }
            String comment = optionalText(dto.getComment(), "评语", MAX_COMMENT);

            boolean isLatest = Objects.equals(version.getVersionNo(), submission.getVersionNo());
            if (isLatest) {
                evaluationMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<TeachingEvaluation>()
                                .eq("submission_id", submission.getId())
                                .set("is_current", 0));
            }

            TeachingEvaluation evaluation = new TeachingEvaluation();
            evaluation.setSubmissionId(submission.getId());
            evaluation.setVersionId(version.getId());
            evaluation.setVersionNo(version.getVersionNo());
            evaluation.setTaskId(task.getId());
            evaluation.setStudentId(submission.getStudentId());
            evaluation.setTeacherId(teacherId);
            evaluation.setDecision(decision);
            evaluation.setScore(score);
            evaluation.setComment(comment);
            evaluation.setIsCurrent(isLatest ? 1 : 0);
            evaluation.setCreateTime(LocalDateTime.now());
            if (evaluationMapper.insert(evaluation) != 1) {
                throw new IllegalStateException("保存评价失败");
            }

            // 评价非最新版本时不改变最新版本状态，避免历史评价影响当前状态
            if (isLatest) {
                submission.setStatus(TeachingEvaluation.DECISION_PASS.equals(decision)
                        ? TeachingSubmission.STATUS_EVALUATED
                        : TeachingSubmission.STATUS_REVISING);
                submission.setUpdateTime(LocalDateTime.now());
                if (submissionMapper.updateById(submission) != 1) {
                    throw new IllegalStateException("更新成果状态失败");
                }
            }

            EvaluationVO vo = toEvaluationVO(evaluation);
            SysUser teacher = sysUserMapper.selectById(teacherId);
            if (teacher != null) {
                vo.setTeacherName(teacher.getRealName());
            }
            return vo;
        });
    }

    private Long latestVersionId(Long submissionId) {
        TeachingSubmissionVersion version = versionMapper.selectOne(
                new QueryWrapper<TeachingSubmissionVersion>()
                        .eq("submission_id", submissionId)
                        .orderByDesc("version_no").last("LIMIT 1"));
        if (version == null) {
            throw BusinessExceptions.conflict("该学生尚未正式提交，无法评价");
        }
        return version.getId();
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private TeachingSubmission findSubmission(Long taskId, String studentId) {
        return submissionMapper.selectOne(new QueryWrapper<TeachingSubmission>()
                .eq("task_id", taskId).eq("student_id", studentId));
    }

    private void insertIgnoreSubmission(Long taskId, SysUser student) {
        submissionMapper.insertIgnore(taskId, student.getId(), student.getClassId(), LocalDateTime.now());
    }

    private List<SubmissionSectionDTO> normalizeSections(List<SubmissionSectionDTO> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        if (sections.size() > MAX_SECTIONS) {
            throw BusinessExceptions.badRequest("成果章节不能超过 " + MAX_SECTIONS + " 个");
        }
        List<SubmissionSectionDTO> result = new ArrayList<>();
        for (SubmissionSectionDTO dto : sections) {
            if (dto.getTitle() == null || dto.getTitle().isBlank()) {
                throw BusinessExceptions.badRequest("章节标题不能为空");
            }
            String title = dto.getTitle().trim();
            if (title.length() > MAX_SECTION_TITLE) {
                throw BusinessExceptions.badRequest("章节标题长度不能超过 " + MAX_SECTION_TITLE);
            }
            if (dto.getContent() != null && dto.getContent().length() > MAX_CONTENT) {
                throw BusinessExceptions.badRequest("章节内容长度不能超过 " + MAX_CONTENT);
            }
            SubmissionSectionDTO copy = new SubmissionSectionDTO();
            copy.setTitle(title);
            copy.setContent(dto.getContent());
            copy.setSortOrder(dto.getSortOrder());
            copy.setAttachmentIds(dto.getAttachmentIds());
            result.add(copy);
        }
        return result;
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw BusinessExceptions.badRequest("完成说明不能为空");
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_CONTENT) {
            throw BusinessExceptions.badRequest("完成说明长度不能超过 " + MAX_CONTENT + " 个字符");
        }
        return trimmed;
    }

    private String optionalText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw BusinessExceptions.badRequest(field + "长度不能超过 " + max + " 个字符");
        }
        return trimmed;
    }

    private String requireRequestKey(String key) {
        if (key == null || key.isBlank()) {
            throw BusinessExceptions.badRequest("缺少提交请求标识（requestKey），请刷新页面后重试");
        }
        String trimmed = key.trim();
        if (trimmed.length() < 8 || trimmed.length() > 64) {
            throw BusinessExceptions.badRequest("提交请求标识长度必须在 8 到 64 之间");
        }
        if (!trimmed.matches("[A-Za-z0-9_-]+")) {
            throw BusinessExceptions.badRequest("提交请求标识只能包含字母、数字、下划线和连字符");
        }
        return trimmed;
    }
}
