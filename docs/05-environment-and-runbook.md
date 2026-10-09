# 环境准备、日常运行与隔离验证

本页是 2026-10-09 审查后的运行说明，由 Codex 根据 Harness 修改和实际验收更新。
业务设计、接口及代码来源见 `03-reuse-and-reimplementation.md`；本轮分工和证据见 `07-final-acceptance.md`。

## 已有环境再次启动

当前虚拟机已有 JDK、Maven、MySQL 程序、初始化的数据目录和项目依赖。
安装压缩包或 npm 缓存缺失不影响已安装程序，不应重新下载或重新初始化。
已有数据库禁止重复导入 SQL、清空或重建。

在项目根目录执行：

```bash
bash scripts/start-all.sh
bash scripts/stop-all.sh
```

启动顺序为 MySQL、后端 8100、前端 5173。脚本检查应用数据库连接、进程身份和 HTTP 就绪状态；
超时或配置错误返回非零。重复启动核对原 PID、启动时间、进程组及响应。
并发启动/停止使用同一把锁，竞争者返回 4；应等前一次执行结束后再重试。
停止脚本仅处理本运行目录记录的前后端进程组，默认保留共享 MySQL。
没有记录或身份不符的服务不会被接管；不要使用通用 `pkill` 清理。

本虚拟机另有 Codex 创建的本地忽略包装脚本，加载当前网卡 CORS 设置和结构只读验证配置：

```bash
cd /home/tsar413/projects/Vue-Practise-Class-Projects-DeepSeek
bash .runtime/run-local.sh start
bash .runtime/run-local.sh stop
```

`.runtime/run-local.sh`、`.runtime/local.env` 和 `frontend/.env.local` 不进入 Git。

## 版本与目录

| 组件 | 已核验版本 / 目录 |
| --- | --- |
| 系统 | Ubuntu 24.04.4 LTS / Linux 7.0.0-31-generic / x86_64 |
| Node / npm / Git | 24.14.0 / 11.9.0 / 2.43.0 |
| JDK | 21.0.12.1+1，`~/tools/opt/jdk-21.0.12.1+1` |
| Maven | 3.9.16，`~/tools/opt/apache-maven-3.9.16` |
| MySQL | 8.0.44，`~/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64` |
| 兼容动态库 | `~/tools/opt/lib`，由脚本设置 `LD_LIBRARY_PATH` |
| MySQL 数据 / socket | `~/var/mysql/data` / `~/var/mysql/db.sock` |
| MySQL 日志 | `~/var/mysql/log/error.log` |
| 正常应用运行记录 / 日志 | `.runtime/run/env.state` / `.runtime/run/backend.log`、`frontend.log` |
| 正常图片 | `backend/repair-files`（后端默认工作目录为 `backend`） |
| Harness | `~/ai-tools/deepseek-harness`，0.2.0-rc.2，沿用 deepseek-flash / Max |

本轮未重新安装上述环境或更换项目依赖。磁盘扩容后实测可用约 23 GB。
虚拟机内存约 3.8 GiB，同时运行两个后端和浏览器会产生交换压力，隔离测试与正常验收宜串行执行。

## 全新环境首次准备

本节只适用于没有数据的新环境，本轮没有对原 MySQL 数据目录执行这些步骤。

1. 准备与上表兼容的 Java、Maven、Node 和 MySQL，设置 `JAVA_HOME`、`MAVEN_HOME`、`MYSQL_HOME`；
   项目默认使用上述用户目录。前端缺少依赖时在 `frontend` 执行 `npm ci`，后端在 `backend` 执行 `mvn -DskipTests package`。
2. **仅对全新、空的数据目录**初始化 MySQL。已有 `auto.cnf` 或任何数据文件时必须停止此步骤：

```bash
export MYSQL_HOME="$HOME/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64"
export LD_LIBRARY_PATH="$HOME/tools/opt/lib:${LD_LIBRARY_PATH:-}"
mkdir -p "$HOME/var/mysql/data" "$HOME/var/mysql/log" "$HOME/var/mysql/tmp"
test -z "$(find "$HOME/var/mysql/data" -mindepth 1 -print -quit)" || exit 1
"$MYSQL_HOME/bin/mysqld" --no-defaults --initialize-insecure \
  --basedir="$MYSQL_HOME" --datadir="$HOME/var/mysql/data"
START_BACKEND=0 START_FRONTEND=0 bash scripts/start-all.sh
bash scripts/init-db.sh
```

这里是回环绑定的本地教学环境初始化流程；脚本使用本机 socket 上已有的 root 认证。
`init-db.sh` 只新建：库或账号已存在就拒绝，既不修改原账号口令，也不覆盖种子数据；没有 RESET/drop 入口。
自定义库例如 `DB_NAME=class_demo_v1 bash scripts/init-db.sh`，日常启动也应使用相同 `DB_NAME`；
建库、表、授权、种子和后端连接保持一致。指定 `DB_URL` 时须与显式 `DB_NAME` 一致且只连接本机。
默认应用口令和演示账号口令是仓库公开的本地演示值，不能把本仓库表述为“无口令”。
自定义口令通过环境变量或忽略文件提供，避免进入命令日志和版本库。

3. 复制 `frontend/.env.example` 为忽略文件 `.env.local`，配置完整的 `VITE_API_BASE_URL`，再正常启动。
4. 全新库的虚构账号由 SQL 创建；教师按班级初始化两个项目的数据。已有环境的日常启动不执行此操作。

## 只在隔离环境运行写测试

正常数据库不得用于全量接口测试或浏览器写入测试。历史 `frontend/tests/browser.mjs` 包含写操作，
不可直接对 8100 执行；本轮正常浏览器验收仅做登录、退出和查询。

```bash
bash scripts/review-env.sh init
bash scripts/review-env.sh start
READ_ONLY=1 bash scripts/review-verify.sh
OUT=/tmp/review-result.txt bash scripts/review-verify.sh
API_BASE=http://127.0.0.1:18100 OUT=/tmp/api-result.txt bash scripts/api-verify.sh
bash scripts/review-env.sh stop
```

隔离库 `vue_practise_review_20261009`、独立用户、`.runtime/review-20261009/run`、
`.runtime/review-20261009/repair-files` 和回环端口 18100 与正常环境分开。
写测试前统一核对 `/proc` 中实际后端连接、图片目录和记录进程组，失败立即退出。
`READ_ONLY=1` 不登录、不写入。包含凭证的临时响应只进入私有目录，并在退出时删除。
不要并发运行测试套件：同一账号再次登录会轮换网页 Token，导致另一套测试的旧凭证失效。
已有测试库可能包含上轮夹具变化，完整套件需要在该**独立测试库**中准备相应基准状态。

## 访问与限制

虚拟机浏览器访问 `http://127.0.0.1:5173`，后端检查 `http://127.0.0.1:8100/hello`。
宿主机浏览器使用 `http://<虚拟机当前IP>:5173`；在虚拟机执行 `hostname -I` 获取地址。
前端本地配置 `VITE_API_BASE_URL=http://<虚拟机IP>:8100`；后端 CORS 加入**前端来源**
`http://<虚拟机IP>:5173`，并保留 localhost / 127.0.0.1 的 5173、4173 来源。
有代理时检查现有免代理列表，保证访问虚拟机自己的地址不会被转发；不要猜代理地址。
本轮只在虚拟机内部操作；没有操作宿主机、改变防火墙或开放公网。

五分钟重置冷却由前端本地状态实现，**不是后端限流**，本轮没有改变规则。
角色、workspace、跨学生访问和重置范围由后端校验；图片删除任务与业务事务同时提交，
后台每 30 秒处理任务。磁盘删除和事务回滚已有实际验证，见最终验收记录。
抢票/报修高负载压测、生产部署及宿主机真实浏览器连通性仍未验证。

## 10. 教学与 AI 辅导模块的运行要点

新增结构必须通过迁移脚本创建，应用已改为 `ddl-auto: none`：

```bash
# 只读预检 / 状态
python3 scripts/migrate.py --database <库> --dry-run
python3 scripts/migrate.py --database <库> --status
# 实际应用（会校验 SHA-256、结构与唯一约束，成功后写 schema_migration）
python3 scripts/migrate.py --database <库>
```

* 迁移器必须显式给出 `--database`，不会切库也不会建库；
  失败保留现场、不自动删表，重复执行幂等且不刷新首次 `applied_at`。
* 教学截图目录 `TEACHING_FILES_ROOT`（默认后端工作目录下 `teaching-files`）
  与报修图片目录**分开**，不要互相指认。
* AI 辅导密钥只来自环境变量；未配置时页面显示「AI辅导暂未配置」，
  不会用模拟答案冒充真实回答。教师看不到学生私聊。
* 详细表结构、接口与规则见 [教学与 AI 辅导](08-teaching-and-ai-tutor.md)。
