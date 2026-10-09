package com.study.vuePractiseBackend;

import com.study.vuePractiseBackend.config.AiTutorProperties;
import com.study.vuePractiseBackend.util.AiTutorContextBuilder;
import com.study.vuePractiseBackend.util.AiTutorContextBuilder.ContextBundle;
import com.study.vuePractiseBackend.util.AiTutorContextBuilder.TaskBrief;
import com.study.vuePractiseBackend.util.AiTutorSanitizer;
import com.study.vuePractiseBackend.vo.AiTutorContextRefVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 辅导纯逻辑单元测试：脱敏与上下文检索。
 *
 * 这些能力直接决定「哪些内容会离开本系统」以及「模型看到的接口是否真实存在」，
 * 因此不依赖数据库与 Spring 容器单独断言。
 * 测试素材全部是虚构哨兵值，不使用任何真实密钥；文档读取走 classpath 下的真实副本。
 */
class AiTutorUtilsTests {

    private AiTutorContextBuilder contextBuilder;
    private AiTutorProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AiTutorProperties();
        contextBuilder = new AiTutorContextBuilder();
        ReflectionTestUtils.setField(contextBuilder, "properties", properties);
        ReflectionTestUtils.setField(contextBuilder, "objectMapper", new JsonMapper());
    }

    /* ------------------------- 脱敏 ------------------------- */

    @Test
    void 脱敏覆盖引号写法裸值与环境变量占位() {
        String hexToken = "0123456789abcdef".repeat(4);
        String bearer = "fixture-bearer-9f3a";
        String skKey = "sk-fixturekey0123456789";
        String jsonPassword = "fixture-secret";
        String jsApiKey = "fixture-key";
        String bareToken = "fixture-token";
        String accessCode = "fixture-code";
        String envValue = "FIXTURE_ENV_VALUE";
        String quotedApiKey = "fixture-api-key";
        String jdbcPassword = "fixture-pwd";
        String phone = "13800138000";
        String email = "student@example.com";

        String input = String.join("\n",
                "Authorization: Bearer " + bearer,
                "{\"password\":\"" + jsonPassword + "\", \"apiKey\": \"" + quotedApiKey + "\"}",
                "{'apiKey':'" + jsApiKey + "'}",
                "token=" + bareToken,
                "accessCode: " + accessCode,
                "secret_key = ${" + envValue + "}",
                "API_KEY=" + skKey,
                "db=jdbc:mysql://127.0.0.1:3306/demo?user=root&password=" + jdbcPassword,
                "token64=" + hexToken,
                "phone=" + phone + " mail=" + email);

        assertTrue(AiTutorSanitizer.containsSensitive(input));
        String masked = AiTutorSanitizer.sanitize(input, 4000);

        for (String sentinel : List.of(hexToken, bearer, skKey, jsonPassword, jsApiKey,
                bareToken, accessCode, envValue, quotedApiKey, jdbcPassword, phone, email)) {
            assertFalse(masked.contains(sentinel), "脱敏后仍能读到敏感值：" + sentinel);
        }
        assertTrue(masked.contains(AiTutorSanitizer.MASK));
        // 键名保留：模型仍能看到字段结构，只是值被替换
        assertTrue(masked.contains("\"password\""));
        assertTrue(masked.contains("accessCode"));
    }

    @Test
    void 单值脱敏后不再被识别为敏感内容() {
        assertFalse(AiTutorSanitizer.containsSensitive("fixture-secret"));
        assertFalse(AiTutorSanitizer.containsSensitive("普通中文提问：报名接口怎么调"));
        assertFalse(AiTutorSanitizer.containsSensitive(null));
    }

    @Test
    void 普通代码与提问不被误伤() {
        String code = "public Result<TicketActivity> getAllActivities(String campus, Integer status) {\n"
                + "    if (status == null) { return Result.success(list); }\n"
                + "    return Result.error(\"状态只能为0,1或2\");\n"
                + "}";
        assertEquals(code, AiTutorSanitizer.sanitize(code, 4000));
    }

    @Test
    void 超长文本先脱敏再截断并追加标记() {
        // 哨兵值放在最前面：先截断再脱敏的话，fixture-secret 会残留在前 100 个字符里
        String input = "password=fixture-secret " + "a".repeat(5000);
        String masked = AiTutorSanitizer.sanitize(input, 100);

        assertEquals(100, masked.length());
        assertTrue(masked.endsWith(AiTutorSanitizer.TRUNCATED));
        assertFalse(masked.contains("fixture-secret"));
        assertTrue(masked.startsWith("password=" + AiTutorSanitizer.MASK));
    }

    @Test
    void null与空串保持原样() {
        assertNull(AiTutorSanitizer.sanitize(null, 100));
        assertEquals("", AiTutorSanitizer.sanitize("", 100));
    }

    /* ------------------------- 上下文检索 ------------------------- */

    @Test
    void TICKET提问只引用TICKET接口且来源与上下文一致() {
        ContextBundle bundle = contextBuilder.build("TICKET", TaskBrief.EMPTY,
                "TicketController_getAllActivities 这个接口怎么调");

        assertFalse(bundle.contextText().isEmpty());
        assertFalse(bundle.refs().isEmpty());
        assertTrue(bundle.refs().stream()
                        .anyMatch(ref -> "TicketController_getAllActivities".equals(ref.getId())),
                "未命中真实存在的抢票活动列表接口");
        assertTrue(bundle.contextText().contains("/api/practice/{accessCode}/ticket/activities"));

        for (AiTutorContextRefVO ref : bundle.refs()) {
            if (AiTutorContextRefVO.TYPE_API.equals(ref.getType())) {
                assertTrue(bundle.contextText().contains(ref.getPath()),
                        "来源声明的接口没有真的写进上下文：" + ref.getPath());
                assertTrue(bundle.contextText().contains(ref.getId()),
                        "来源声明的 operationId 没有真的写进上下文：" + ref.getId());
            }
        }
        for (AiTutorContextRefVO ref : bundle.refs()) {
            assertFalse(ref.getPath().contains("/repair/"), "TICKET 请求混入了报修接口：" + ref.getPath());
        }
        assertFalse(bundle.contextText().contains("RepairController_"), "TICKET 上下文混入了报修接口");
    }

    @Test
    void REPAIR提问只引用REPAIR接口并带请求体结构摘要() {
        ContextBundle bundle = contextBuilder.build("REPAIR", TaskBrief.EMPTY,
                "RepairController_createDevice 的请求体怎么写");

        assertTrue(bundle.refs().stream()
                .anyMatch(ref -> "RepairController_createDevice".equals(ref.getId())));
        assertTrue(bundle.contextText().contains("body(application/json){"));
        // required 用 * 标记，枚举用 a|b
        assertTrue(bundle.contextText().contains("operatorId:integer*"));
        assertTrue(bundle.contextText().contains("校区A|校区B"));
        assertTrue(bundle.contextText().contains("resp200"));
        for (AiTutorContextRefVO ref : bundle.refs()) {
            assertFalse(ref.getPath().contains("/ticket/"), "REPAIR 请求混入了抢票接口：" + ref.getPath());
        }
        assertFalse(bundle.contextText().contains("TicketController_"), "REPAIR 上下文混入了抢票接口");
    }

    @Test
    void 文档来源使用仓库里的真实文件名() {
        ContextBundle bundle = contextBuilder.build("TICKET", TaskBrief.EMPTY,
                "TicketController_getAllActivities 接口");

        AiTutorContextRefVO docRef = bundle.refs().stream()
                .filter(ref -> AiTutorContextRefVO.TYPE_DOC.equals(ref.getType()))
                .findFirst()
                .orElse(null);
        assertNotNull(docRef, "缺少文档来源");
        assertEquals("ai-docs/ticket/activities/operations.json", docRef.getId());
        assertEquals("frontend/src/data/ticket/activities/operations.json", docRef.getPath());
    }

    @Test
    void 登录问题命中共享的系统接口且不跨业务项目() {
        ContextBundle ticket = contextBuilder.build("TICKET", TaskBrief.EMPTY, "怎么登录");
        assertTrue(ticket.refs().stream().anyMatch(ref -> "SysLoginController_login".equals(ref.getId())),
                "TICKET 问题应能引用共享的登录接口");
        AiTutorContextRefVO systemDoc = ticket.refs().stream()
                .filter(ref -> "ai-docs/system-spec.json".equals(ref.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(systemDoc, "缺少系统文档来源");
        assertEquals(AiTutorContextRefVO.TYPE_DOC, systemDoc.getType());
        assertEquals("frontend/src/data/system-spec.json", systemDoc.getPath());
        assertTrue(ticket.contextText().contains("/login"));
        // 共享 system 不等于跨项目：TICKET 里不能出现任何报修条目
        assertTrue(ticket.refs().stream().noneMatch(ref -> ref.getPath().contains("/repair/")));
        assertFalse(ticket.contextText().contains("RepairController_"));

        ContextBundle repair = contextBuilder.build("REPAIR", TaskBrief.EMPTY, "怎么登录");
        assertTrue(repair.refs().stream().anyMatch(ref -> "SysLoginController_login".equals(ref.getId())));
        // REPAIR 同样只能带 repair + system，不能出现抢票条目
        assertTrue(repair.refs().stream().noneMatch(ref -> ref.getPath().contains("/ticket/")));
        assertFalse(repair.contextText().contains("TicketController_"));
    }

    @Test
    void 任务信息进入上下文并出现在来源里() {
        TaskBrief task = new TaskBrief(11L, "抢票活动列表任务",
                "完成活动列表接口的查询与分页", "能按校区与状态筛选");

        ContextBundle bundle = contextBuilder.build("TICKET", task,
                "TicketController_getAllActivities 接口报错 500");

        assertTrue(bundle.contextText().contains("任务要求"));
        assertTrue(bundle.contextText().contains("完成活动列表接口的查询与分页"));
        AiTutorContextRefVO taskRef = bundle.refs().stream()
                .filter(ref -> AiTutorContextRefVO.TYPE_TASK.equals(ref.getType()))
                .findFirst()
                .orElse(null);
        assertNotNull(taskRef, "任务来源缺失");
        assertEquals("11", taskRef.getId());
        assertEquals("抢票活动列表任务", taskRef.getTitle());
    }

    @Test
    void 放不进预算的任务不会出现在来源里() {
        properties.setMaxContextChars(12000);
        ContextBundle apiOnly = contextBuilder.build("TICKET", TaskBrief.EMPTY,
                "TicketController_getAllActivities 接口");
        assertFalse(apiOnly.contextText().isEmpty());

        // 预算只够刚才那份接口上下文：任务段落放不下，因此不能声明引用了任务
        properties.setMaxContextChars(apiOnly.contextText().length() + 20);
        String taskTitle = "不可能塞进预算的超长任务标题";
        TaskBrief bigTask = new TaskBrief(12L, taskTitle, "要求".repeat(700), "标准".repeat(700));
        ContextBundle bundle = contextBuilder.build("TICKET", bigTask,
                "TicketController_getAllActivities 接口");

        assertTrue(bundle.contextText().contains("/api/practice/{accessCode}/ticket/activities"));
        assertTrue(bundle.contextText().length() <= apiOnly.contextText().length() + 20);
        assertFalse(bundle.contextText().contains(taskTitle));
        assertTrue(bundle.refs().stream()
                .noneMatch(ref -> AiTutorContextRefVO.TYPE_TASK.equals(ref.getType())));
    }

    @Test
    void 预算极小时不写入上下文也不声明任何来源() {
        properties.setMaxContextChars(1);

        ContextBundle bundle = contextBuilder.build("TICKET",
                new TaskBrief(13L, "任务标题", "任务要求", "验收标准"), "TicketController_getAllActivities");

        assertEquals("", bundle.contextText());
        assertTrue(bundle.refs().isEmpty());
    }

    @Test
    void 没有命中关键词时返回空来源() {
        // 纯 ASCII 占位串，确定不会命中任何接口文档
        ContextBundle bundle = contextBuilder.build("TICKET", TaskBrief.EMPTY, "zzzqqqxxx-wvvv");

        assertEquals("", bundle.contextText());
        assertTrue(bundle.refs().isEmpty());
    }
}
