package com.study.vuePractiseBackend;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.study.vuePractiseBackend.config.AiTutorProperties;
import com.study.vuePractiseBackend.controller.AiTutorController;
import com.study.vuePractiseBackend.dto.AiTutorAskDTO;
import com.study.vuePractiseBackend.entity.AiTutorConversation;
import com.study.vuePractiseBackend.entity.AiTutorMessage;
import com.study.vuePractiseBackend.mapper.AiTutorConversationMapper;
import com.study.vuePractiseBackend.mapper.AiTutorMessageMapper;
import com.study.vuePractiseBackend.mapper.SysUserMapper;
import com.study.vuePractiseBackend.service.AiTutorService;
import com.study.vuePractiseBackend.service.TeachingTaskService;
import com.study.vuePractiseBackend.service.impl.AiTutorServiceImpl;
import com.study.vuePractiseBackend.util.AiTutorContextBuilder;
import com.study.vuePractiseBackend.vo.AiTutorAnswerVO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 辅导提问链路测试：数据库用 Mockito 模拟，上游模型用本机 HttpServer 模拟。
 *
 * 覆盖未配置、401、429、超时、正常回答、重复点击幂等、最小间隔限流与会话删除后的缓存校验。
 * 密钥一律使用虚构哨兵（fixture-api-key），不读取环境变量、不访问任何真实模型端点。
 */
class AiTutorAskFlowTests {

    private static final String STUDENT_ID = "2026001";
    private static final String FIXTURE_KEY = "fixture-api-key";
    private static final long CONVERSATION_ID = 77L;

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>("");

    private volatile int responseStatus = 200;
    private volatile String responseBody =
            "{\"choices\":[{\"message\":{\"content\":\"先定位：检查活动列表接口的必填参数。\"}}]}";
    private volatile long responseDelayMillis;

    private AiTutorProperties properties;
    private AiTutorConversationMapper conversationMapper;
    private AiTutorMessageMapper messageMapper;
    private AiTutorServiceImpl service;
    private AiTutorConversation conversation;

    @BeforeEach
    void setUp() throws IOException {
        // 没有 Spring 容器时，MyBatis-Plus 的 LambdaQueryWrapper 需要手动初始化实体的 TableInfo
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AiTutorConversation.class);
        TableInfoHelper.initTableInfo(assistant, AiTutorMessage.class);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (responseDelayMillis > 0) {
                try {
                    Thread.sleep(responseDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // 客户端超时后会主动断开，这里不需要处理
            } finally {
                exchange.close();
            }
        });
        server.start();

        properties = new AiTutorProperties();
        // 只指向本机测试服务器；生产配置才需要 /v1 这类版本前缀
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("fixture-model");
        properties.setApiKey(FIXTURE_KEY);
        properties.setConnectTimeoutSeconds(2);
        properties.setTimeoutSeconds(3);
        properties.setMaxTokens(128);
        properties.setMinIntervalSeconds(0);
        properties.setDailyLimitPerStudent(0);
        properties.setMaxConcurrentPerStudent(1);
        properties.setMaxConcurrentGlobal(2);
        properties.setAcquireTimeoutMillis(200);

        AiTutorContextBuilder contextBuilder = new AiTutorContextBuilder();
        ReflectionTestUtils.setField(contextBuilder, "properties", properties);
        ReflectionTestUtils.setField(contextBuilder, "objectMapper", new JsonMapper());

        conversationMapper = mock(AiTutorConversationMapper.class);
        messageMapper = mock(AiTutorMessageMapper.class);
        conversation = conversation();

        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        when(conversationMapper.selectCount(any())).thenReturn(0L);
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.insert(any(AiTutorConversation.class))).thenAnswer(invocation -> {
            AiTutorConversation created = invocation.getArgument(0);
            created.setId(CONVERSATION_ID);
            return 1;
        });
        when(messageMapper.insert(any(AiTutorMessage.class))).thenReturn(1);
        when(messageMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(messageMapper.selectCount(any())).thenReturn(0L);

        service = new AiTutorServiceImpl();
        ReflectionTestUtils.setField(service, "conversationMapper", conversationMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "sysUserMapper", mock(SysUserMapper.class));
        ReflectionTestUtils.setField(service, "teachingTaskService", mock(TeachingTaskService.class));
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "contextBuilder", contextBuilder);
        ReflectionTestUtils.setField(service, "objectMapper", new JsonMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /* ------------------------- 未配置 ------------------------- */

    @Test
    void 未配置模型时不调用模型且只保存脱敏后的提问() {
        properties.setApiKey("");

        AiTutorAnswerVO answer = service.ask(STUDENT_ID, askDTO("fixture-req-unconfigured"));

        assertFalse(answer.isConfigured());
        assertEquals("AI辅导暂未配置", answer.getNotice());
        assertNull(answer.getMessage());
        assertEquals(0, requestCount.get(), "未配置时不允许调用模型");

        List<AiTutorMessage> saved = savedMessages();
        assertEquals(1, saved.size());
        assertEquals(AiTutorMessage.ROLE_USER, saved.get(0).getRole());
        // 提问里粘贴的凭证形态在落库前就已经脱敏
        assertFalse(saved.get(0).getContent().contains("fixture-token-value"));
        assertTrue(saved.get(0).getContent().contains("[已脱敏]"));
    }

    /* ------------------------- 上游异常 ------------------------- */

    @Test
    void 上游401翻译为认证失败提示且不回传原文() {
        responseStatus = 401;
        responseBody = "{\"error\":\"invalid key fixture-detail\"}";

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.ask(STUDENT_ID, askDTO("fixture-req-401")));

        assertEquals(503, error.getStatusCode().value());
        assertEquals("AI 辅导服务认证失败，请联系教师检查配置", error.getReason());
        assertFalse(String.valueOf(error.getReason()).contains("fixture-detail"));
        // 提问照常保存，但不写任何回答
        assertEquals(1, savedMessages().size());
    }

    @Test
    void 上游429翻译为频率提示() {
        responseStatus = 429;
        responseBody = "{\"error\":\"rate limited\"}";

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.ask(STUDENT_ID, askDTO("fixture-req-429")));

        assertEquals(503, error.getStatusCode().value());
        assertEquals("AI 辅导请求过于频繁，请稍后再试", error.getReason());
        assertEquals(1, savedMessages().size());
    }

    @Test
    void 上游超时翻译为超时提示() {
        properties.setTimeoutSeconds(1);
        responseDelayMillis = 2000;

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.ask(STUDENT_ID, askDTO("fixture-req-timeout")));

        assertEquals(503, error.getStatusCode().value());
        assertEquals("AI 辅导响应超时，请稍后重试", error.getReason());
    }

    @Test
    void 上游响应超过大小上限时中断读取() {
        properties.setMaxResponseBytes(64);
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"" + "长".repeat(200) + "\"}}]}";

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.ask(STUDENT_ID, askDTO("fixture-req-too-large")));

        assertEquals(503, error.getStatusCode().value());
        assertEquals("AI 辅导返回内容过大，请稍后重试", error.getReason());
        assertEquals(1, savedMessages().size(), "只应保存学生提问");
    }

    /* ------------------------- 正常回答 ------------------------- */

    @Test
    void 正常回答落库并下发thinking与max_tokens() {
        AiTutorAnswerVO answer = service.ask(STUDENT_ID, askDTO("fixture-req-happy"));

        assertTrue(answer.isConfigured());
        assertNull(answer.getNotice());
        assertNotNull(answer.getMessage());
        assertEquals(AiTutorMessage.ROLE_ASSISTANT, answer.getMessage().getRole());
        assertTrue(answer.getMessage().getContent().contains("先定位"));
        // 来源来自真实接口文档，不是编造的
        assertTrue(answer.getMessage().getContextRefs().stream()
                .anyMatch(ref -> "TicketController_getAllActivities".equals(ref.getId())));

        String payload = lastBody.get();
        assertTrue(payload.contains("\"model\":\"fixture-model\""));
        assertTrue(payload.contains("\"max_tokens\":128"));
        assertTrue(payload.contains("\"thinking\":{\"type\":\"disabled\"}"));
        assertTrue(payload.contains("你是高职院校实训课的 AI 编程辅导助手"));
        assertTrue(payload.contains("【学生提问】"));
        assertEquals("Bearer " + FIXTURE_KEY, lastAuthorization.get());

        List<AiTutorMessage> saved = savedMessages();
        assertEquals(2, saved.size());
        assertEquals(AiTutorMessage.ROLE_ASSISTANT, saved.get(1).getRole());
        assertNotNull(saved.get(1).getContextRefs(), "回答必须带上真实来源 JSON");
    }

    /* ------------------------- 幂等与限流 ------------------------- */

    @Test
    void 同一requestKey重复点击只调用一次模型且不重复建会话() {
        // 最小间隔设为 5 秒：幂等必须优先于限流，否则重复点击会被 409 拒绝
        properties.setMinIntervalSeconds(5);
        AiTutorAskDTO dto = askDTO("fixture-req-idem");
        dto.setConversationId(null);

        AiTutorAnswerVO first = service.ask(STUDENT_ID, dto);
        AiTutorAnswerVO second = service.ask(STUDENT_ID, dto);

        assertEquals(1, requestCount.get(), "重复点击不应重复调用模型");
        assertEquals(first.getMessage().getContent(), second.getMessage().getContent());
        verify(conversationMapper, times(1)).insert(any(AiTutorConversation.class));
    }

    @Test
    void 会话删除后命中幂等缓存返回404而不是旧回答() {
        AiTutorAskDTO dto = askDTO("fixture-req-deleted");
        service.ask(STUDENT_ID, dto);

        when(conversationMapper.selectOne(any())).thenReturn(deletedConversation());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.ask(STUDENT_ID, dto));

        assertEquals(404, error.getStatusCode().value());
        assertEquals("辅导会话已删除", error.getReason());
        assertEquals(1, requestCount.get(), "命中缓存不应再次调用模型");
    }

    @Test
    void 不同requestKey在最小间隔内被拒绝() {
        properties.setMinIntervalSeconds(5);
        service.ask(STUDENT_ID, askDTO("fixture-req-limit-a"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.ask(STUDENT_ID, askDTO("fixture-req-limit-b")));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(String.valueOf(error.getReason()).contains("提问过于频繁"));
        assertEquals(1, requestCount.get());
    }

    @Test
    void 幂等缓存始终不超过固定容量() {
        int capacity = (int) ReflectionTestUtils.getField(AiTutorServiceImpl.class, "IDEMPOTENT_MAX_ENTRIES");
        assertTrue(capacity > 0);

        for (int i = 0; i < capacity + 5; i++) {
            service.ask(STUDENT_ID, askDTO("fixture-capacity-" + i));
        }

        Map<?, ?> cache = (Map<?, ?>) ReflectionTestUtils.getField(service, "askRequests");
        assertNotNull(cache);
        assertTrue(cache.size() <= capacity, "幂等缓存超过容量上界：" + cache.size());
    }

    /* ------------------------- 控制器角色边界 ------------------------- */

    @Test
    void 教师访问AI辅导被拒绝为403() {
        AiTutorService mockedService = mock(AiTutorService.class);
        AiTutorController controller = new AiTutorController();
        ReflectionTestUtils.setField(controller, "aiTutorService", mockedService);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.listConversations(STUDENT_ID, "TEACHER"));

        assertEquals(403, error.getStatusCode().value());
        assertEquals("仅学生可以使用 AI 辅导", error.getReason());
        verifyNoInteractions(mockedService);
    }

    @Test
    void 学生访问会话列表被放行() {
        AiTutorService mockedService = mock(AiTutorService.class);
        when(mockedService.listConversations(STUDENT_ID)).thenReturn(List.of());
        AiTutorController controller = new AiTutorController();
        ReflectionTestUtils.setField(controller, "aiTutorService", mockedService);

        var response = controller.listConversations(STUDENT_ID, "STUDENT");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(200, response.getBody().getCode());
    }

    /* ------------------------- 辅助 ------------------------- */

    private AiTutorAskDTO askDTO(String requestKey) {
        AiTutorAskDTO dto = new AiTutorAskDTO();
        dto.setConversationId(CONVERSATION_ID);
        dto.setProject("TICKET");
        dto.setQuestion("TicketController_getAllActivities 接口报 500，token=fixture-token-value 也被带上了");
        dto.setCodeSnippet("axios.get('/api/practice/' + code + '/ticket/activities')");
        dto.setErrorText("GET /api/practice/fixture/ticket/activities 500");
        dto.setRequestKey(requestKey);
        return dto;
    }

    private AiTutorConversation conversation() {
        AiTutorConversation value = new AiTutorConversation();
        value.setId(CONVERSATION_ID);
        value.setStudentId(STUDENT_ID);
        value.setProject(AiTutorConversation.PROJECT_TICKET);
        value.setTitle(AiTutorConversation.DEFAULT_TITLE);
        value.setStatus(AiTutorConversation.STATUS_NORMAL);
        value.setMessageCount(0);
        value.setCreateTime(LocalDateTime.now());
        value.setUpdateTime(LocalDateTime.now());
        return value;
    }

    private AiTutorConversation deletedConversation() {
        AiTutorConversation value = conversation();
        value.setStatus(AiTutorConversation.STATUS_DELETED);
        return value;
    }

    private List<AiTutorMessage> savedMessages() {
        ArgumentCaptor<AiTutorMessage> captor = ArgumentCaptor.forClass(AiTutorMessage.class);
        verify(messageMapper, atLeast(0)).insert(captor.capture());
        return captor.getAllValues();
    }
}
