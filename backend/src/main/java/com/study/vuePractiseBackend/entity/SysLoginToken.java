package com.study.vuePractiseBackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 登录凭证。
 * token_hash 保存网页登录 Token 的 SHA-256；
 * api_access_code 是学生长期 API 访问码，原值保存，不随网页退出失效。
 */
@Entity
@Table(name = "sys_login_token")
@TableName("sys_login_token")
@Data
public class SysLoginToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "user_id", unique = true, nullable = false, length = 50)
    @TableField("user_id")
    private String userId;

    @JsonIgnore
    @ToString.Exclude
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    @TableField("token_hash")
    private String tokenHash;

    @JsonIgnore
    @ToString.Exclude
    @Column(name = "api_access_code", unique = true, length = 64)
    @TableField("api_access_code")
    private String apiAccessCode;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(columnDefinition = "DATETIME", name = "create_time")
    @TableField(value = "create_time")
    private LocalDateTime createTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(columnDefinition = "DATETIME", name = "expire_time")
    @TableField(value = "expire_time")
    private LocalDateTime expireTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(columnDefinition = "DATETIME", name = "revoke_time")
    @TableField(value = "revoke_time")
    private LocalDateTime revokeTime;
}
