# 环境依赖、初始化、启动停止与运行限制

## 1. 实际使用的环境与版本

验证时的实际版本（命令输出）：

| 组件 | 版本 | 验证命令 |
| --- | --- | --- |
| 操作系统 | Ubuntu 24.04.4 LTS（x86_64） | `lsb_release -a` |
| 内核 | 见 `uname -r` | — |
| Java | Temurin OpenJDK 21.0.12.1+1 LTS | `java -version` |
| Maven | Apache Maven 3.9.16 | `mvn -v` |
| MySQL | 8.0.44（linux-glibc2.17-x86_64） | `mysql --version` / `SELECT VERSION()` |
| Node.js | v24.14.0 | `node -v` |
| npm | 11.9.0 | `npm -v` |
| 浏览器 | Google Chrome（本机已安装，`/opt/google/chrome/chrome`） | — |
| playwright-core | 1.64.0（仅测试时临时安装） | `npm ls playwright-core` |

虚拟机内存约 3.8 GB，可用约 1.3 GB，磁盘剩余约 1.8 GB。
这些限制影响了浏览器验证方式（详见第 7 节）。

## 2. 依赖位置

所有组件解压在用户目录下，不覆盖系统或其他项目的配置：

```
~/tools/opt/jdk-21.0.12.1+1/                       JDK 21
~/tools/opt/apache-maven-3.9.16/                   Maven
~/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64/   MySQL
~/tools/opt/lib/                                   额外动态库（libaio.so.1、libncurses.so.5 兼容链接）
~/var/mysql/                                       MySQL 数据目录、socket、日志
~/var/run/                                         后端与前端后台进程日志
```

`scripts/start-all.sh`、`scripts/stop-all.sh`、`scripts/init-db.sh` 支持用环境变量覆盖这些路径：

| 变量 | 作用 | 默认值 |
| --- | --- | --- |
| `MYSQL_HOME` | MySQL 安装目录 | `$HOME/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64` |
| `JAVA_HOME` | JDK 安装目录 | `$HOME/tools/opt/jdk-21.0.12.1+1` |
| `MAVEN_HOME` | Maven 安装目录 | `$HOME/tools/opt/apache-maven-3.9.16` |
| `EXTRA_LIB` | 额外动态库目录 | `$HOME/tools/opt/lib` |
| `RUN_DIR` | 运行期数据根目录 | `$HOME/var` |
| `DB_SOCKET` | MySQL socket 路径 | `$RUN_DIR/mysql/db.sock` |
| `DB_NAME` | 数据库名 | `vue_practise_backend` |
| `DB_USERNAME` / `DB_PASSWORD` | 应用账号与口令 | `vue_practice` / `vue_practice_local` |

## 3. 数据库初始化

```bash
# 首次初始化（创建库、表、应用账号、演示数据）
bash scripts/init-db.sh

# 需要清空重来时先删除同名库
RESET=1 bash scripts/init-db.sh
```

脚本行为：

1. 执行 `db/01-schema.sql` 创建数据库与 15 张表（可重复执行）。
2. 创建只能访问本库的本机应用账号（默认 `vue_practice@127.0.0.1`）。
3. 执行 `db/02-seed-demo-data.sql` 导入虚构演示数据（可重复执行）。

**安全说明**：脚本只连接本机 MySQL（通过 socket 或 `127.0.0.1`），
不会连接、也不会修改任何线上数据库。

演示数据（全部虚构，无真实学生信息）：

| 类型 | 编号 | 姓名 |
| --- | --- | --- |
| 班级 | `DEMO2026` | Vue 实训示范班 |
| 教师 | `DEMO_TEACHER` | 示范教师 |
| 学生 | `DEMO2026001` / `DEMO2026002` / `DEMO2026003` | 学生甲 / 学生乙 / 学生丙 |

口令统一为 `123456`；登录后由系统生成网页登录 Token 与学生长期访问码。
抢票与报修的基准数据需要教师登录后，在「工作空间」页按班级执行
「按班级初始化」，或调用：

```
POST /api/sys-workspace/classes/DEMO2026/ticket/initialize
POST /api/sys-workspace/classes/DEMO2026/repair/initialize
```

## 4. 启动与停止

### 4.1 启动

```bash
bash scripts/start-all.sh
```

按顺序启动 MySQL → 后端（8100）→ 前端（5173）。
已在运行的组件会被跳过，因此可以重复执行。

### 4.2 访问地址

| 用途 | 地址 |
| --- | --- |
| 前端页面 | <http://127.0.0.1:5173> |
| 后端服务 | <http://127.0.0.1:8100> |
| 后端连通检查 | <http://127.0.0.1:8100/hello> |
| 前端预览（构建产物） | <http://127.0.0.1:4173>（执行 `npm run preview`） |

### 4.3 停止

```bash
bash scripts/stop-all.sh
```

按前端 → 后端 → MySQL 的顺序停止，并等待后端端口释放。

### 4.4 日志

| 文件 | 内容 |
| --- | --- |
| `~/var/run/backend.log` | 后端 Spring Boot 日志 |
| `~/var/run/frontend.log` | 前端 Vite 日志 |
| `~/var/mysql/log/error.log` | MySQL 错误日志 |

## 5. 从宿主机访问

后端与前端默认监听 `0.0.0.0`（Vite 使用 `--host 0.0.0.0`），
因此在 VMware 宿主机上可以访问：

1. 先获取虚拟机 IP：`hostname -I`（当前环境中为 `192.168.247.128` 或 `192.168.17.128`）。
2. 在宿主机浏览器打开 `http://<虚拟机IP>:5173`。
3. 前端通过 `VITE_API_BASE_URL` 访问后端；若从宿主机访问，
   需要把 `frontend/.env.local` 改为 `http://<虚拟机IP>:8100`，
   并把该地址加入后端 `APP_CORS_ALLOWED_ORIGINS`。

**本轮没有开放任何公网端口**，只做本机与宿主机访问。

## 6. 公开前检查结果

| 检查项 | 方法 | 结果 |
| --- | --- | --- |
| 数据库口令 | 全量扫描 `password`、`passwd`、`secret` 等关键词 | 仅在 `application.yml` 中出现环境变量默认值 `vue_practice_local`（本地测试口令），不含任何线上凭据 |
| API Key / Token / 私钥 | 扫描 `apikey`、`token`、`PRIVATE KEY`、`BEGIN RSA` | 无。代码中的 token 均为运行期随机生成，无硬编码值 |
| 真实学生个人信息 | 扫描演示数据与文档中的姓名、学号、手机号 | 演示数据全部为虚构编号（`DEMO*`、`IMPORT*`）与占位姓名（学生甲/乙/丙）；手机号统一为 `138000000xx` 示例号段 |
| 数据库备份与日志 | 检查是否存在 `.sql` 备份、`*.log` | `db/` 下只有建表与演示数据脚本；日志目录已在 `.gitignore` 中排除 |
| 截图敏感内容 | 逐张检查 `docs/screenshots/` | 截图中出现的访问码位置已用 Playwright 的 `mask` 遮蔽为占位色块，不包含任何运行期凭证；其余内容均为虚构演示数据 |
| 本地配置 | 检查 `.env.local` 等 | 已在 `.gitignore` 中排除，仓库只提交 `.env.example` |
| 依赖目录与构建产物 | 检查 `node_modules`、`dist`、`target` | 均已在 `.gitignore` 中排除 |
| 目标仓库地址 | `git remote -v` | 只指向 `Tsar413/Vue-Practise-Class-Projects-DeepSeek`，不包含源仓库的推送地址 |

## 7. 运行限制与已知问题

1. **内存受限影响浏览器验证**：虚拟机可用内存约 1.3 GB，
   Chrome 在执行「整页截图」或「页面重载」时偶发渲染进程崩溃。
   处理方式：收敛 Chrome 启动参数、截图改为尽力而为、用新开页面替代整页刷新。
   功能断言全部通过，`docs/screenshots/reset-cooldown.png` 因该限制缺失，
   不影响结论。
2. **MySQL 依赖额外动态库**：Ubuntu 24.04 只有 `libaio.so.1t64`，
   而 MySQL 二进制链接 `libaio.so.1`。
   已通过 `~/tools/opt/lib/libaio.so.1` 符号链接解决，
   `scripts/start-all.sh` 会自动把该目录加入 `LD_LIBRARY_PATH`。
3. **未做并发压测**：抢票与报修的并发策略通过「工作空间行锁 + 固定加锁顺序」实现，
   只做了代码审查，没有做压力测试。
4. **图片异步清理未逐文件断言**：重置时通过任务表登记待删文件，
   后台每 30 秒清理一次；验证只覆盖了数据库记录清空，没有断言磁盘文件已删除。
5. **未做生产部署**：本轮只做本机联调，没有配置反向代理、HTTPS、
   进程守护与生产数据库；`ddl-auto: update` 与默认口令均只适合本地开发。
6. **`repair-files/` 为运行期目录**：图片按 `{workspaceId}/{yyyyMM}/{uuid}.{ext}` 落盘，
   已在 `.gitignore` 中排除。
7. **前端构建告警**：`Docs.vue` 打包后约 410 KB（含全部接口文档数据），
   属于文档数据的固有体积；已按路由拆分为独立 chunk，不影响首页加载。
