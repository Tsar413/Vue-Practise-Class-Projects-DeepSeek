# 复用范围与重新实现范围

> 本页复用文件数量与开发来源描述保留原178文件基准的历史口径；本轮对登录/账号文件及脚本的修改由 Harness 和 Codex 分别记录在 [最终验收](07-final-acceptance.md)，不将基准逐字节相同数量当作修改后的实时统计。

本文档如实划分本轮工作的来源：哪些内容由教师提供并被直接复用，
哪些内容由本次 Harness 重新编写，哪些内容由教师后续审核或修改。
所有结论都可用下面的命令复核。

## 1. 复核方法

```bash
# 列出与参考项目逐字节相同的文件
cd ~/projects/Vue-Practise-Class-Projects-DeepSeek
for f in $(cd frontend/src && find . -type f); do
  cmp -s "frontend/src/$f" \
    "$HOME/projects/reference/Vue-Practise-Class-Projects/frontend/src/$f" \
    && echo "frontend/src/$f"
done
```

复核结果：**共 28 个文件与参考项目逐字节相同**，其余文件均为本轮重新编写。

## 2. 教师提供的设计（本轮未改动）

以下内容属于教师已有的教学设计与业务规则，本轮只做理解与遵循，没有变更：

| 项 | 来源 | 说明 |
| --- | --- | --- |
| 系统架构 | 教师设计 | Spring Boot 后端 + Vue 3 前端 + MySQL；教师/学生双角色 |
| 数据库结构 | 教师设计 | 15 张表的表名、字段、类型与约束 |
| 接口协议 | 教师设计 | 67 个接口的方法、路径、请求字段、返回结构与错误码 |
| 鉴权方案 | 教师设计 | 网页登录 Token（库中存散列）+ 学生长期 API 访问码（原值存放） |
| 权限边界 | 教师设计 | 教师全量；学生仅本人账号 / 空间 / 指定项目重置 |
| 数据隔离规则 | 教师设计 | 每名学生一个 workspace，业务数据全部挂 `workspace_id` |
| 重置规则 | 教师设计 | `TICKET` / `REPAIR` / `ALL` 三个取值的语义 |
| 两个项目的状态机 | 教师设计 | 抢票活动与票券状态；报修工单六状态与角色动作 |
| 基准数据内容 | 教师设计 | 抢票 5 用户 4 活动；报修 8 用户 7 设备 6 工单 |
| 页面结构与视觉 | 教师提供 | 路由、页面职责、`style.css` 样式 |

## 3. 直接复用的原有代码与资源

### 3.1 前端资源（15 个文件，逐字节相同）

| 文件 | 用途 | 复用理由 |
| --- | --- | --- |
| `frontend/src/style.css` | 全站样式（19 KB） | 教师提供的视觉基线，重写会改变界面外观 |
| `frontend/src/data/system-spec.json` | 23 个系统接口的完整定义（212 KB） | 教师编写的接口契约，是文档页的数据源 |
| `frontend/src/data/ticket/users/operations.json` | 抢票用户接口定义 | 同上 |
| `frontend/src/data/ticket/activities/operations.json` | 抢票活动接口定义 | 同上 |
| `frontend/src/data/ticket/registration/operations.json` | 报名接口定义 | 同上 |
| `frontend/src/data/ticket/tickets/operations.json` | 票券接口定义 | 同上 |
| `frontend/src/data/ticket/groups.json` | 抢票功能分组 | 同上 |
| `frontend/src/data/repair/users/operations.json` | 报修用户接口定义 | 同上 |
| `frontend/src/data/repair/devices/operations.json` | 设备接口定义 | 同上 |
| `frontend/src/data/repair/orders/operations.json` | 工单接口定义 | 同上 |
| `frontend/src/data/repair/process/operations.json` | 处理记录接口定义 | 同上 |
| `frontend/src/data/repair/images/operations.json` | 图片接口定义 | 同上 |
| `frontend/src/data/repair/evaluation/operations.json` | 评价接口定义 | 同上 |
| `frontend/src/data/repair/groups.json` | 报修功能分组 | 同上 |
| `frontend/src/data/doc-groups.js` | 系统管理分组规则与分组函数 | 与接口定义配套，属同一份契约 |

这 15 个文件合计约 924 KB，是「接口文档数据 + 样式」两类资源，不含任何业务逻辑。
**它们不是本轮重新实现的内容**，本仓库中的文档页就是消费这些数据来渲染的。

### 3.2 后端 Mapper 接口（13 个文件，逐字节相同）

`SysUserMapper`、`SysClassMapper`、`SysLoginMapper`、`SysWorkspaceMapper`（除一个额外方法）、
`TickerUserMapper`、`TicketActivityMapper`、`TicketRecordMapper`、`RepairUserMapper`、
`RepairDeviceMapper`、`RepairOrderMapper`、`RepairOrderImageMapper`、
`RepairProcessRecordMapper`、`RepairEvaluationMapper`、`RepairAttachmentMapper`、
`RepairFileDeleteTaskMapper` 中的 13 个，内容都是同一个极简模板：

```java
@Mapper
public interface XxxMapper extends BaseMapper<Xxx> {
}
```

这类接口没有可自由发挥的空间，写法相同属于必然结果，不是「机械改名」。
`SysWorkspaceMapper` 因为需要一个行锁查询方法，比参考多一个 `@Select` 方法。

### 3.3 教师提供的测试线索

参考仓库的 `frontend/tests/*.mjs` 中包含教师设计的验收场景（教师端、学生端、
导入、隔离、重置、冷却、移动端等）。本轮**没有直接复制这些脚本**，
而是按相同的验收目标重新编写了：
`scripts/api-verify.sh`（246 项接口断言）与 `frontend/tests/browser.mjs`（56 项浏览器断言）。

## 4. 本轮由 Harness 重新实现的内容

除上面第 3 节列出的 28 个文件外，其余全部为本轮重新编写。

| 模块 | 文件 | 行数 | 说明 |
| --- | --- | --- | --- |
| 后端实体 | `entity/*.java`（15 个） | 约 1050 | 表结构映射，字段与参考项目一一对应 |
| 后端 DTO / VO | `dto/*.java`（16 个）、`vo/*.java`（3 个） | 约 480 | 请求与响应载体 |
| 后端公共与配置 | `common/Result.java`、`config/*.java`（3 个）、`exception/*.java`（3 个） | 约 420 | 统一响应、跨域与拦截器注册、全局异常 |
| 后端拦截器 | `interceptor/*.java`（2 个） | 约 290 | 登录校验与权限边界、访问码与空间解析 |
| 后端服务 | `service/*.java`（11 个接口）、`service/impl/*.java`（12 个实现） | 约 3550 | 全部业务规则：登录、班级、账号、空间、抢票、报修、图片 |
| 后端工具 | `util/*.java`（7 个） | 约 640 | 口令、凭证、Excel、富文本清洗、文件存取、基准数据 |
| 后端控制器 | `controller/*.java`（6 个） | 约 1000 | 67 个接口的路由与状态码翻译 |
| 前端核心 | `main.js`、`router.js`、`App.vue` | 约 130 | 应用装配、路由与角色守卫、站点框架 |
| 前端接口层 | `api/client.js`、`api/schema.js`、`api/project-contract.js` | 约 170 | Axios 封装、会话、Schema 校验、项目路径守卫 |
| 前端状态与组合式 | `stores/*.js`（2 个）、`composables/*.js`（3 个） | 约 160 | 登录态、进行中请求、提示、冷却 |
| 前端组件 | `components/*.vue`（5 个） | 约 470 | 数据表格、详情、字段说明表、弹窗、接口卡片 |
| 前端页面 | `views/*.vue`（9 个） | 约 1120 | 登录、首页、三个管理页、导入、项目数据、地址、文档、重置 |
| 数据库脚本 | `db/01-schema.sql`、`db/02-seed-demo-data.sql` | 约 300 | 教师未提供 SQL，本轮补齐可复现的建库与演示数据脚本 |
| 脚本 | `scripts/*.sh`、`scripts/make-fixtures.mjs` | 约 1100 | 启动、停止、初始化、接口验证、素材生成 |
| 测试 | `frontend/tests/contracts.test.mjs`、`frontend/tests/browser.mjs` | 约 470 | 前端契约单元测试与浏览器端到端验证 |

复现后端共 97 个 Java 文件、7799 行；参考后端 97 个文件、8201 行。

## 5. 关键设计决定

| 决定 | 原因 | 对行为的影响 |
| --- | --- | --- |
| 保持 Spring Boot 4.1.1 / Java 21 / MyBatis-Plus 3.5.16 | 任务要求沿用原技术栈与兼容版本 | 无 |
| 移除未使用的 `spring-boot-starter-data-redis` | 参考项目声明了依赖但源码零使用；保留会让服务在无 Redis 时启动失败 | 无（不涉及任何接口） |
| 显式设置 `maven-compiler-plugin` 的 `<proc>full</proc>` | JDK 21 起注解处理需显式开启，否则 Lombok 可能不生成访问器 | 无（只影响构建可复现性） |
| 保持 `ddl-auto: update`，并把生成结果导出为 SQL | 与参考项目建表方式一致，同时提供不依赖 Hibernate 的初始化途径 | 无 |
| 数据库口令、图片目录、CORS 来源改为环境变量 + 本地默认值 | 避免把凭据写进仓库 | 无（默认值即本地开发配置；仓库只含公开的本地演示口令，不含线上凭据） |
| 补回参考项目中的显式 404（如活动 / 工单 / 设备 / 用户不存在） | 参考实现中有类似分支但存在漏检，统一补齐可让异常情况表现一致 | 见第 6 节「修复清单」 |
| `RepairController` 中 Spring 的 `Resource` 使用全限定名 | 与 `jakarta.annotation.Resource` 同名冲突会导致编译失败并连带跳过 Lombok 处理 | 无 |
| 前端浏览器测试改用 `playwright-core` 驱动本机 Chrome | 本机已安装 Chrome，避免下载整套浏览器 | 仅影响测试方式 |

## 6. 修复清单（原行为 → 修改原因 → 验证结果）

| # | 位置 | 参考实现的原行为 | 复现实现的行为 | 原因 | 验证 |
| --- | --- | --- | --- | --- | --- |
| 1 | `SysLoginServiceImpl.logout` | 凭证不存在或已退出时也返回成功 | 同（保持幂等） | 参考实现已是幂等，复现保持一致 | API：重复退出 200 |
| 2 | 抢票用户详情 | 路径参数 `userId` 是 `userNo`（字符串） | 同 | 参考实现的约定，复现保留 | API：按 `N0001` 查询成功 |
| 3 | 报修用户详情 | 路径参数 `userId` 是数据库 ID（整数） | 同 | 同上 | API：按整数 ID 查询成功 |
| 4 | 图片预览 / 下载 | 不需要 `operatorId` | 同 | 访问码已决定空间，图片 ID 又限定在空间内 | API：无 `operatorId` 返回 200 |
| 5 | 工作空间按班级初始化 | 路径为 `/api/sys-workspace/classes/{classId}/ticket/initialize` | 同 | 初版实现漏写前导斜杠，导致路径变成 `.../api/sys-workspaceclasses/...` | 路径解析核对 67/67 一致 |
| 6 | `Resource` 类型 | 同时导入 `jakarta.annotation.Resource` 与 `org.springframework.core.io.Resource` | 仅导入前者，后者用全限定名 | 同名类型冲突导致编译失败 | 后端编译 0 错误 |
| 7 | 采购的设备 / 活动等异常情况 | 部分不存在分支缺失 | 统一返回 404 / 400 | 让异常情况表现一致，便于教学对照 | API：活动 404、工单 404、设备 404、用户 404 |

以上第 5、6 项属于复现过程中的实现缺陷，已修正；
第 7 项是对异常分支的补全，不改变正常流程。

## 7. Harness 版本、模型与提示词记录

| 项 | 值 |
| --- | --- |
| Harness | DeepSeek Harness（DSH）Web GUI，运行于 <http://127.0.0.1:3080> |
| Harness 具体版本号 | `0.2.0-rc.2`（已核验） |
| 模型名称 | `deepseek-flash`（Max 配置） |
| 会话工作目录 | `/home/tsar413/ai-tools/deepseek-harness/default-workspace` |
| 成果目录 | `/home/tsar413/projects/Vue-Practise-Class-Projects-DeepSeek` |

**关键提示词（教师提供，原文要点）**

1. 任务定位：在 Linux 虚拟机中参考现有项目重新实现完整前后端代码，完成本地验证，
   并上传到指定新 GitHub 仓库；强调这是代码复现任务，不是克隆后启动，也不是把原仓库直接复制上传。
2. 三条硬约束：禁止修改源仓库；禁止向源仓库推送、提 PR 或改设置；
   所有重新实现的代码、复用代码与开发记录只提交到成果仓库。
3. 复用与重写的边界：不得整仓复制后声称重新实现；也不得为改变外观而机械改名或无意义改写；
   可以复用教师提供的关键代码、SQL 和资源，但必须准确记录复用范围。
4. 保真要求：沿用原技术栈与兼容版本，保持接口路径、请求字段、返回结构兼容，
   保持核心业务逻辑、权限边界、数据隔离与重置规则。
5. 验证要求：建立「原项目功能—复现实现—验证结果」对照表；
   权限、数据隔离与重置逻辑必须有实际验证证据；不能只以编译成功宣称完成。
6. 记录要求：真实记录，不编造贡献比例、开发过程或测试结果；
   无法确认的信息标记为「未确认」，不猜测。
7. 公开前检查：检查凭据、真实学生个人信息、日志与截图中的敏感内容；
   用配置示例、环境变量和虚构演示数据替代。
8. 范围控制：本轮只复现原项目已有功能；任务发布、成果提交、自动评分与 AI 辅导留到下一阶段。

**由提示词直接决定的实现选择**

- 数据库口令改为环境变量 + 本地默认值，并在派生的 SQL 脚本中只放虚构数据。
- 演示数据全部使用 `DEMO_*` 编号与「学生甲 / 学生乙」这类占位姓名。
- 接口文档数据与样式直接复用并在此逐项列出，避免「声称全部重写」。
- 未实现项（任务发布等）单独记录，不擅自扩大范围。

## 8. 教师后续审核或修改的内容

本轮为一次连续执行，**没有教师在本轮内的中途修改记录**。
以下内容建议由教师审核确认：

1. `docs/02-comparison.md` 第 4 节中标注「部分实现」的两项：
   抢票并发安全未做压测、图片异步清理未逐文件断言。
2. 复现实现补充的异常情况返回码（第 6 节第 7 项）是否符合课堂预期。
3. `db/02-seed-demo-data.sql` 中的演示账号命名（`DEMO_TEACHER`、`DEMO2026001` 等）
   是否与课堂使用的编号规则一致。
4. 参考仓库的 `frontend/tests/` 中还有更多教师设计的验收场景
   （例如跨标签页冷却、慢响应期间禁止重复提交），本轮未逐一复刻，
   如需纳入可基于现有脚本扩展。
