# 教学任务、成果评价与 AI 实训辅导（新增模块）

本轮在既有实训平台（校园抢票 TICKET、校园设备报修 REPAIR）之上新增「实训任务 →
成果草稿/正式版本 → 教师评分或退回 → 修改再交」的完整闭环，以及基于真实 DeepSeek
模型的 AI 实训辅导。既有 workspace、访问码、两个项目的接口与重置规则保持不变。

分工：DeepSeek Harness 负责教学模块、权限白名单、前端与文档；AI 辅导模块由内部另一个
执行方按同一套约束实现，Harness 复核并汇总；Codex 负责环境、隔离库迁移应用、独立复核、
真实模型联调与最终提交。

## 1. 数据表（增量迁移，只新增）

迁移脚本：`db/03-teaching-ai-tutor.sql`；执行器：`scripts/migrate.py`。
版本 `2026-10-09-v3`，13 张新增表。SQL 中**没有** `USE`、`INSERT`、`DROP`，
目标库由执行器显式传入。

| 表 | 用途 | 关键约束 |
| --- | --- | --- |
| `teaching_task` | 实训任务 | `teacher_id` 外键 → `sys_user` |
| `teaching_task_class` | 任务分配班级 | 唯一键 (`task_id`,`class_id`) |
| `teaching_submission` | 每任务每学生一条成果 | 唯一键 (`task_id`,`student_id`)；含草稿字段 |
| `teaching_submission_version` | 不可覆盖的正式版本 | 唯一键 (`submission_id`,`version_no`) |
| `teaching_submission_section` | 成果章节（草稿章节软删除） | `deleted_at` 标记移除 |
| `teaching_attachment` | 截图附件（软删除） | `deleted_at`；不随版本删除 |
| `teaching_version_attachment` | 版本↔截图**不可变**关联 | 唯一键 (`version_id`,`attachment_id`) |
| `teaching_section_attachment` | 章节↔截图当前归属（可替换） | 唯一键 (`section_id`,`attachment_id`) |
| `teaching_file_delete_task` | 待删除文件队列 | `processed` 状态 |
| `teaching_evaluation` | 教师评价（绑定版本） | `is_current` 标记当前评价 |
| `teaching_submit_request` | 提交幂等键 | 唯一键 (`submission_id`,`request_key`) |
| `ai_tutor_conversation` | 学生私聊会话（软删除） | `status` |
| `ai_tutor_message` | 会话消息 | `role` / `context_refs` |

迁移执行器的行为（`scripts/migrate.py`）：

* 必须显式 `--database`；库名先做标识符校验，再拼接任何 SQL；
* 只允许本机连接（socket 或 127.0.0.1/localhost/::1）；
* 迁移锁是**数据库专属 fcntl 文件锁**，覆盖整个流程
  ——不使用 MySQL `GET_LOCK`，因为每次 mysql 客户端调用都是独立会话，锁无法保持；
* 计算脚本 SHA-256；若该库已记录同一版本而校验和不一致则拒绝执行；
* SQL 成功且结构校验（表、关键列类型/排序规则、唯一约束）通过后才写 `schema_migration`；
* 失败保留已建结构、不自动 `DROP`、退出非零并打印恢复步骤（前向修复，不删表）；
* 重复执行对已存在且一致的结构是幂等的，且不刷新首次 `applied_at`。

应用侧 `spring.jpa.hibernate.ddl-auto` 已由 `update` 改为 **`none`**：
新增结构只能通过迁移脚本创建，未迁移时访问新接口会明确报错，而不是被 Hibernate 静默建表。

## 2. 接口

### 2.1 教学模块 `/api/teaching`

| 方法 | 路径 | 角色 | 说明 |
| --- | --- | --- | --- |
| GET | `/tasks` | 教师/学生 | 教师看到全部任务；学生只看到分配给自己班级且非草稿的任务 |
| GET | `/tasks/{id}` | 教师/学生 | 任务详情；学生版本会带本人状态与评分 |
| POST | `/tasks` | 教师 | 新建任务，固定创建为草稿 |
| PUT | `/tasks/{id}` | 教师 | 编辑任务（见第 4 节限制） |
| POST | `/tasks/{id}/status` | 教师 | `PUBLISH` 发布 / `CLOSE` 关闭 |
| GET | `/tasks/{id}/stats` | 教师 | 未提交/待评价/已评价/需修改统计 |
| GET | `/tasks/{id}/submissions` | 教师 | 按班级查看学生提交行，可带 `classId` |
| GET | `/submissions/{id}` | 教师 | 某学生成果的全部历史版本与每版评价 |
| POST | `/evaluations` | 教师 | 给分（PASS）或退回（REVISE） |
| GET | `/tasks/{id}/submission` | 学生 | 本人成果（草稿 + 历史版本 + 评价） |
| PUT | `/tasks/{id}/draft` | 学生 | 保存草稿（字段 + 章节 + 截图归属） |
| POST | `/tasks/{id}/submissions` | 学生 | 正式提交，生成不可覆盖版本（`requestKey` 幂等） |
| POST | `/tasks/{id}/attachments` | 学生 | 上传截图（JPEG/PNG/WebP，≤5MB） |
| GET | `/tasks/{id}/attachments` | 学生 | 本人该任务的可用截图 |
| DELETE | `/attachments/{id}` | 学生 | 删除**未冻结**的临时截图 |
| GET | `/attachments/{id}/content` | 教师/学生 | 读取截图（学生仅本人；`nosniff`） |

### 2.2 AI 辅导 `/api/ai-tutor`（仅学生）

`GET /conversations`、`POST /conversations`、`DELETE /conversations/{id}`、
`GET /conversations/{id}/messages`、`POST /ask`。

教师访问一律 **403**：不能因为既有规则「教师放行全部系统接口」而读到学生私聊。

### 2.3 权限白名单

`SysLoginInterceptor` 对学生的放行改为**逐条「方法 + 路由模板」匹配**
（`isStudentTeachingEndpoint` / `isStudentAiTutorEndpoint`），不再使用前缀放行。
拦截器仍沿用教师全局放行；教学模块的教师专用路由不在学生白名单内，
AI 辅导控制器另行明确拒绝教师访问，不能依赖教师全局放行规则。

## 3. 业务规则

**任务状态**：0 草稿（学生不可见）→ 1 已发布 → 2 已关闭。
关闭只改状态，已有提交、版本、截图、评价全部保留；草稿任务不需要关闭。
关闭后：学生只能查看历史与评价；**不允许保存草稿，也不允许正式提交**（前后端一致）。

**截止时间**：过期且任务不允许逾期时拒绝提交；允许逾期时正常提交并在版本上标记 `late`。

**成果与版本**：每「任务 + 学生」只有一条成果记录；每次正式提交生成一个递增且
不可覆盖的版本；再次提交进入待评价，旧评价保留在原版本上（`is_current` 置 0），
因此「查看每版评分评语」与「退回后修改重交」都可用。

**评价**：分数与评语由教师确定，系统不自动评分、不查重、不排名、不执行学生代码。
`PASS` 必须给分且在 `0..满分` 内；`REVISE` 不给分。评价非最新版本时不改变最新版本状态。

**编辑限制**（避免破坏历史）：已有提交时不允许更换所属项目；满分不能低于已给出的最高分；
不允许取消已有成果的班级分配。任务不做物理删除。

**并发与幂等**：
* 锁顺序统一为 `teaching_task → teaching_submission → 版本/评价`；
* 写操作用 `TransactionTemplate` 显式开启（同类内部 this 调用不经过 Spring 代理，
  注解式事务不生效），隔离级别固定 `READ_COMMITTED`；
* 取得任务行锁后**重新校验**班级分配与可见性，避免「先通过校验、后被移出班级」的竞态；
* 成果行用 `INSERT IGNORE` + `FOR UPDATE` 创建，版本号在该行锁内递增；
* 正式提交以 (`submission_id`,`request_key`) 唯一键幂等，重复点击/网络重试不生成第二个版本；
* 同一 requestKey 不同 payload：返回既有成果，不另造版本。

**截图与磁盘**：
* 独立目录 `teaching.files.root`（默认 `./teaching-files`）与独立表，不复用 `repair-files`
  与 `repair_attachment`；
* 复用既有安全能力：按内容解码校验格式、像素与大小上限、路径越界与符号链接保护，
  伪图片会被拒绝；
* 版本↔截图关联**只增不改**：退回后只改说明、保留原截图再交，不会把旧版本的截图移走；
* 附件行只软删除；磁盘文件只有在「无任何版本引用且无草稿引用」时才进入删除队列；
* `TeachingFileCleanupWorker` 每 30 秒消费队列：独立事务（`REQUIRES_NEW` + `READ_COMMITTED`）、
  先取任务行锁、再二次确认无引用，**只有磁盘确实删除才标记 `processed=1`**，失败留队重试；
  若发现又被引用，则撤回删除意图而不是删文件。

## 4. 配置

```yaml
teaching:
  files:
    root: ${TEACHING_FILES_ROOT:./teaching-files}
ai:
  tutor:
    base-url: ${AI_TUTOR_BASE_URL:}
    model: ${AI_TUTOR_MODEL:}
    api-key: ${AI_TUTOR_API_KEY:}          # 只从环境变量读取，仓库中不存在任何密钥
    connect-timeout-seconds: 5
    timeout-seconds: 30
    max-response-bytes: 262144
    max-tokens: 1200
    max-context-chars: 12000
    max-question-chars: 2000
    max-code-chars: 4000
    max-error-chars: 2000
    max-history-messages: 8
    max-output-chars: 4000
    max-conversations-per-student: 50
    min-interval-seconds: 5
    daily-limit-per-student: 100
    max-concurrent-per-student: 1
    max-concurrent-global: 4
    acquire-timeout-millis: 2000
    thinking-enabled: false
```

* 密钥只来自环境变量（用户的忽略文件 `.runtime/ai-tutor.env`），
  **不读取** Harness/Codex 的任何凭据，`AiTutorProperties` 的 `toString` 已排除 `apiKey`。
* 未配置时接口返回 `200` + `configured=false` + `notice="AI辅导暂未配置"`，
  **不调用模型、也不生成模拟答案**；只保存已脱敏的用户提问。
* 上游失败映射为 `503` 业务错误（认证失败/频率/超时/响应过大/暂不可用），
  **不使用 401**，避免前端误判为登录失效；任何提示都不包含内部 URL、key 或原始异常。

## 5. 前端

新增路由：`/teacher/tasks`、`/teacher/tasks/new`、`/teacher/tasks/:id`、
`/teacher/tasks/:id/edit`、`/tasks`、`/tasks/:id`、`/tutor`。

* `frontend/src/api/teaching.js`：教学与 AI 接口封装。注意两点——
  ① 不复用 `client.unwrap`（它只接受 `code===200`），本模块接受 **200 与 201**；
  ② AI 提问单独把客户端超时放宽到 **90s**（大于后端总时限），减少前端提前超时；
  同一提问复用同一个 `requestKey`，但其保证范围有限，见第 7 节「AI 重试的实际保证范围」。
* 任务要求、验收标准、AI 回答都由 `marked` + `DOMPurify` 渲染（沿用既有 `Endpoint.vue` 的做法），
  防 XSS；参考资料链接只允许 http/https，服务端不会抓取学生提交的 URL。
* 加载/空/错误态与提交中的按钮禁用齐备；成果链接与截图至少一项、完成说明必填在前端同步校验。
* 新页面**不新增独立样式文件**，全部追加在全局 `frontend/src/style.css` 末尾，
  沿用既有白底青色变量（`--cyan` / `--green` / `--border`）与断点（1000 / 850 / 600），
  未修改任何既有页面规则（diff 为纯新增 141 行）。任务卡、状态标签、会话区、表单、
  章节/历史/截图按钮均有专属样式；窄屏自动单列，长代码与长 URL 不撑破布局。
* AI 辅导页可在页面内选择**项目**与**本人任务**（任务列表由后端按班级过滤）；
  从任务详情或接口文档跳转时不会自动打开无关旧会话；切换会话会同步该会话的
  `project`/`taskId`（避免后端边界校验拒绝），并重置幂等键；在途提问期间禁止切换会话，
  防止回答串到另一个会话。

## 6. 测试

| 类型 | 内容 | 状态 |
| --- | --- | --- |
| 迁移（本地一次性库） | 结构完整、关键列/唯一约束校验通过、重复执行幂等、状态查询 | 通过 |
| 迁移（Codex 隔离库） | `vue_teaching_review_20261009` 应用成功；重复执行与锁冲突、非法库名均按预期拒绝；原库 15 表 124 行与 12 张图片完全不变 | 通过 |
| 后端单元测试（总计 34 = 旧 9 + 新 25） | 旧 9 项为原有工具与安全测试；新增 25 项覆盖 AI 脱敏、上下文按项目过滤与 refs 一致（含 system 共享条目）、未配置/401/429/超时/响应过大、幂等与缓存上界、教师 403 | 通过（使用虚构哨兵，未连真实模型）；以 `docs/09` 证据为准 |
| 教学接口 | 独立 HTTP 完整流程 77 项 + 补充边界 8 项 + 浏览器完整闭环 | 通过（证据见 `docs/09-teaching-acceptance.md`，以该文件为准） |
| 真实模型调用 | 平台 HTTP 两轮 + 学生浏览器一次 | 通过，共 3 次平台真实请求（证据同上） |
| 前端测试与构建 | 既有 7 项测试通过；Vite 正式构建通过 | 通过（证据同上） |

> 上表最后一列中的「通过」均指由 Codex 执行并记录在 `docs/09-teaching-acceptance.md` 的证据；
> 本文档不重复粘贴该证据，计数与结论以 `docs/09` 为准。

## 7. 本轮限制与未完成项

* **幂等为进程内保证**：`ai_tutor_message` 没有 request_key 列，重启或多实例部署后
  幂等缓存失效。跨重启强幂等需要后续新增迁移（本轮迁移已冻结应用，不再原地修改）。
* **AI 重试的实际保证范围**（不要对外承诺「重试一定不会重复调用」）：
  - 保证：**成功的回答**在进程内按 `requestKey` 缓存并被复用；**同一 `requestKey` 的在途请求**
    复用首个请求的结果；
  - **不保证**：请求**失败**（超时、上游 401/429/5xx、响应过大等）时后端会**移除该幂等键**，
    因此失败后重试**可能再次调用模型**，也无法保证供应商侧未计费；
  - 因此前端错误提示写的是「请求未完成，请先查看会话记录；超时后重试可能再次调用模型」，
    而不是「重试不会重复生成回答」。放宽客户端超时（90s）只是减少前端提前超时，
    不等于消除了重复调用。
* 频率/间隔限制也是进程内的（单实例有效）；每日上限是查库的。
* `thinking` 字段：默认显式下发 `{"type":"disabled"}`；启用语义未实测，故不传该字段。
* 教师查看学生成果时按现有教师规则放行（教师范围沿用既有规则，未新增细粒度授权）。
* 抢票/报修高负载压测、生产部署、宿主机浏览器连通性仍未验证。

## 8. 最终验收记录

最终独立验收、真实模型请求、失败修复及正常库数据保留的详细证据见 [教学流程与 AI 辅导验收](09-teaching-acceptance.md)。
