package com.study.vuePractiseBackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.study.vuePractiseBackend.entity.AiTutorConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiTutorConversationMapper extends BaseMapper<AiTutorConversation> {

    /**
     * 对会话行加排他锁（当前读）。
     *
     * 用途：把「确认会话仍属于本人且未删除」与「写入消息 / 更新计数」放进同一临界区，
     * 并与删除会话在同一行锁上互斥。仅用于短事务，外部模型 HTTP 调用期间不得持锁。
     *
     * 必须在事务中调用（普通 SELECT ... FOR UPDATE 在自动提交下会立刻释放锁）。
     */
    @Select("SELECT * FROM ai_tutor_conversation WHERE id = #{id} FOR UPDATE")
    AiTutorConversation selectByIdForUpdate(@Param("id") Long id);
}
