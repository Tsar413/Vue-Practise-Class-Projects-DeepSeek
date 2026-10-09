package com.study.vuePractiseBackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.study.vuePractiseBackend.entity.TeachingAttachment;
import com.study.vuePractiseBackend.entity.TeachingFileDeleteTask;
import com.study.vuePractiseBackend.entity.TeachingSectionAttachment;
import com.study.vuePractiseBackend.entity.TeachingTask;
import com.study.vuePractiseBackend.mapper.TeachingAttachmentMapper;
import com.study.vuePractiseBackend.mapper.TeachingFileDeleteTaskMapper;
import com.study.vuePractiseBackend.mapper.TeachingSectionAttachmentMapper;
import com.study.vuePractiseBackend.mapper.TeachingTaskMapper;
import com.study.vuePractiseBackend.mapper.TeachingVersionAttachmentMapper;
import com.study.vuePractiseBackend.util.TeachingFileUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 教学截图待删除文件的消费者。
 *
 * 只有入队而没有消费者的话，临时图片永远不会从磁盘消失，因此这里必须真正执行删除。
 *
 * 事务说明（重要）：
 *   {@code @Scheduled} 方法直接调用本类的 {@code @Transactional} 方法不经过 Spring 代理，
 *   注解不会生效。因此这里统一使用 {@link TransactionTemplate}
 *   （PROPAGATION_REQUIRES_NEW + ISOLATION_READ_COMMITTED），每条队列项一个独立事务。
 *
 * 每条队列项的处理顺序：
 *   1. 取 teaching_task 行锁（与提交/保存草稿/删除临时截图串行，避免「刚判定可删就被重新绑定」）；
 *   2. 在该锁内重新确认：未被任何正式版本引用、未被任何草稿章节引用、附件已软删除；
 *   3. 删除磁盘文件；只有磁盘确实删除成功才标记 processed=1；
 *   4. 删除失败或又被引用时保留队列项，下一轮重试（重新被引用的按「取消删除」结算）。
 */
@Slf4j
@Component
public class TeachingFileCleanupWorker {

    private static final int BATCH_SIZE = 50;

    @Resource
    private TeachingFileDeleteTaskMapper deleteTaskMapper;

    @Resource
    private TeachingAttachmentMapper attachmentMapper;

    @Resource
    private TeachingVersionAttachmentMapper versionAttachmentMapper;

    @Resource
    private TeachingSectionAttachmentMapper sectionAttachmentMapper;

    @Resource
    private TeachingTaskMapper taskMapper;

    @Resource
    private TeachingFileUtil fileUtil;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Scheduled(initialDelay = 20_000, fixedDelay = 30_000)
    public void run() {
        try {
            int cleaned = cleanupOnce();
            if (cleaned > 0) {
                log.info("教学截图清理完成 {} 项", cleaned);
            }
        } catch (RuntimeException e) {
            // 定时任务不允许因单轮异常而中断
            log.warn("教学截图清理任务本轮失败，将在下一轮重试", e);
        }
    }

    /** 执行一轮清理；返回本轮真正删除成功的数量（便于测试与手工触发）。 */
    public int cleanupOnce() {
        List<TeachingFileDeleteTask> pending = fetchPending();
        int cleaned = 0;
        for (TeachingFileDeleteTask task : pending) {
            try {
                if (processOne(task.getId())) {
                    cleaned++;
                }
            } catch (RuntimeException e) {
                // 单条失败不影响其它队列项，也不标记 processed，留待重试
                log.warn("处理截图删除任务失败：taskId={}", task.getId(), e);
            }
        }
        return cleaned;
    }

    private List<TeachingFileDeleteTask> fetchPending() {
        TransactionTemplate template = newTransaction();
        return template.execute(status -> deleteTaskMapper.selectList(
                new QueryWrapper<TeachingFileDeleteTask>()
                        .eq("processed", 0)
                        .orderByAsc("id")
                        .last("LIMIT " + BATCH_SIZE)));
    }

    /**
     * 处理一条队列项；返回 true 表示磁盘文件已确认删除。
     * 整个判断与删除在同一事务内完成，并先取任务行锁。
     */
    private boolean processOne(Long queueId) {
        TransactionTemplate template = newTransaction();
        Boolean deleted = template.execute(status -> {
            TeachingFileDeleteTask task = deleteTaskMapper.selectById(queueId);
            if (task == null || Integer.valueOf(1).equals(task.getProcessed())) {
                return Boolean.FALSE;
            }

            // 解析来源附件，确定需要锁住哪个任务
            Long taskId = resolveTaskId(task);
            if (taskId != null) {
                TeachingTask locked = taskMapper.selectForUpdate(taskId);
                if (locked == null) {
                    // 任务不存在（理论上不会发生，外键约束保证）：直接结算，避免死循环
                    markProcessed(task, "任务不存在，直接结算", 1);
                    return Boolean.FALSE;
                }
            }

            // 锁内重新确认：仍被引用就撤回删除意图
            if (isStillReferenced(task)) {
                markProcessed(task, "重新被引用，取消删除", 1);
                return Boolean.FALSE;
            }

            // 删除前不再读取文件内容，直接按路径删除
            if (!deleteFile(task.getImageUrl())) {
                // 删除失败：保留 processed=0，下一轮重试
                return Boolean.FALSE;
            }
            markProcessed(task, null, 1);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(deleted);
    }

    private Long resolveTaskId(TeachingFileDeleteTask task) {
        if (task.getAttachmentId() != null) {
            TeachingAttachment attachment = attachmentMapper.selectById(task.getAttachmentId());
            if (attachment != null && attachment.getTaskId() != null) {
                return attachment.getTaskId();
            }
        }
        // 兜底：按磁盘路径反查附件
        List<TeachingAttachment> byUrl = attachmentMapper.selectList(
                new QueryWrapper<TeachingAttachment>()
                        .eq("image_url", task.getImageUrl())
                        .last("LIMIT 1"));
        return byUrl.isEmpty() ? null : byUrl.get(0).getTaskId();
    }

    /** 是否仍被任何正式版本或草稿章节引用，或附件仍在正常使用。 */
    private boolean isStillReferenced(TeachingFileDeleteTask task) {
        if (versionAttachmentMapper.countVersionRefsByUrl(task.getImageUrl()) > 0) {
            return true;
        }
        List<TeachingAttachment> attachments = attachmentMapper.selectList(
                new QueryWrapper<TeachingAttachment>().eq("image_url", task.getImageUrl()));
        for (TeachingAttachment attachment : attachments) {
            long sectionRefs = sectionAttachmentMapper.selectCount(
                    new QueryWrapper<TeachingSectionAttachment>()
                            .eq("attachment_id", attachment.getId()));
            if (sectionRefs > 0) {
                return true;
            }
            if (attachment.getDeletedAt() == null) {
                // 附件未被软删除：说明仍在正常使用，不能删文件
                return true;
            }
        }
        return false;
    }

    private void markProcessed(TeachingFileDeleteTask task, String reason, int processed) {
        task.setProcessed(processed);
        if (reason != null) {
            task.setReason(reason);
        }
        deleteTaskMapper.updateById(task);
    }

    /**
     * 真正删除磁盘文件；返回 true 表示确认文件已不存在。
     * 失败返回 false，调用方保留 processed=0 等待下一轮重试。
     */
    private boolean deleteFile(String imageUrl) {
        try {
            fileUtil.delete(imageUrl);
            return true;
        } catch (RuntimeException e) {
            log.warn("删除教学截图失败，将在下一轮重试：{}", imageUrl, e);
            return false;
        }
    }

    private TransactionTemplate newTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
}
