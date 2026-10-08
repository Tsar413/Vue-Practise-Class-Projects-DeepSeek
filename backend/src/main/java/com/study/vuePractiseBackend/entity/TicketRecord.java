package com.study.vuePractiseBackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 抢票记录（票券）。 */
@Entity
@Table(name = "ticket_record")
@TableName("ticket_record")
@Data
public class TicketRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    @TableField("workspace_id")
    private Long workspaceId;

    @Column(name = "activity_id", nullable = false)
    @TableField("activity_id")
    private Long activityId;

    /** 抢票的模拟业务用户，对应 ticket_user.id，不是学生学号。 */
    @Column(name = "user_id", nullable = false)
    @TableField("user_id")
    private Long userId;

    @Column(name = "ticket_no", nullable = false, length = 64)
    @TableField("ticket_no")
    private String ticketNo;

    /** 0 已取消，1 有效，2 已核销。 */
    @Column(nullable = false)
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "booking_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("booking_time")
    private LocalDateTime bookingTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "cancel_time", columnDefinition = "DATETIME")
    @TableField("cancel_time")
    private LocalDateTime cancelTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "verify_time", columnDefinition = "DATETIME")
    @TableField("verify_time")
    private LocalDateTime verifyTime;

    @Column(name = "verify_user_id")
    @TableField("verify_user_id")
    private Long verifyUserId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "update_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("update_time")
    private LocalDateTime updateTime;
}
