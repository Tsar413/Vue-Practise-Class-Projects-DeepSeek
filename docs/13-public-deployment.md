# 公网部署与验收记录（2026-10-09）

## 版本与执行来源

- 作品入口：http://106.14.114.66/ 。本轮没有域名或 HTTPS 证书。
- 实际应用代码：`main` / `52aed11da9d9b76247aea3cda6c75093bb2ff0da`。构建前工作区干净，fetch 后与 origin/main 无差异。
- 发布目录：`/opt/vue-practice/releases/52aed11da9d9b76247aea3cda6c75093bb2ff0da`，`current` 指向该目录；`release.json` 记录 commit、产物 SHA-256 和逐文件摘要。
- 本文及部署工具是在部署后补充的资料，不改变上述应用代码版本。
- 本轮部署、配置、验收脚本及报告由 Codex 直接完成；没有让 Harness 修改业务代码，也没有在服务器安装 Harness/Codex。保留原有开发来源记录。
- 所有本地操作在 Linux 虚拟机进行，远程操作只通过已配置的 `vue-practice-server` SSH 连接；未操作宿主机或原参考仓库。

## 环境、网络与配置

检查时服务器只有 SSH 等系统服务，没有已有站点或数据库。Ubuntu 24.04.2、2 核、约 1.6GiB 内存、35GiB 初始可用空间。使用 Ubuntu 软件源安装 Java 21.0.12.1、MySQL 8.0.46、Nginx 1.24.0。为小内存服务器新增独立 2GiB swap 文件，原 fstab 已备份；没有改动 SSH 或防火墙规则。

| 对象 | 实际位置/配置 |
| --- | --- |
| 应用服务 | `vue-practice.service`，用户 `vue-practice`，无登录 shell |
| 后端 | `current/backend/app.jar`，打包运行，JAR 不含 DevTools，127.0.0.1:8100 |
| 前端 | `current/frontend`，Vite 生产产物，API 为 `http://106.14.114.66` |
| 数据库 | `vue_practice_public`，物理数据 `/var/lib/mysql`，127.0.0.1:3306 |
| 数据库应用账号 | `vue_public_app`@127.0.0.1，仅 SELECT/INSERT/UPDATE/DELETE |
| 报修图片 | `/opt/vue-practice/data/repair-files` |
| 教学截图 | `/opt/vue-practice/data/teaching-files` |
| 后端配置 | `/etc/vue-practice/application.env` |
| 独立辅导凭据 | `/etc/vue-practice/ai-tutor.env` |
| 演示口令领取 | `/etc/vue-practice/demo-accounts.json`；仅通过已授权 SSH 或本机私密副本领取 |
| Nginx 独立站点 | `/etc/nginx/sites-available/vue-practice`；保留默认站点 |
| MySQL 独立配置 | `/etc/mysql/mysql.conf.d/vue-practice.cnf` |
| 应用日志 | `/var/log/vue-practice/application.log`，每文件 10MB，14 天，总上限 150MB |
| Nginx 日志 | `/var/log/nginx/vue-practice.*.log`，由现有 Nginx logrotate 规则轮转 |
| 配置及验收备份 | `/opt/vue-practice/backups/`，仅 root 可读 |

配置目录权限 0700；配置、辅导凭据及演示口令文件为 root:root 0600，由 systemd 读取。JVM 堆上限 384MiB、元空间上限 192MiB；MySQL buffer pool 128MiB、连接上限 40；应用连接池上限 8。systemd 开机启动、失败重启，限制可写目录并使用 0027 umask。

Nginx 保留 `/api/**`、`/login`、`/logout`、`/hello` 路径和参数，Vue history 路由回退至 index.html。请求体上限 6MiB，对应应用单文件 5MiB、请求体 6MiB。后端 AI 超时 60 秒、前端 90 秒、代理 95 秒。响应头包含 nosniff、DENY、same-origin Referrer-Policy。专属访问日志只记时间、方法、状态、大小、耗时，不记录含访问码的 URI、查询参数或认证头。

公网从虚拟机实测 80 可连接；3306、8100、5173、3080 均不可连接。服务器监听表同时证实数据库及后端只绑定回环地址；没有远程 Vite 或 Harness。未要求额外放行安全组，也未更改现有 SSH 连通性。

## 数据准备与保护

初始化顺序：`db/01-schema.sql` → `db/02-seed-demo-data.sql`（通过拒绝覆盖已有库的 init-db.sh 改写为独立库名）→ `scripts/migrate.py --database vue_practice_public` / `db/03-teaching-ai-tutor.sql`。迁移版本 `2026-10-09-v3`，校验摘要 `1d733a7d02e05d9d7c189cb5a7409541cb621e56e851c4d328d1a2d2d209adbf`；13 张教学/AI 表与约束校验通过，总计 29 张表，应用以 Hibernate validate 启动成功。

只导入仓库虚构 seed 和中性 Mock 数据。演示账号在应用启动前全部替换为独立随机口令，未沿用公开开发口令。账号：教师 `DEMO_TEACHER`；体验学生 `DEMO2026001`、`DEMO2026002`；重置验收专用 `DEMO2026003`。三个学生均初始化抢票与报修项目。数据库正式口令也随机生成，不在仓库保存。

预置“公网演示：报修接口与成果提交”任务。学生 001 的两版成果、退回反馈、88 分评价均明确注明“部署验收虚构数据/非真实课堂成绩”。截图是程序生成的合成像素图。没有导入虚拟机正常库、旧图片、真实学生资料、成果或历史 AI 会话。本轮未对虚拟机正常库执行写操作，也未改变其辅导额度。结束前确认虚拟机原 MySQL 3306、Harness 3080、前端 5173、后端 8100 的原有进程继续运行；没有启动本地隔离测试服务。

重启前已保存 `/opt/vue-practice/backups/acceptance-before-restart/{database.sql,images.tar.gz,integrity.json}`。停止应用后取基线，MySQL 重启前后 29 张表 CHECKSUM TABLE 一致，两个图片文件 SHA-256 一致。随后启动应用、重启 Nginx，经公网再次验证成果、反馈、工单、图片和真实 AI 会话均保留。

## 真实 AI 配置与结果

仅从用户明确配置的独立 `.runtime/ai-tutor.env` 读取辅导配置，通过 SSH 加密通道传递，未读取 Harness/Codex 凭据。端点 `https://api.deepseek.com/chat/completions`，模型 `deepseek-flash`。每学生每日 10 次、最小请求间隔 15 秒、每学生并发 1、全局并发 2；最大输出 600 tokens、上下文 12000 字符，thinking=false。

本轮真实请求 **1 次**，上限 2 次的持久预算仍保留；不自动重试。入口为公网平台 `POST /api/ai-tutor/ask`，使用虚构报修任务与学生，未直接绕过平台请求供应商。返回 HTTP 200，耗时 **3.978 秒**，回答 **691 字符**；平台响应未提供 usage，因此 token 用量记为“未提供”，不估算成实测数据。

检查结果：同一会话恰好保存 USER/ASSISTANT 各一条；回答非空；引用的 5 个 API 条目、2 个文档条目及当前任务均存在，并逐项核对与实际存入用户上下文一致，无抢票接口混入。其他学生读取该会话返回 404。当前前端产物、问答内容、应用及专属 Nginx 日志均未发现实际辅导密钥；前端没有本机 API 地址或本机路径。

回答质量人工核对：

- 正确指出上传应为 multipart/FormData 和真实 File，而不是 JSON 文件名。
- 正确指出工单绑定字段为 `imageIds`，上传响应 `data.id` 是附件 ID，最多 6 个。
- 未声称实际执行过代码或测试，没有虚构接口。
- **仍有遗漏**：示例创建工单未提供 `deviceId`，也未提供设备名称、类型、校区和位置，回答没有指出这组必填条件；最终查询工单图片的验证步骤没有写出必填 `operatorId`。`orderNo` 的表述也未明确强调响应封装 `data` 层。
- 因此判定“调用和主问题定位通过，回答完整性有缺口”，不能以 HTTP 200 认定内容完全正确。部署阶段未无关改写业务，也未消耗第二次调用去掩盖质量限制。

## 实际验收覆盖

| 场景 | 结果/证据 |
| --- | --- |
| 前端生产构建及单元契约 | 通过，13 项，无失败/跳过 |
| 后端 Maven package、单元测试 | 通过，34 项，无失败/跳过；JAR 无 DevTools |
| 首页、入口静态资源、docs/tasks/teacher/tasks/ai-tutor 路由刷新 | 公网 HTTP 通过 |
| 教师、三个虚构学生登录与退出 | 通过；退出后受保护接口为 401 |
| 班级、学生、workspace、两个项目查询 | 通过；3 个独立 workspace |
| 草稿任务对学生隐藏、发布后班级学生可见 | 通过 |
| 草稿→上传截图→正式提交→退回→第二版→评分→旧版反馈 | 公网接口完整通过；草稿无正式版本，正式版本保留两版 |
| 报修上传、预览、下载、绑定工单、查询 | 通过，返回真实图片字节 |
| 双学生截图、成果、workspace、辅导会话隔离 | 通过，跨学生访问受拒绝 |
| 指定第三学生只重置 TICKET | 通过；其 REPAIR 数据及其他学生教学/会话记录保留 |
| 真实辅导接口、记录、引用与上下文 | 通过；回答内容限制见上 |
| MySQL/应用/Nginx 重启持久性 | 通过，29 表及2图片基线一致，公网重读通过 |
| 配置权限、最小数据库权限、开机启动、公网端口 | 通过 |
| 浏览器页面内操作、AI 回答/来源视觉展示、控制台无错误 | **未验证**：虚拟机内嵌浏览器能列出登录页标题，但 AX、DOM、截图控制连续超时；不能用 HTTP 结果冒充浏览器验收 |
| 公网生产负载、长时间资源趋势 | 未进行压力测试，未长期观测 |

首次离线打包因 Maven jar-plugin 尚未缓存而失败，联网补齐该构建依赖后正常打包；未重新安装本机 Maven/JDK。首次后端启动就绪前曾观察到短暂 502，完成启动后 `/hello` 返回 HTTP 200 和准确正文 `Hello Spring Boot!`；后续验收无业务接口失败。浏览器故障与 AI 内容遗漏分别保留，未修改断言放行。

## 体验步骤

教师登录后进入任务管理，查看演示任务、提交列表与两版成果；可新建草稿，选择报修或抢票项目及演示班，发布后由学生查看。学生 002 可从“我的任务”进入任务详情，保存草稿、上传截图并正式提交；教师评价或退回；学生查看反馈及历史版本。学生 001 的 AI 辅导记录已有本轮真实回答，可查看来源，继续提问会消耗配置额度。

口令仅由维护者通过 SSH 读取 `/etc/vue-practice/demo-accounts.json`，或领取当前虚拟机的私密副本。不要将该文件、`/etc/vue-practice`、验收原始状态、日志或数据库备份提交 Git。无域名阶段按要求使用 HTTP；后续提供域名后再配置 HTTPS，当前不承诺传输层加密。

## 日常管理

以下在服务器执行，或以 `ssh vue-practice-server '命令'` 从虚拟机执行：

```bash
sudo systemctl start vue-practice
sudo systemctl stop vue-practice
sudo systemctl restart vue-practice
sudo systemctl status vue-practice mysql nginx --no-pager
sudo tail -n 100 /var/log/vue-practice/application.log
sudo journalctl -u vue-practice -n 50 --no-pager
sudo nginx -t
```

停止应用命令不停止 MySQL、Nginx 或无关服务。运行目录位于版本目录之外；更换 current 不覆盖图片或配置。

## 更新发布

`setup-fresh.py` 仅供已经检查过的空白新服务器首次初始化；已部署服务器禁止重跑 seed/首次初始化。正常更新步骤：

1. 核对成果仓库 origin 和远程差异，保护工作区。为当前终端配置已有 Java 21、Maven、npm 的 PATH，不重装本机环境。
2. 在仓库运行 `python3 scripts/deploy/package-release.py /绝对路径/全新私密发布目录 HEAD`。脚本导出指定 commit 到仓库之外、运行前后端测试和构建、生成带 SHA 清单的发布包；不读取 `.runtime`，不打包 node_modules 或数据库。复用前端依赖前会比对 lockfile；不一致时停止，由维护者在导出目录准备对应依赖。
3. 将生成的 `<完整SHA>.tar.gz` 通过 scp 上传到服务器 `/root/`。用一个全新且不存在的 `/opt/vue-practice/releases/<完整SHA>` 目录解包；先检查归档清单不含外部路径、符号链接或私密文件。不要覆盖已有 release。
4. 审查本次迁移与旧版代码兼容性，再运行 `sudo /usr/local/sbin/vue-practice-activate <完整SHA>`。服务器上的工具来自 `scripts/deploy/activate-release.sh`：校验摘要→停本应用→备份数据库/图片/配置→显式增量迁移→记录 previous→原子切换 current→启动及严格健康检查。失败保留备份并报非零，禁止删除数据来恢复。工具不替代公网业务验收。
5. 通过公网检查首页、登录、图片、任务和引用。真实 AI 请求需要新的明确调用预算；普通发布不应自动发付费请求。

可重复打包脚本已在独立目录实际重建部署提交，前端 13 项及后端 34 项测试再次通过；Python/shell 语法及当前版本清单校验已运行，同 SHA 再次激活实测不重启、不写数据库。首次新版本切换/回滚分支尚未在另一业务版本实测，不宣称已做跨版本回滚演练。

## 回滚

本服务器是首次部署，没有更早的可运行版本；现存初始 Nginx/MySQL 配置备份不等于旧版业务。后续发布后 `previous` 保存上一个 release。先检查其数据库兼容性，确认可用后执行：

```bash
# 在服务器执行；只回退代码，绝不自动删表或回灌数据库
previous_release=$(readlink -f /opt/vue-practice/previous)
test -f "$previous_release/backend/app.jar" || exit 1
sudo systemctl stop vue-practice
sudo ln -s "$previous_release" /opt/vue-practice/current.rollback
sudo mv -Tf /opt/vue-practice/current.rollback /opt/vue-practice/current
sudo nginx -t
sudo systemctl start vue-practice
sudo systemctl reload nginx
```

随后检查准确健康响应与公网业务。数据库独立评估：优先前向修复；若必须恢复备份，先保留当前库和增量数据，由维护者评估停机及数据合并，不能自动 DROP 或通过删表“回滚”。配置修改也须保留备份、语法检查后再加载。

## 验收脚本复用

`tests/deployment/public_acceptance.py` 与既有本地协议桩护栏独立：固定新服务器/独立库/独立图片根，运行前经 SSH 核对实际进程环境和 MySQL 会话；只使用四个虚构演示账号。原本地测试护栏没有放宽。

```bash
# 指向私密验收目录，含已领取的 demo-accounts.json 和本次状态文件
export DEPLOY_ACCEPTANCE_DIR=/绝对路径/私密验收目录
python3 tests/deployment/public_acceptance.py persistence
# full 会写演示数据，仅首次空白验收环境可运行，已有状态文件时主动拒绝
# ai 会产生真实费用；预算写入 real-ai-budget.json，最多两次，不自动重试
```

本次实际运行顺序为 full → ai（一次）→服务重启→persistence。不得删除预算文件或状态文件来绕过保护。原始状态、回答、口令与日志未上传；仓库保留脱敏场景报告和可运行脚本。
