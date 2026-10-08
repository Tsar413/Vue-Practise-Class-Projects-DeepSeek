package com.study.vuePractiseBackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import lombok.Data;

/**
 * 抢票项目内的模拟业务用户，与系统学生 / 教师账号无关。
 * userNo 是项目内编号，id 是数据库主键，两者用途不同。
 */
@Entity
@Table(name = "ticket_user")
@TableName("ticket_user")
@Data
public class TicketUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "user_no", length = 50, nullable = false)
    @TableField("user_no")
    private String userNo;

    @Column(name = "real_name", length = 50, nullable = false)
    @TableField("real_name")
    private String realName;

    @Column(name = "workspace_id", nullable = false)
    @TableField("workspace_id")
    private Long workspaceId;

    @Column(length = 20)
    private String phone;

    @Column(length = 100)
    private String department;

    @Column(length = 50)
    private String campus;

    /** USER 普通用户，ADMIN 抢票管理员。 */
    @Column(length = 20)
    private String role;

    /** 1 启用，0 停用。 */
    @Column(nullable = false)
    private Integer status;
}
