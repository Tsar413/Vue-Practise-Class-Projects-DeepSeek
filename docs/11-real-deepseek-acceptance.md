# 真实 DeepSeek 平台调用验收

日期：2026-10-09。执行人：Codex。验收分支 `main`，业务代码基线
`e39fa1dcac4cc304eeb084a20d4be44315abb91a`（包含 AI 会话行锁与两个短事务修复）。
开工时工作区干净，`HEAD...origin/main` 为 `0/0`。本轮只新增/修改测试和本文档，
业务代码、数据库迁移、正常限额未改。测试设计、脚本、安全护栏扩展、运行与人工复核
均由 Codex 完成；本轮没有调用 Harness 修改代码。此前 Harness 的真实开发来源保留。

## 结论

**事务修复后的真实调用链路通过**：3 次串行 API 提问和 1 次浏览器提问均经平台
`POST /api/ai-tutor/ask` 返回 HTTP 200，产生非空真实模型回答；每轮恰有一条 USER
和一条 ASSISTANT，计数与正文一致。另一次同键重放返回原消息 ID 和原正文，无重复入库。
4 个新回答共 8 条消息，分布于 3 个会话。没有直接请求供应商接口来替代平台验收。

**回答质量仅部分通过**：核心接口问题能够定位，多轮纠正有效，未观察到虚构接口路径
或声称已经执行测试；但出现首答字段位置误述、验证步骤遗漏参数，以及一个响应对象
层级表述错误。具体问题保留在下表，不因 HTTP 成功或来源列表正确而认定回答完全正确。
本轮未改业务规则或提示词掩盖这些问题，也未为追求全绿增加付费调用。

## 隔离、端点与预算

- 正常库 `vue_practise_backend` 保留。写操作仅针对既有测试库
  `vue_teaching_review_20261009`，连接用户为该库专用 `_app` 用户。
- 隔离端口：后端 18100、前端 15173；运行与图片在
  `.runtime/teaching-review-20261009/{run,repair-files,teaching-files}`。
  写入前核对 Java PID/工作目录、JDBC URL、实际 MySQL 连接数据库、文件目录和符号链接。
- 密钥只来自用户独立配置 `.runtime/ai-tutor.env`（0600），未读取其他应用凭据。
  实际端点 `https://api.deepseek.com/chat/completions`，模型 `deepseek-flash`，
  `thinking` 关闭、每次 `max_tokens=600`、读取超时 60 秒、上下文上限 24000 字符。
- 显式 real 白名单只接受配置中的 DeepSeek HTTPS 基础地址（根地址或 `/v1`），
  运行值须匹配私有配置；原默认/off/protocol 护栏仍拒绝真实后端，已现场验证。
- 发送前占用持久化预算槽，幂等重放也保守计一次。**5 个平台尝试全部完成，停止付费调用**。
  其中 4 次产生新模型回答；重放走已有结果分支，无新 USER/ASSISTANT。
  未查询供应商账单或取得供应商侧调用审计；不将本地账本描述为供应商计费记录。
- 只用新建的虚构班级、学生、任务、局部代码和公开接口文档。没有向真实 API 发送
  凭据哨兵、真实学生信息或正常业务数据。请求中 `{accessCode}` 只是公开路径占位符。

## 逐次证据与人工内容核对

前 3 项与重放的耗时为平台 HTTP 客户端端到端时间，浏览器项为隔离 Tomcat 服务器处理
时间，二者不作性能比较。字符数只检查回答非空，不代表 token 数。
所有调用的实测 token 用量均为 **未取得：平台未暴露供应商 usage**；不能据此断言供应商
响应本身未提供 usage。未对单个响应修改代码截取供应商正文或认证头。

| 场景 | 预期 | 实际 HTTP / 耗时 / 回答字符 | 入库及来源 | 内容判断 |
| --- | --- | --- | --- | --- |
| 抢票错误代码 | 找出 GET/POST、query/body 和 workspace 错误，引用报名接口 | 200 / 2.532 s / 535 | 会话 77；USER 194、ASSISTANT 195；含任务 75 与实际报名接口 | 部分通过：核心三点正确；把原代码 `params.workspaceId` 误述为 `data.workspaceId`，且首答未明确 Axios POST 第三个参数位置 |
| 同会话追问 | 承接已改 POST、仍将 userId 放 body 的问题 | 200 / 1.548 s / 442 | 同会话 77；USER 196、ASSISTANT 197；累计 4 条 | 通过：明确 query 参数，给出 `axios.post(url, null, { params: { userId: 102 } })`；保留空间由服务端解析的关键上下文 |
| 报修上传与工单 | 找出 JSON 文件名和 imageUrls 错误，不混入抢票接口 | 200 / 1.878 s / 610 | 会话 78；USER 198、ASSISTANT 199；任务 76、报修接口 | 核心通过、完整性不足：正确说明 FormData、File、REPORTER/type=1、data.id→imageIds；最后的图片查询建议未提醒必填 query `operatorId` |
| 同键重放首答 | 返回同一结果，不增加消息或上游调用 | 200 / 0.068 s / 原 535 | 仍为 ASSISTANT 195；会话 77 保持 4 条 | 通过：消息 ID、正文、引用完全一致；代码复核命中缓存分支不进入模型发送，协议桩另验证调用数 |
| 浏览器从任务详情进入 | 实际输入、提交、展示回答/来源，正确说明附件归属 | 200 / 3.180543 s / 556 | 会话 79；USER 200、ASSISTANT 201；任务 76，2 条消息 | 核心通过、表述瑕疵：imageIds 与本人图片规则正确；开头 `RepairImageVO.data.id` 层级不准确，应为响应包裹体的 `data.id`（或 Axios 的 `response.data.data.id`）；末尾图片查询同样省略 `operatorId` |

人工核对使用仓库实际结构化文档和控制器，不用模型回答反推预期：

- `TicketController_bookTicket`：`POST /api/practice/{accessCode}/ticket/activities/{activityId}/records`，
  `userId` 是必填 query，原始请求没有 body。GET 同路径对应管理员名单查询，确实存在。
- `RepairController_uploadImage`：`POST .../repair/images`，multipart 中为
  `operatorId`、`imageType`、`file`；REPORTER 上传类型 1。返回 `Result<RepairImageVO>`，
  `RepairImageVO` 自身包含 `id`，没有 `data` 字段。
- `RepairController_createOrder`：`POST .../repair/orders`，绑定字段 `imageIds`；必须本人、
  当前 workspace、未绑定、类型 1，至多 6 张。用户问题仅要求图片字段的局部建议，未把回答
  当作可直接运行的完整创建工单请求。
- `RepairController_getOrderImages`：`GET .../repair/orders/{orderId}/images` 确实存在且送入上下文，
  必填 query `operatorId`；模型提到正确路径但未给出该参数，记录为验证建议不完整。

每个 API 引用的 operationId 都在该轮保存的 USER 上下文中；TASK ID/标题与实际任务一致，
DOC 文件确实存在。抢票上下文没有 RepairController，报修上下文没有 TicketController。
后端代码在保存该上下文后以相同 bundle 构建供应商请求；本轮未截取 TLS 请求明文，
上述是平台入库证据与发送代码复核，不冒充供应商侧抓包。

## 浏览器及隔离补充

- 虚构学生从任务 76 的详情点击“打开 AI 实训辅导”，页面自动选中 REPAIR 和任务 76。
  填写真实问题、仅点一次发送；在途按钮、项目/任务切换与会话操作被禁用。
- 实际显示四步回答、API 路径、文档来源和对应任务；刷新后可以从会话列表恢复回答。
- 切换到 TICKET 后清空旧任务及当前回答，恢复已有抢票会话后显示两组消息与正确 Axios
  局部修改，未混入报修回答。最后关闭隔离浏览器页，防止误发新调用。
- 本轮浏览器捕获的 error/warn 日志为空。没有用截图或 API 断言冒充点击过程。
- 另一个新建虚构学生读取两个任务及两个会话消息均为 404，会话列表不含他人记录。
  本轮越权继续提问/删除没有再用真实 API 测试，原协议桩覆盖该场景。
- 对私有辅导密钥作内存比对：不在隔离前端进程环境、检查到的构建产物与日志、合成消息
  中出现。只输出检查结果，未输出密钥、平台 Token 或实际访问码。未声称完成所有可能
  侧信道的安全审计。

## 测试代码、协议桩与限制

新增 `real_policy.py`：精确 HTTPS 白名单、只解析独立私有配置、持久化串行五次预算。
扩展 `environment.py`/`support.py`：显式模式继续执行所有隔离检查，默认仍为假端点。
新增 `real_acceptance.py`：逐次平台调用、合成夹具、幂等与消息/来源校验、浏览器预算及
HTTP 访问证据收集。新增 `test_real_policy.py`：完全离线的白名单、预算、防误记录测试。
复制命令与人工浏览器步骤见 `tests/integration/README.md`。

真实调用与协议桩证据分开：

- 本文以上 4 个新回答来自真实 DeepSeek，另 1 次是平台幂等重放。
- 离线安全单元测试：3 个通过，包含错误协议、伪装域名、用户信息域名绕过、额外端口/路径、
  第 6 次拒绝、并发/未完成拒绝、失败也计数和敏感指标字段拒绝。
- 协议桩回归结果在完成后记录于下节；其假响应有明确测试标记，不是实际模型回答。
  401、429、500、超时、断连、空/非 JSON/超大响应及并发/删除竞态仍由本地桩验证，
  本轮未故意制造真实服务错误、限流或付费压力。
- 实际供应商 token 数、费用、finish_reason、供应商侧缓存/计费审计：未取得。
  模型回答质量问题如上保留；4 个样本不能证明模型对所有代码都正确。
- 本轮没有业务源文件改动，因此没有再次完整验收旧业务或重新运行前端构建；上一轮
  34 个后端单元测试与 7 个前端测试证据保留于文档 10，不算作本轮重跑结果。

## 回归与正常环境恢复

本轮 `python3 tests/integration/run.py --mode all` 实际结果：off 1/1 通过（0.871 s），
协议桩 23/23 通过（50.367 s）。其中 D03 同键并发只请求上游一次、D06 在途删除、
D07 回答插入前删除竞争、D10 回答插入失败后的事务恢复均通过；没有跳过、放宽断言或
修改业务源文件。测试结束由运行器停止隔离 18100/15173 和协议桩 18091。
私有结果为 `boundary-results-20261009-153949.json`，原始日志、回答和夹具均留在忽略的
`.runtime/teaching-review-20261009/`，不上传。

正常库 29 张表逐一比较行数及 `CHECKSUM TABLE`：全部一致，总计 125 条记录；
正常 `repair-files` 和 `teaching-files` 文件清单与 SHA-256 全部一致，共 12 个文件。
正常库迁移、会话和教师验收数据未修改，没有重导种子或执行重置。
独立辅导配置与本机配置文件恢复前后哈希一致；没有变更正常限额。
备份（0600、目录 0700）位于项目目录之外的虚拟机私有路径：
`/home/tsar413/Documents/Codex/2026-10-08/linux-deepseek-harness-1-linux-node/work/real-acceptance-20261009/`。
备份不推送，包含正常库 dump、逐表基线、图片归档和哈希证据。

正常服务按原 `.runtime/run-local.sh` 恢复。MySQL 3306、Harness 3080、正常后端 8100、
正常前端 5173 保持运行；当前虚拟机 IP 为 `192.168.247.128` 与 `192.168.17.128`。
虚拟机本机访问 `http://127.0.0.1:5173`；能到达对应网卡的宿主机可访问
`http://192.168.247.128:5173` 或 `http://192.168.17.128:5173`。没有操作宿主机环境。

```bash
cd /home/tsar413/projects/Vue-Practise-Class-Projects-DeepSeek
bash .runtime/run-local.sh start
# 停止本项目正常前后端，默认保留 MySQL：
bash .runtime/run-local.sh stop
```

提交仅包含测试代码、README 与本报告，使用正常提交/推送；不重写历史、不强制推送，
不操作原参考仓库。公开前检查暂存文件无实际密钥、本机配置、依赖、测试数据或日志。
