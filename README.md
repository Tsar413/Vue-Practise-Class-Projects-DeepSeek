# Vue 实训管理平台（DeepSeek 复现版）

本项目是参考仓库 [Tsar413/Vue-Practise-Class-Projects](https://github.com/Tsar413/Vue-Practise-Class-Projects)
（分支 `main`，基线 commit `c0f9aa319cec03c0e32849118cae26e76859c701`）重新实现的前后端完整代码，
用于 Vue 项目实训课堂：教师管理班级、账号与工作空间，每名学生在独立空间中练习
「校园抢票」与「校园设备报修」两个实训项目。

> 说明：本仓库为**重新实现**，不是参考仓库的克隆副本。
> 复用范围（教师提供的接口文档数据与页面样式）与重新实现范围见
> [docs/03-reuse-and-reimplementation.md](docs/03-reuse-and-reimplementation.md)。

## 技术栈与版本

| 层 | 技术 | 版本 |
| --- | --- | --- |
| 后端 | Java | 21 |
| 后端 | Spring Boot | 4.1.1 |
| 后端 | MyBatis-Plus | 3.5.16 |
| 后端 | MySQL Connector/J | 由 Spring Boot 4.1.1 管理 |
| 后端 | jsoup / Apache POI / TwelveMonkeys WebP | 1.23.2 / 5.5.1 / 3.12.0 |
| 数据库 | MySQL | 8.0 |
| 前端 | Vue | 3.5 |
| 前端 | Vite | 7 |
| 前端 | vue-router / pinia / axios | 4.5 / 3.0 / 1.8 |
| 前端 | marked / dompurify | 15.0 / 3.2 |

## 目录结构

```
backend/          Spring Boot 后端（端口 8100）
frontend/          Vue 3 前端（开发端口 5173）
db/               数据库初始化脚本与虚构演示数据
scripts/          环境启动、数据库初始化与验证脚本
docs/             架构分析、对照表、复现记录与验证结论
```

## 快速开始

以下命令在项目根目录执行，全部只操作本机资源。

```bash
# 1. 启动 MySQL、后端与前端（可重复执行，已在运行的组件会跳过）
bash scripts/start-all.sh

# 2. 首次使用或需要重置数据时，初始化数据库与演示数据
#    （新库可加 RESET=1 先删除同名数据库再重建）
bash scripts/init-db.sh

# 3. 停止全部组件
bash scripts/stop-all.sh
```

访问地址：

- 前端：<http://127.0.0.1:5173>
- 后端：<http://127.0.0.1:8100>
- 后端连通检查：<http://127.0.0.1:8100/hello>

### 演示账号（全部为虚构数据，口令均为 `123456`）

| 角色 | 编号 | 说明 |
| --- | --- | --- |
| 教师 | `DEMO_TEACHER` | 管理班级、账号、工作空间与学生导入 |
| 学生 | `DEMO2026001` | 演示学生甲 |
| 学生 | `DEMO2026002` | 演示学生乙 |
| 学生 | `DEMO2026003` | 演示学生丙 |

班级编号：`DEMO2026`（Vue 实训示范班）。

登录教师账号后，在「工作空间」页按班级执行
「按班级初始化」，即可为全班学生生成两个项目的基准数据。

## 数据库配置

后端通过环境变量读取数据库连接，默认值为本地开发配置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_URL` | `jdbc:mysql://127.0.0.1:3306/vue_practise_backend?...` | 只连接本机测试库 |
| `DB_USERNAME` | `vue_practice` | 应用账号 |
| `DB_PASSWORD` | `vue_practice_local` | 仅本地测试用；生产环境请用环境变量覆盖 |
| `REPAIR_FILES_ROOT` | `./repair-files` | 报修图片存储目录，已在 `.gitignore` 中排除 |
| `SERVER_PORT` | `8100` | 后端端口 |
| `APP_CORS_ALLOWED_ORIGINS` | 本机 5173 / 4173 | 允许跨域的前端地址 |

前端接口地址读取 `frontend/.env.local` 中的 `VITE_API_BASE_URL`，
可从 `frontend/.env.example` 复制后修改。

## 验证

```bash
# 接口 / 权限 / 数据隔离 / 重置 对照验证（246 项断言）
bash scripts/api-verify.sh

# 后端纯逻辑单元测试（口令散列、凭证、富文本清洗）
cd backend && mvn test

# 前端契约单元测试
cd frontend && npm test

# 真实浏览器端到端验证（需要本机安装 Google Chrome）
cd frontend && node tests/browser.mjs

# 生成测试素材（PNG 与 Excel 示例文件）
node scripts/make-fixtures.mjs
```

验证结论保存在 `docs/verification-api.txt`、`docs/verification-browser.txt`，
截图保存在 `docs/screenshots/`。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/01-architecture-analysis.md](docs/01-architecture-analysis.md) | 原项目架构、数据库、接口与业务规则分析 |
| [docs/02-comparison.md](docs/02-comparison.md) | 源代码基准、页面 / 功能 / 接口对照表 |
| [docs/03-reuse-and-reimplementation.md](docs/03-reuse-and-reimplementation.md) | 复用范围、重新实现范围与关键设计决定 |
| [docs/04-development-log.md](docs/04-development-log.md) | 分模块复现过程、问题修复与测试结果 |
| [docs/05-environment-and-runbook.md](docs/05-environment-and-runbook.md) | 环境依赖、初始化、启动停止与运行限制 |
| [docs/verification-api.txt](docs/verification-api.txt) | API 对照验证逐项结果 |
| [docs/verification-browser.txt](docs/verification-browser.txt) | 浏览器端到端验证逐项结果 |

## 说明

- 仓库中不包含任何真实学生姓名、学号或联系方式；演示数据全部为虚构内容。
- 数据库口令、访问码等敏感信息通过环境变量提供，不写入仓库。
- 本轮只复现参考项目已有功能；任务发布、成果提交、自动评分与 AI 辅导留待下一阶段。
