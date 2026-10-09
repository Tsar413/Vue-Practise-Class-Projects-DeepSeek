package com.study.vuePractiseBackend.service;

import com.study.vuePractiseBackend.dto.AiTutorAskDTO;
import com.study.vuePractiseBackend.dto.AiTutorConversationDTO;
import com.study.vuePractiseBackend.vo.AiTutorAnswerVO;
import com.study.vuePractiseBackend.vo.AiTutorConversationVO;
import com.study.vuePractiseBackend.vo.AiTutorMessageVO;

import java.util.List;

/**
 * AI 辅导：会话管理、消息读取与提问。
 * 所有方法的 studentId 都来自登录身份，会话归属每次校验，客户端不能替换。
 */
public interface AiTutorService {

    List<AiTutorConversationVO> listConversations(String studentId);

    AiTutorConversationVO createConversation(String studentId, AiTutorConversationDTO dto);

    /** 软删（status=0）；重复删除按成功处理。 */
    void deleteConversation(String studentId, Long conversationId);

    List<AiTutorMessageVO> listMessages(String studentId, Long conversationId);

    AiTutorAnswerVO ask(String studentId, AiTutorAskDTO dto);
}
