# 教学与 AI 边界契约测试

由 Codex 独立设计和编写。`test_boundaries.py` 使用真实 MySQL 与本地 HTTP 协议桩；
桩的每条回答都标记为测试，不读取真实辅导配置，不调用任何真实模型。
测试不安装依赖、不建库、不重导种子、不改迁移、不清空表。每次生成新的虚构班级和学生，
保留独立测试库的数据供诊断。请勿与其他使用同一隔离库的测试套件同时运行。

## 前提与护栏

本机复用 `vue_teaching_review_20261009`，必须已应用项目增量迁移。
独立后端 18100，前端 15173，桩 18091；图片和运行状态在 `.runtime/teaching-review-20261009/`。
MySQL 客户端沿用 `MYSQL_HOME` 或 `~/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64`。
该虚拟机的本地 root socket 用于测试库的触发器、行锁和只读证据查询；不是平台登录口令。

每次 HTTP 写入和 SQL 故障注入前验证：Java PID/cwd、独立端口、JDBC 库名、独立应用用户、
实际 MySQL 会话数据库、两种图片根目录没有符号链接绕向正常目录、off/protocol 模式下 AI 仅指向回环桩且密钥
只允许空或固定虚构值（显式 real 扩展见文末）。任一项不符即停止。不能通过环境变量把本套件指向正常库或 8100。
SQL 操作拒绝 `USE`、`TRUNCATE`、`DROP DATABASE`；故障触发器仅作用于本用例新建对象，
在 `finally` 中删除。中断后先检查测试库的 `boundary_*` 触发器，不要盲目继续测试。

## 可复制命令

先运行现有相关测试：

```bash
cd /home/tsar413/projects/Vue-Practise-Class-Projects-DeepSeek
(cd backend && JAVA_HOME="$HOME/tools/opt/jdk-21.0.12.1+1" \
  "$HOME/tools/opt/apache-maven-3.9.16/bin/mvn" -o test)
(cd frontend && npm test)
```

本虚拟机内存较小，避免同时启动两个后端。以下包装在结束或出错时恢复原正常服务：

```bash
cd /home/tsar413/projects/Vue-Practise-Class-Projects-DeepSeek
bash .runtime/run-local.sh stop
(
  trap 'bash .runtime/run-local.sh start' EXIT
  python3 tests/integration/run.py --mode all
)
```

运行器依次执行未配置模式和协议桩模式，最终停止隔离服务及桩；不删除测试数据。
详细失败输出到终端，场景状态 JSON 进入忽略的测试运行目录。禁止将原始日志/测试数据推送。
公共报告见 `docs/10-boundary-test-report.md`。

有意保留隔离环境进行浏览器验收时：

```bash
python3 tests/integration/environment.py stub-start
python3 tests/integration/environment.py start --mode protocol
python3 tests/integration/environment.py guard --mode protocol
python3 -m unittest discover -s tests/integration -p test_boundaries.py -v
# 浏览器：http://127.0.0.1:15173 （只使用测试账号）
python3 tests/integration/browser_fixture.py
# 完成后：
python3 tests/integration/environment.py stop
python3 tests/integration/environment.py stub-stop
bash .runtime/run-local.sh start
```

## 场景与同步机制

- A01–A03：学生教师接口权限、班级/草稿/关闭可见性、严格日期与字段边界、截止规则、已有成果的编辑保护。
- B01–B05：草稿不建版本且清空生效、四路不同键版本不丢失、四路同键幂等、保存草稿/提交竞争、历史反馈和身份、关闭时锁内重新校验。
- C01–C05：损坏/非图/大小/像素拒绝、异常文件名、真实落盘后写库失败的补偿、删除排队事务回滚、物理清理且他人文件不变、越界/绝对路径/符号链接拒绝、业务重置保留教学与辅导。
- D01–D10：角色/会话ID越权、输入与实际引用、同键并发、个人/全局并发及恢复、每日第6/7次与删除防绕过、模型在途删除和最终插入前删除竞态、9类上游异常、虚构凭据脱敏、回答插入失败恢复。
- 单独 off 用例：缺配置明确提示、保存真实提问但没有假助手消息、桩调用数不变。

并发提交使用 `threading.Barrier`，关键串行边界使用真实 InnoDB 行锁和
`performance_schema.data_lock_waits`；插入窗口用具名 MySQL 锁及触发器控制。
上游在途用条件变量和事件确认请求已到达。轮询只等待明确状态，固定超时为失败上限，
不以随意 sleep 推测竞争是否发生。文件清理由实际磁盘消失和其他文件哈希判定。

AI 删除竞态允许合法的两种先后顺序：删除先完成则禁止新消息；若回答事务已持会话锁，
删除可等待其提交，但删除返回之后也不能再写消息。所有判定均检查真实数据库状态。
协议配置每日上限临时设为 6、个人并发 1、全局 2、等待 150ms、总超时 3s、间隔 0，
便于确定性边界验证；这些设置只作用于隔离后端，不修改正常配置或业务默认值。

## 浏览器补充验收

使用本机浏览器自动化在隔离 15173 执行（API 套件结束后，避免登录 Token 相互轮换）：
教师新建发布 → 学生保存草稿并上传 `scripts/fixtures/test-image.png` → 正式提交 →
教师退回 → 学生修改重交 → 教师评分 → 学生查看最新分数与旧反馈。
在草稿和评分后刷新、退出重新登录，核验字段、图片与历史不变。
用不存在的任务地址验证加载错误提示，用协议桩 auth/html 模式验证错误后不登出和 HTML 清洗；
再检查项目切换、新会话/旧会话恢复、390px 页面和未处理脚本异常。
浏览器检查单及实际证据单列于公共报告，不把 API 断言冒充浏览器验证。

任务网络超时检查可在已打开的隔离辅导页前运行 `python3 tests/integration/pause_backend.py`，立即刷新页面。该脚本只暂停核验后的隔离 Java，45 秒自动恢复；前端 30 秒超时后应出现重试，恢复后点击重试。不要用于正常后端。

## 显式真实 DeepSeek 验收（付费、默认不执行）

本段与前面的协议桩证据分开。`run.py --mode all` 仍只运行 off/protocol，
不会读取真实密钥。只有 `environment.py start --mode real` 和
`real_acceptance.py ... --mode real` 明确选择真实模式。

真实模式继续核验同一隔离库、独立 MySQL 用户及实际会话、18100 端口、Java 身份、
独立运行/两种图片目录。配置只读取 owner-only 的 `.runtime/ai-tutor.env`，不执行
shell、不读取 Harness/Codex 配置；端点必须精确为 `https://api.deepseek.com` 或
`https://api.deepseek.com/v1`，不允许重定向、任意路径/端口、HTTP 或相似域名。
运行进程的密钥/模型/端点必须与该独立文件一致。默认/协议桩护栏遇到真实后端仍拒绝。
隔离服务固定输出 `max_tokens=600`、读取超时 60 秒、上下文上限 24000 字符。
正常环境配置和限额不变。隔离前端不会继承密钥。

每个阶段单独执行并检查结果，绝不循环重试。预算持久化在忽略的
`.runtime/teaching-review-20261009/real-acceptance-budget.json`：
最多 5 个平台尝试（幂等重放也保守占一个槽），发送前占位；失败也计入，存在未完成槽
则禁止其他尝试。该记录不是供应商账单计数。浏览器必须先占位，再只点一次发送。
脚本不提供预算重置；已有运行不能被 `prepare` 覆盖。本次已完成，**不要为重复运行
删除账本**；新一轮付费运行必须先取得新的预算授权，再归档旧证据并准备新一轮。

不付费的安全单元测试：

```bash
python3 -m unittest discover -s tests/integration -p test_real_policy.py -v
```

新一轮已授权验收的操作顺序（现有账本会阻止本轮再次运行）：

```bash
cd /home/tsar413/projects/Vue-Practise-Class-Projects-DeepSeek
bash .runtime/run-local.sh stop
# 操作期间设置退出清理，异常时也恢复正常服务：
trap 'python3 tests/integration/environment.py stop; bash .runtime/run-local.sh start' EXIT
python3 tests/integration/environment.py start --mode real
python3 tests/integration/real_acceptance.py prepare --mode real
python3 tests/integration/real_acceptance.py ticket --mode real
# 阅读私有 state 文件内的回答，按文档人工核对；失败先分析，不重试。
python3 tests/integration/real_acceptance.py followup --mode real
python3 tests/integration/real_acceptance.py repair --mode real
python3 tests/integration/real_acceptance.py replay --mode real
python3 tests/integration/real_acceptance.py isolation --mode real
python3 tests/integration/real_acceptance.py browser-reserve --mode real
# 使用输出的虚构学生账号（测试初始密码 123456）登录 http://127.0.0.1:15173。
# 打开输出的任务详情 → AI 辅导 → 输入输出的问题 → 仅点击一次发送。
# 核对回答、来源、禁用中的按钮、刷新恢复、项目切换和浏览器控制台。
python3 tests/integration/real_acceptance.py browser-collect --mode real
python3 tests/integration/real_acceptance.py verify --mode real
python3 tests/integration/environment.py stop
bash .runtime/run-local.sh start
trap - EXIT
```

`browser-collect` 读取隔离 Tomcat 访问日志，记录真实平台 HTTP 状态和服务器处理耗时；
日志仅记录方法、路径、状态和耗时，不含请求头、查询参数或正文。Tomcat 11 的 `%D`
单位为微秒。HTTP 脚本使用客户端单调时钟，两种耗时口径分开说明。
平台当前不向前端返回供应商 `usage`/`finish_reason`；不能用字符数或 600-token 上限
冒充实际 token 用量，也不能断言供应商未提供 usage。
真实验收报告见 `docs/11-real-deepseek-acceptance.md`。
