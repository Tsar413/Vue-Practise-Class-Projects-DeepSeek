package com.study.vuePractiseBackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.study.vuePractiseBackend.entity.SysLoginToken;
import com.study.vuePractiseBackend.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 登录凭证访问。
 *
 * 并发策略：登录时先在事务内对 sys_user 中该账号的行加排他锁
 * （{@link #selectUserForUpdate}），使同一账号的登录请求串行执行。
 * 「读现有记录 → 判断是否需要生成访问码 → 写入」因此在同一临界区内完成，
 * 不会再出现「先查后插撞唯一键」与「并发各生成一个访问码互相覆盖」。
 *
 * 注意点（实测得出的必要条件，不要删改）：
 *   1. 读登录记录必须用加锁读 {@link #selectByUserIdForUpdate}。
 *      MySQL 默认 REPEATABLE READ 下普通 SELECT 是快照读，即使已持有 sys_user 行锁，
 *      也读不到本事务开始之后才提交的 api_access_code，判断会失效。
 *   2. 加锁顺序统一为 sys_user → sys_login_token，与删除账号路径一致；
 *      顺序不一致会带来交叉等待的风险。
 *
 * 凭证本身用一条 INSERT ... ON DUPLICATE KEY UPDATE 写入：
 *   - 记录不存在时插入；
 *   - 已存在时轮换 token_hash / create_time / expire_time，并清空 revoke_time；
 *   - api_access_code 为 null 时不修改该列（教师，或学生已有访问码）。
 */
@Mapper
public interface SysLoginMapper extends BaseMapper<SysLoginToken> {

    /** 对账号行加排他锁；必须在事务中调用，用于串行化同一账号的登录。 */
    @Select("SELECT * FROM sys_user WHERE id = #{userId} FOR UPDATE")
    SysUser selectUserForUpdate(@Param("userId") String userId);

    /** 读取账号当前的登录记录（不加锁），用于回读最终生效值。 */
    @Select("SELECT * FROM sys_login_token WHERE user_id = #{userId}")
    SysLoginToken selectByUserId(@Param("userId") String userId);

    /**
     * 加锁读取账号当前的登录记录。
     *
     * 必须用加锁读而不是普通 SELECT：MySQL 默认 REPEATABLE READ 下普通 SELECT 是快照读，
     * 即使已经持有 sys_user 行的排他锁，也读不到「本事务开始之后才提交」的 api_access_code，
     * 于是每个并发请求都会认为「还没有访问码」而各自生成一个，最后互相覆盖。
     * 加锁读始终读取最新已提交数据，因此判断结果确定。
     */
    @Select("SELECT * FROM sys_login_token WHERE user_id = #{userId} FOR UPDATE")
    SysLoginToken selectByUserIdForUpdate(@Param("userId") String userId);

    /**
     * 写入或轮换登录凭证。
     * apiAccessCode 为 null 时该列保持数据库原值不变。
     */
    @Update("""
            INSERT INTO sys_login_token
                (user_id, token_hash, api_access_code, create_time, expire_time, revoke_time)
            VALUES
                (#{userId}, #{tokenHash}, #{apiAccessCode}, #{createTime}, #{expireTime}, NULL)
            ON DUPLICATE KEY UPDATE
                token_hash  = VALUES(token_hash),
                create_time = VALUES(create_time),
                expire_time = VALUES(expire_time),
                revoke_time = NULL,
                api_access_code = COALESCE(#{apiAccessCode}, api_access_code)
            """)
    int upsertToken(@Param("userId") String userId,
                    @Param("tokenHash") String tokenHash,
                    @Param("apiAccessCode") String apiAccessCode,
                    @Param("createTime") java.time.LocalDateTime createTime,
                    @Param("expireTime") java.time.LocalDateTime expireTime);
}
