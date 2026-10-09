# 审查记录：问题、修复与验证

本文档由 Harness 编写，Codex 完成最终事实校订；最终分工、证据与状态以 [07-final-acceptance.md](07-final-acceptance.md) 为准。

本文档记录第二轮审查发现的问题、实际修改与验证结果。
只记录真实执行过的内容；未执行或未通过的部分单独标注。

审查对象：`Tsar413/Vue-Practise-Class-Projects-DeepSeek`
审查起点 commit：`bccfda1b1af86311b10063ec3deb86caed4d2b35`（文件树 `88ef4ef5…`）
参考仓库：`Tsar413/Vue-Practise-Class-Projects`（只读，未修改）

## 1. 分工

| 角色 | 职责 |
| --- | --- |
| Codex | 环境准备与恢复、独立复核、集成回归、最终 Git 提交与上传 |
| DeepSeek Harness（本次） | 代码审查、缺陷修复、回归脚本编写、文档记录 |

DeepSeek Harness 本轮**没有执行提交或推送**；所有提交由 Codex 统一处理。

## 2. 问题清单、修复与验证

### 2.1 登录并发（同一账号首次并发登录）

| 项 | 内容 |
| --- | --- |
| 现象 | 同一账号首次并发登录时，多个请求都认为「没有登录记录」并各自插入，撞 `sys_login_token.user_id` 唯一键，返回 409；同时多个请求各自生成长期访问码，后写入的覆盖先写入的，先返回给前端的访问码随即失效 |
| 历史证据（Codex 基线） | 3 个虚构学生各 24 并发：HTTP 200/409 分别为 15/9、20/4、24/0；证据 `work/baseline-review/race-before.json` |
| 复现（本次） | 隔离库清空登录记录后 24 并发：先出现 24/24 成功但「响应访问码取值数=10、与库一致仅 15/24」；改用 `INSERT IGNORE + SELECT FOR UPDATE` 后出现 `DeadlockLoserDataAccessException`（24 并发中约 9 次失败） |
| 根因 | ①「先查后插」在并发下同时插入；②`INSERT IGNORE` 后再加锁，多事务在首次插入时互相等待触发死锁；③MySQL 默认 REPEATABLE READ 下普通 `SELECT` 是**快照读**，即使持有 `sys_user` 行锁也读不到本事务开始后才提交的 `api_access_code`，导致每个请求都认为「还没有访问码」而各自生成 |
| 修复 | ①登录时在事务内对 `sys_user` 该账号行加排他锁（`selectUserForUpdate`），使同一账号的登录串行化；②读登录记录改用**加锁读** `selectByUserIdForUpdate`，保证读到最新已提交值；③凭证写入用单条 `INSERT ... ON DUPLICATE KEY UPDATE`（`upsertToken`）；④加锁顺序统一为 `sys_user → sys_login_token`，删除账号路径同步加 `selectByIdForUpdate` 保持一致 |
| 涉及文件 | `mapper/SysLoginMapper.java`、`mapper/SysUserMapper.java`、`service/impl/SysLoginServiceImpl.java`、`service/impl/SysUserServiceImpl.java` |
| 验证（本次实测） | 3 名学生各 24 并发：**全部 24/24 HTTP 200**；库中记录数 1；**所有响应的长期访问码取值数 = 1，且 24/24 与数据库最终值一致**；并发响应中仅 1 个网页 token 仍生效（其余按原有轮换规则失效）；后端日志**新增死锁/未处理异常 0 处** |
| 验证（Codex 独立） | 3 名学生各 24 并发共 72/72 HTTP 200；成功响应长期码逐一等于最终 DB；仅最后一个网页 Token 生效 |
| 回归测试 | **本轮的登录并发单元测试已被移除**（原因见下）。当前后端仅保留原有的 `SecurityAndContentUtilsTests`（9 个纯逻辑用例：口令散列、凭证格式与散列、富文本清洗）。并发结论以隔离 MySQL 环境的实测与 Codex 的独立复核为准 |
| 保留不变 | 单会话 token 轮换规则保持原样：每次登录轮换网页 token，长期访问码不变 |

> 曾尝试使用 H2 并发测试，但没有形成可验证真实 MySQL 服务事务语义的有效证据；草案中的跳过结果未计入通过数。Harness 已移除该测试、配置和依赖。原 9 项单元测试不变，并发回归由真实隔离 MySQL 上的测试脚本承担。

### 2.2 启停脚本

| 问题 | 修复 | 验证 |
| --- | --- | --- |
| `stop-all.sh` 用宽泛 `pgrep` 匹配，可能误停其他项目 | 改为按状态文件记录的「PID + 进程启动时间 + 进程组」精确停止，停止前逐项复核 | 停止 18100 后端后端口释放；MySQL 仍在运行；DSH/Codex 进程未受影响 |
| 生命周期锁只在写状态时加，并发启动仍可双启 | 整个「检查 → 启动 → 写状态」在单把 `flock` 内完成；子进程以 `9>&-` 关闭继承的锁 FD（含 MySQL 的 nohup 子进程）；停止流程使用同一把锁 | 并发启动测试：第二个实例退出码 4 并提示「另一个 start-all.sh 正在执行」，无重复进程 |
| 身份判定过宽（祖先链「任意参数含项目根目录」） | 改为**严格命令类型 + 工作目录**：后端必须是 `spring-boot:run` 或本项目后端工程启动的 JVM；前端必须是本项目 `node_modules/.bin/vite` 或本项目 vite 脚本。默认只接管有匹配 PID/start/PGID 状态记录的服务 | 在项目 ROOT 用 cwd 启动一个返回完全相同 `/hello` 文本的 Python 服务（18104/18106）：**脚本拒绝并退出码 1**，假服务未被误杀；把假服务的 PID/启动时间/PGID 写进状态文件后同样被拒绝（命令类型不符） |
| 「已在运行」分支只凭 PID 跳过，未做健康检查 | 已有记录时同样执行完整就绪校验（HTTP 200 + 预期响应内容 + 监听者身份） | 脚本已无「仅凭 PID 即跳过」的路径 |
| 端口占用提示不区分「同项目其他 RUN_DIR」与「陌生服务」 | 无状态记录且端口有响应 → 明确失败并说明可能来源；有记录但不匹配 → 单独提示命令类型校验未通过 | 上述 18104/18106 场景分别命中两类提示 |
| PGID 与记录不一致时「按实际进程组处理」 | B 分支（记录进程仍在）与 C 分支（父进程已消失）在 PGID 不一致时**一律拒绝**并提示人工处理，不再接管；不再出现「只 kill 监听 PID」的接管行为 | Codex 实测伪造 PGID 返回非零且未停止进程，恢复记录后正常停止 |
| 缺超时/状态码/内容/端口归属/依赖/配置校验 | 补充：依赖与配置预检（JDK 21/Maven/MySQL 数据目录/node/前端依赖）、端口 1..65535 校验、启动超时（后端 150s、前端 90s、MySQL 60s）、HTTP 状态码 + **预期响应内容**校验、失败时回收本次启动的进程组 | `bash -n` 与函数交叉检查通过；实际覆盖配置失败、外部端口、重复/并发启动与超时回收；不宣称穷举所有故障 |
| `RUN_DIR` 把应用日志与既有 MySQL 数据目录绑死 | 拆分为 `MYSQL_DATA_DIR`/`DB_SOCKET`/`MYSQL_LOG_DIR` 与应用侧 `RUN_DIR`/`STATE_FILE`/`LOG_DIR`；状态文件默认落在项目内 `.runtime/run`，按项目隔离 | 隔离环境用 `RUN_DIR=.runtime/review-...` 复用既有 MySQL 成功启动 |
| `BACKEND_PORT` 未传给 Spring Boot | 启动时显式 `env SERVER_PORT="$BACKEND_PORT"` | 18100 就绪校验通过 |
| `FRONTEND_PORT` 未传给 Vite | 启动时以 `--port "$FRONTEND_PORT" --strictPort` 调用本项目 vite | Codex 实测 15173 隔离前端与正常 5173 均生效 |
| 数据库连接未做可用性校验 | 新增：`DB_URL` 未设置时由经校验的 `DB_NAME` 构造并 `export`；拒绝非本机 `DB_URL`（不打印 URL 内容）；解析库名/端口；自定义库的应用账号默认值按 `init-db.sh` 的派生规则（`<库名>_app` / `<库名>_local`）；用 `--database=<库名>` 连接并 `SELECT ... FROM sys_user LIMIT 0` 验证「连接 + 库级权限」 | 错误 `DB_URL`（库不存在）→ 退出码 1；错误口令 → 明确提示并退出；正确配置 → 打印「应用账号连接校验通过（库 …，15 张表）」 |

### 2.3 数据库初始化脚本

| 问题 | 修复 |
| --- | --- |
| `DB_NAME` 可覆盖，但两份 SQL 内固定库名 | 建库/建表/授权/种子四处统一：执行前用 `sed` 把 SQL 中的默认库名改写为本次库名 |
| 默认口令与后端不一致 | 默认库默认口令改回 `vue_practice_local`，与 `application.yml` 的 `${DB_PASSWORD:vue_practice_local}` 一致 |
| `SHOW GRANTS` 的 `GRANT USAGE ON *.*` 会被误判为全局高权限 | 校验前先剔除 `GRANT USAGE ON *.*` 占位行，再判断是否只有本库权限 |
| `grep` 按行匹配检测不出换行 | 口令校验改用 `case` 模式匹配：单引号/双引号/反斜杠/反引号、换行/回车/制表符、控制字符分别判断 |
| 已存在账号只补 `GRANT` 后仍宣称「仅授权该库」，可能扩权 | 库已存在或账号已存在时**直接拒绝退出**，不做任何写入；**移除 RESET/drop 入口**，本脚本只负责首次新建 |
| `DB_HOST` 未校验即拼进 SQL | 只允许 `127.0.0.1` / `localhost` / `::1` |
| 自定义库可能复用默认账号 | 自定义库强制使用由库名派生的独立账号；显式传入默认账号名时拒绝 |

验证（实测，均未产生写入）：已有默认库 → 退出码 5；`DB_HOST=10.0.0.5` → 退出码 2；
口令含单引号 / 反斜杠 / 换行 → 退出码 2；自定义库复用默认账号 → 退出码 2；非法库名 → 退出码 2。
调用后复核 `vue_practise_backend` 仍为 15 张表、4 个账号，未被改动。
Codex 独立实测：新库 `vp_review_init_20261009` 首次初始化成功（15 表 4 账号）。

> 如实说明：验证过程中一次带合法参数的调用实际创建了测试库 `demo_v1`，
> 已当场删除该库与其测试账号 `demo_v1_app`；受保护库与审查库未受影响。

### 2.4 隔离审查环境与回归脚本

| 问题 | 修复 |
| --- | --- |
| `review-verify.sh` 的 `mysql_q` 强制只读事务，却用于 `DELETE`，导致 A0「首次场景」未建立 | 拆分为 `mysql_ro`（显式只读事务）与 `mysql_rw`（仅用于隔离库写操作） |
| 断言直接传 `C1`/`C2` 等变量，失败时会打印访问码 | 新增 `check_secret_bool`：只比较布尔结果，**绝不打印凭证值**；并发统计改在 Python 内完成，只输出统计量 |
| 临时文件与 curl 响应落在共享 `/tmp` | 改为 `umask 077` + `mktemp -d` 私有目录 + `trap` 退出清理；`api-verify.sh` 同步改造（原 `/tmp/av.body`） |
| `SKIP_WRITES` 命名与实际行为不符（仍会登录） | 更名为 `READ_ONLY=1`，语义为**不登录、不写任何数据**；E0 通过后立即输出结论并退出 |
| 环境身份只靠变量名含 review | 改为只读核对**监听进程自身**的 `/proc/<pid>/environ`：`DB_URL` 指向隔离库、`REPAIR_FILES_ROOT` 等于隔离图片目录、`SERVER_PORT` 为 18100；并核对可执行文件、工作目录与记录进程组；不输出凭据。`api-verify.sh` 加入同一只读核验，核验不通过直接退出（不发出任何请求） |
| E0 失败只累加 FAIL 不中止，后面仍会写数据 | 任何 E0 失败（`ENV_OK!=1` 或 `FAIL>0`）在**第一条写操作之前**退出；删除无证明价值的哨兵写入块 |
| 越权尝试被记在「只读」段落 | B 段中「学生重置他人项目」标注为越权尝试（应被拒绝、不产生写入） |
| `api-verify.sh` 会覆盖旧验证报告 | 报告路径可用 `OUT` 指定（建议项目外路径）；覆盖前自动保留 `.prev` 历史副本；对 8100 直接拒绝执行（该套件含重置与删除） |

验证：`bash -n` 全部通过；函数定义与调用交叉检查无缺失；
`READ_ONLY=1` 实测只执行 E0（最终脚本 6 项身份/基线检查，写测试前执行）后退出，未登录、未写入；
对 8100 执行 api-verify 退出码 3 并给出拒绝原因。

### 2.5 注释与文档准确性

| 问题 | 修复 |
| --- | --- |
| 注释中出现「绝不会死锁」等未经证明的绝对化表述 | 改为如实描述：说明加锁顺序一致是**降低**交叉等待风险，并列出两个必须保持的必要条件（加锁读、锁顺序） |
| 文档称「不含任何线上凭据」，容易被读成「仓库无口令」 | 订正为：仓库**确实包含公开的本地演示口令**（演示账号口令与 `application.yml` 的本地默认值），但**不含**任何线上凭据、API Key、Token 或私钥 |
| 文件数量表述 | 订正：`231` 是 Git 目录项数量，**受版本控制的文件为 178 个** |
| Harness 版本标记为「未确认」 | 已核验为 `0.2.0-rc.2`；模型 `deepseek-flash`（Max 配置） |

## 3. 本轮实际修改的文件

后端（4 个源文件；本轮未新增测试文件，`backend/pom.xml` 最终与审查前逐字节一致）：

| 文件 | 修改 |
| --- | --- |
| `backend/src/main/java/.../mapper/SysLoginMapper.java` | 重写为「账号行加锁 + 加锁读 + 单条 upsert」 |
| `backend/src/main/java/.../mapper/SysUserMapper.java` | 增加 `selectByIdForUpdate`（统一加锁顺序） |
| `backend/src/main/java/.../service/impl/SysLoginServiceImpl.java` | 登录凭证写入改为串行化 + 加锁读 + 回读最终值 |
| `backend/src/main/java/.../service/impl/SysUserServiceImpl.java` | 删除账号前先锁 `sys_user` 行 |
| ~~`SysLoginMapperConcurrencyTests.java`~~ | 曾经新增，因无法验证真实服务且引入环境改动，已移除 |
| ~~`application-test.yml`~~ | 曾经新增，已随之移除 |

脚本：

| 文件 | 修改 |
| --- | --- |
| `scripts/start-all.sh` | 生命周期单锁 + FD 隔离、严格身份判定、完整就绪校验、DB 连接预检、端口范围、超时与失败回收、目录与端口拆分 |
| `scripts/stop-all.sh` | 精确停止、PGID 校验、不一致即拒绝、孤儿监听者处理、与启动共用锁 |
| `scripts/init-db.sh` | 只做首次新建、库/账号已存在即拒绝、移除 RESET、参数与口令校验、授权校验修正 |
| `scripts/review-env.sh` | 端口/图片目录护栏、`env` 不再输出口令、显式绑定回环地址 |
| `scripts/review-verify.sh` | 重写：E0 只读身份核验、失败即停、只读早退、私有临时目录、凭证不落输出、越权尝试标注 |
| `scripts/api-verify.sh` | 私有临时目录、只读隔离核验、报告 `OUT` 覆盖与 `.prev` 历史保留、对 8100 拒绝执行 |

文档：新增本文件，订正 `docs/02-comparison.md`、`docs/03-reuse-and-reimplementation.md`、
`docs/04-development-log.md`、`docs/05-environment-and-runbook.md`、`README.md` 中的不准确表述。

## 4. 验证执行情况

| 验证 | 执行方 | 结果 |
| --- | --- | --- |
| 登录并发（3 学生 × 24 并发，逐个响应与 DB 比对） | 本次 | 72/72 HTTP 200；访问码全部一致；仅 1 个 token 生效；0 死锁 |
| 登录并发（独立复核） | Codex | 72/72 HTTP 200；与 DB 逐一一致 |
| 后端单元测试 | 本次 / Codex | **9 项通过**（`SecurityAndContentUtilsTests`）。本轮新增的并发单元测试已移除，未计入通过项 |
| 前端单元测试与构建 | Codex | 7/7 通过；Vite 构建通过 |
| 图片事务与磁盘清理（触发器注入回滚、33 秒清理周期、跨学生保留） | Codex | 11 项全过 |
| 启停脚本（外部同响应服务、伪造 state、并发/重复启动、停止后端口释放） | Codex | 全部通过 |
| 新库首次初始化 | Codex | `vp_review_init_20261009` 15 表 4 账号 |
| 历史 246 断言接口套件（隔离环境串行重跑） | Codex | 246/246 通过（首轮 217/246 的失败由并发执行造成的 Token 轮换干扰引起，已保留记录） |
| 原库完整性 | Codex | 正常浏览器登录前 15 表校验和一致；登录后仅两张认证相关表变化，仍为 120 行；其他 13 表及 11 张原图片哈希一致 |

## 5. 最终补充

Codex 已完成正常 8100/5173 启动、重复启动、停止后 JVM 子进程及端口释放、无关进程保留与恢复；最终仓库 API 脚本 246/246、专项脚本 61/61 均通过。PGID 不一致拒绝路径已实测。正常浏览器完成师生登录、角色导航、管理列表、两个项目查询及接口文档实际请求。

仍未验证：宿主机真实访问、高负载压力、原 56 项浏览器写操作套件的本轮重跑、断电恢复与 MySQL 故障转移。五分钟重置冷却仍是前端行为，不是后端限流。

Codex 直接补正了最终进程身份校验、隔离写测试护栏、私有临时目录初始化、图片前后哈希断言和初始化说明；并新增 `scripts/verify-review-target.py`。这些不归为 Harness 独立完成，具体文件分工见最终验收。
