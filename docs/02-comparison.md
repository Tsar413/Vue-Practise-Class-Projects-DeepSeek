# 对照表：源代码基准、页面、功能与接口

本文档给出「参考项目 → 复现实现 → 验证结果」的逐项对照。

## 1. 源代码基准

| 项目 | 值 |
| --- | --- |
| 参考仓库 | <https://github.com/Tsar413/Vue-Practise-Class-Projects> |
| 参考分支 | `main` |
| 参考 commit | `c0f9aa319cec03c0e32849118cae26e76859c701`（提交信息：Fix some bugs，2026-10-07） |
| 参考仓库文件数（git 跟踪） | 154 |
| 参考仓库后端 Java 文件 | 97 个，8201 行 |
| 参考仓库前端源文件 | 28 个（含 `style.css` 与 13 个接口文档 JSON） |
| 复现仓库 | <https://github.com/Tsar413/Vue-Practise-Class-Projects-DeepSeek> |
| 复现后端 Java 文件 | 97 个，7799 行 |
| 复现前端源文件 | 28 个，2052 行 |
| 获取方式 | 本地 `git clone`，仅用于读取与对照，未做任何修改、推送或设置变更 |
| 成果基准提交受版本控制的文件数 | **178**（`231` 是 Git 目录项数量，含目录条目，容易误读） |

> 说明：参考仓库未提供任何初始化 SQL；数据库表由实体上的 JPA 注解配合
> `ddl-auto: update` 在启动时生成。复现实现保持同样机制，并把生成结果导出为
> `db/01-schema.sql`，便于脱离 Hibernate 直接建库。

本表源码行数和页面验证描述属于原基准记录；本轮最终 Git 跟踪文件为196个；新增文件及重新执行的验收结果见 [最终验收](07-final-acceptance.md)。

## 2. 技术栈与版本对照

| 项 | 参考项目 | 复现实现 | 是否一致 |
| --- | --- | --- | --- |
| Java | 21 | 21 | 一致 |
| Spring Boot | 4.1.1 | 4.1.1 | 一致 |
| MyBatis-Plus | `mybatis-plus-spring-boot4-starter` 3.5.16 | 同 | 一致 |
| MyBatis-Plus SQL 解析 | `mybatis-plus-jsqlparser` 3.5.16 | 同 | 一致 |
| 数据库驱动 | `mysql-connector-j`（Boot 管理版本） | 同 | 一致 |
| ORM 建表 | `spring-boot-starter-data-jpa`，`ddl-auto: update` | 同 | 一致 |
| 富文本清洗 | jsoup 1.23.2 | 同 | 一致 |
| Excel | poi-ooxml 5.5.1 | 同 | 一致 |
| WebP 支持 | imageio-webp 3.12.0 | 同 | 一致 |
| 加密工具 | hutool-crypto 5.8.26 | 同 | 一致 |
| Lombok | 1.18.42 | 同 | 一致 |
| 模板引擎 | `spring-boot-starter-thymeleaf` | 同 | 一致 |
| 邮件 | `spring-boot-starter-mail` | 同 | 一致 |
| 热部署 | `spring-boot-devtools` | 同 | 一致 |
| Redis | `spring-boot-starter-data-redis`（源码中无任何使用） | **未引入** | 有意差异，见下 |
| 前端框架 | Vue 3.5 / Vite 7 / vue-router 4.5 / pinia 3.0 | 同 | 一致 |
| 前端请求库 | axios 1.8 | 同 | 一致 |
| 文档渲染 | marked 15 / dompurify 3.2 | 同 | 一致 |
| 前端测试 | `@playwright/test` 1.55 | `playwright-core`（复用本机 Chrome） | 见说明 |
| 后端端口 | 8100 | 8100 | 一致 |
| 前端端口 | 5173 / 预览 4173 | 同 | 一致 |

**有意差异说明**

1. **未引入 Redis**：参考项目 `pom.xml` 声明了 `spring-boot-starter-data-redis`，
   但全仓库（Java 源码与配置文件）没有任何 Redis 相关代码或配置。
   直接保留该依赖会让服务在缺少 Redis 的环境下启动失败，因此复现实现移除了它。
   这是「去掉未使用的依赖」，不改变任何可观察的接口行为。
2. **补充 `maven-compiler-plugin` 的 `<proc>full</proc>`**：JDK 21 起注解处理需要显式开启，
   否则 Lombok 在某些环境下不会生成访问器。显式声明保证构建可复现。
3. **前端浏览器测试库**：参考项目用 `@playwright/test`（需下载浏览器）。
   本机已安装 Google Chrome，复现实现改用 `playwright-core` 驱动本机浏览器，
   减少一次大体积下载，断言内容重新编写。

## 3. 页面与交互对照

| 路由 | 页面 | 角色 | 职责 | 复现情况 |
| --- | --- | --- | --- | --- |
| `/login` | `Login.vue` | 公开 | 账号登录，写入 sessionStorage 会话 | 复现，含错误提示与禁用态 |
| `/` | `Home.vue` | 全部 | 首页：角色化入口、学生工作空间信息 | 复现 |
| `/teacher/classes` | `Management.vue`（kind=classes） | 教师 | 班级查询、新增、编辑、启停、删除 | 复现 |
| `/teacher/users` | `Management.vue`（kind=users） | 教师 | 系统账号查询、新增、编辑、启停、删除 | 复现 |
| `/teacher/workspaces` | `Management.vue`（kind=workspaces） | 教师 | 按班级/学号查询空间、启停、重置、按班级初始化 | 复现 |
| `/teacher/import` | `Import.vue` | 教师 | 学生名单 Excel 批量导入与逐行结果 | 复现 |
| `/data` | `ProjectData.vue` | 学生 | 两个项目的只读数据浏览、工单关联信息与图片预览下载 | 复现 |
| `/addresses` | `Addresses.vue` | 学生 | 本人接口基础地址复制与使用说明 | 复现 |
| `/docs` | `Docs.vue` | 全部 | 接口文档：分组、搜索、参数填写、真实请求、响应展示、冷却 | 复现 |
| `/reset` | `Reset.vue` | 学生 | 按项目重置本人数据，成功后进入五分钟冷却 | 复现 |

**交互行为对照**

| 行为 | 参考项目 | 复现实现 | 验证 |
| --- | --- | --- | --- |
| 未登录访问受保护路由 | 跳转 `/login` | 同 | 浏览器验证通过 |
| 学生访问教师路由 | 前端拦截回首页，后端 403 | 同（前端 + 后端双重） | 浏览器与 API 验证通过 |
| 教师查看业务文档 | 只读，不显示发送按钮 | 同 | 浏览器验证通过 |
| 文档请求冷却 | 每次发送后 10 秒，仅学生，只读查询与图片不计 | 同 | 浏览器验证通过 |
| 冷却持久化 | `localStorage`，键按「学生 + 用途」区分 | 同 | 浏览器验证通过 |
| 重置冷却 | 成功后 5 分钟，仅学生 | 同；失败与本地校验不启动 | 浏览器与 API 验证通过 |
| 跨标签页串行 | `navigator.locks` | 同 | 代码一致 |
| 重置后数据失效提示 | 广播事件并重新加载 | 同 | 浏览器验证通过 |
| 移动端布局 | 390px 无横向溢出 | 同 | 浏览器验证通过 |

## 4. 功能对照与验证结果

图例：**已实现**＝代码完成且实际验证通过；**部分实现**＝行为与参考项目一致但覆盖范围有限；
**未实现**＝本轮未做；**验证失败**＝已实现但断言未通过。

### 4.1 账号与登录

| 功能 | 参考项目 | 复现实现 | 验证方式与结果 | 结论 |
| --- | --- | --- | --- | --- |
| 教师 / 学生登录 | 有 | 有 | API：正确口令 200、角色 TEACHER/STUDENT | 已实现 |
| 口令散列 | SHA-256(salt + ":" + 口令) | 同 | 初始化脚本用 MySQL `SHA2` 复算比对一致；登录成功即验证 | 已实现 |
| 登录失败分类 | 400/401/403 六类提示 | 同 | API：空参 400、超长 400、错误口令 401、停用 403、班级停用 403 | 已实现 |
| 学生注册 | 无自助注册，由教师创建/导入 | 同 | 教师创建接口与导入接口验证通过 | 已实现（教师代为创建） |
| Token 校验 | 64 位十六进制，库中存散列，1 天过期 | 同 | API：非法格式 401、不存在 401、退出后 401 | 已实现 |
| Token 轮换 | 每次登录更换，访问码保持不变 | 同 | API：再次登录 Token 变化、访问码不变 | 已实现 |
| 退出登录 | 写入撤销时间，幂等 | 同 | API：退出 200、重复退出 200、退出后原 Token 401 | 已实现 |
| 长期 API 访问码 | 学生专属，不随退出失效 | 同 | API：退出网页登录后访问码仍 200 | 已实现 |
| 账号状态 | 停用即无法登录且访问码被拒 | 同 | API：停用 403、恢复后 200 | 已实现 |
| 密码重置 | 无（仅新建时固定 123456） | 同 | — | 未实现（参考项目也没有） |

### 4.2 班级、学生与工作空间

| 功能 | 参考项目 | 复现实现 | 验证方式与结果 | 结论 |
| --- | --- | --- | --- | --- |
| 班级增删改查 | 有 | 有 | API 全流程 + 浏览器列表/编辑 | 已实现 |
| 班级启停联动学生与空间 | 有 | 有 | API：停用班级后学生登录 403、访问码 403；恢复后 200 | 已实现 |
| 删除班级级联删除学生、凭证、空间与项目数据 | 有 | 有 | API：删除学生后访问码失效、空间 404 | 已实现 |
| 学生账号创建 | 有，初始密码 123456 | 同 | API + 浏览器 | 已实现 |
| 学生批量导入 | 有，xls/xlsx，≤1000 行，逐行结果 | 同 | API：4 行 → 成功 2 / 跳过 1 / 失败 1；浏览器同 | 已实现 |
| 导入文件校验 | 表头、文本编号、15 位限制、非 Excel 拒绝 | 同 | API：非 Excel 400；浏览器真实上传 | 已实现 |
| 每名学生独立 workspace | 有 | 有 | API：两名学生的 workspaceId 不同、模拟用户主键不重叠 | 已实现 |
| 空间启停 | 有，启用前校验账号/角色/班级 | 同 | API：暂停后访问码 403、恢复后 200 | 已实现 |
| 按班级初始化项目数据 | 有，已有数据则跳过 | 同 | API：初始化后 4 条活动 / 6 条工单；重复初始化跳过 | 已实现 |
| 按项目重置 | TICKET / REPAIR / ALL | 同 | API：重置只清空指定项目，另一项目与另一学生不受影响 | 已实现 |

### 4.3 校园抢票

| 功能 | 参考项目 | 复现实现 | 验证方式与结果 | 结论 |
| --- | --- | --- | --- | --- |
| 模拟用户查询 / 身份切换 | 有 | 有 | API：列表、按 userNo 详情、切换、停用身份 403 | 已实现 |
| 活动查询与筛选 | campus / status / keyword | 同 | API：非法参数 400、正常 200；浏览器列表 | 已实现 |
| 创建 / 修改活动 | 仅管理员，时间顺序校验 | 同 | API：非管理员 403、时间倒置 400、非法校区 400、名额 0 → 400 | 已实现 |
| 活动状态流转 | 0→1→2，幂等，非法流转拒绝 | 同 | API：发布、重复发布幂等、关闭、已关闭不可修改 | 已实现 |
| 报名开始后禁止改时间 | 有 | 有 | API：报名已开始的已发布活动改时间 409 | 已实现 |
| 删除活动 | 仅草稿且无报名记录 | 同 | API：草稿删除 200；已发布 409 | 已实现 |
| 报名抢票 | 窗口内、未重复、有名额 | 同 | API：成功 200、重复 409、管理员身份 403、未开始 409 | 已实现 |
| 名额一致性 | 报名 +1、取消 -1、核销不变 | 同 | API：报名后 1、核销后仍 1、取消释放 | 已实现 |
| 取消票券 | 仅本人、活动开始前、幂等 | 同 | API：本人 200、他人 403、已核销 409、重复取消 200 | 已实现 |
| 核销票券 | 仅管理员、不可重复 | 同 | API：普通用户 403、管理员 200、重复 409 | 已实现 |
| 票券查询 | 本人 / 管理员、状态与活动筛选 | 同 | API：本人 200、他人 403、名单 1 条 | 已实现 |
| 并发安全 | 工作空间行锁串联 | 同（锁顺序：空间→活动→记录） | 通过代码审查；未做压力测试 | 部分实现（未做并发压测） |

### 4.4 校园设备报修

| 功能 | 参考项目 | 复现实现 | 验证方式与结果 | 结论 |
| --- | --- | --- | --- | --- |
| 模拟用户维护 | 管理员增改删、编号不可改 | 同 | API：创建、重复编号 409、改编号 400、停用后切换 403、有业务关联不可删 409 | 已实现 |
| 设备维护 | 管理员增改与启停、编号不可改 | 同 | API：创建、重复 409、改编号 400、停用 200 | 已实现 |
| 创建工单 | 已登记设备或手填设备，富文本清洗 | 同 | API：正常 200、无有效文字 400、非法校区 400、设备不存在 404 | 已实现 |
| 撤回工单 | 待分派 / 待接单，幂等 | 同 | API：撤回 200、重复 200、已撤销不可确认 409 | 已实现 |
| 派单 | 仅管理员，目标须为师傅，重复派同一人幂等 | 同 | API：报修人 403、非师傅 403、派单 200、改派 200 | 已实现 |
| 接单 | 仅被分派师傅，幂等 | 同 | API：非被分派 403、成功 200、重复 200 | 已实现 |
| 追加维修记录 | 维修中、仅当前师傅 | 同 | API：成功 200、报修人 403、空内容 400 | 已实现 |
| 提交维修结果 | 维修中、必须 1~6 张维修图片 | 同 | API：缺图 400、成功 200（状态 4）、重复提交幂等、复用已绑定图片 409 | 已实现 |
| 退回维修 | 仅原报修人，回到维修中 | 同 | API：成功 200、状态回到 3 | 已实现 |
| 确认完成 | 仅原报修人，幂等 | 同 | API：师傅 403、报修人 200、重复 200、维修中 409 | 已实现 |
| 评价 | 已完成、1~5 分、不可重复 | 同 | API：成功 200、重复 409、评分 6 → 400 | 已实现 |
| 处理记录时间线 | 每次动作追加一条，含分派目标 | 同 | API：CREATE,ASSIGN,ASSIGN,ASSIGN,ACCEPT,RECORD,SUBMIT | 已实现 |
| 工单可见范围 | 管理员全部 / 报修人本人 / 师傅分派或参与 | 同 | API：报修人结果只含本人；ASSIGNED 与 PARTICIPATED 范围差异验证通过 | 已实现 |
| 图片上传 | 按角色限类型，真实解码校验 | 同 | API：师傅上传维修图 200、报修人上传维修图 403、非图片 400 | 已实现 |
| 图片预览 / 下载 | 按工作空间隔离 | 同 | API：200 且返回真实 PNG 头；用他人访问码 404 | 已实现 |
| 临时图片删除 | 仅上传人、未关联 | 同 | API：非上传人 403、上传人 200 | 已实现 |
| 图片文件异步清理 | 任务表 + 定时重试 | 同 | 通过代码审查；重置后文件由后台任务删除 | 部分实现（未逐文件断言） |
| 按工作空间隔离图片目录 | 有 | 同 | 路径为 `{workspaceId}/{yyyyMM}/{uuid}.{ext}` | 已实现 |

### 4.5 接口文档与在线调试

| 功能 | 参考项目 | 复现实现 | 验证 |
| --- | --- | --- | --- |
| 文档数据 | 23 系统 + 15 抢票 + 29 报修 | 直接复用教师提供的同一份数据 | 与实现逐项比对：67/67 一致 |
| 功能分组 | 4 + 6 组 | 同 | 前端单元测试 |
| 搜索、展开 / 折叠 | 有 | 同 | 浏览器验证通过 |
| 参数表单与本地校验 | 依据 JSON Schema | 同 | 浏览器验证：缺参数时提示且不启动冷却 |
| 真实发送请求 | 有 | 有 | 浏览器验证：HTTP 200 且响应含真实数据 |
| 教师只读 | 不显示发送区 | 同 | 浏览器验证通过 |
| 冷却 | 10 秒，仅学生，只读与图片不等待 | 同 | 浏览器验证：写入截止时间、重开页面仍生效 |
| 二进制图片响应 | 识别为图片并可下载 | 同 | 后端接口验证返回真实 PNG |

## 5. 接口对照表（67 个）

下表逐个核对「参考项目接口文档」与「复现实现控制器签名」。
比对方式：从复现实现的控制器源码解析方法与路径，与文档中的 `method + path` 逐项匹配。

<!-- 本表由 scripts 生成逻辑核对：与复现实现的控制器签名逐项比对 -->

### 系统管理（23 个）

| 方法 | 路径 | 摘要 | operationId | 与实现比对 |
| --- | --- | --- | --- | --- |
| GET | `/hello` | 服务连通检查 | `HelloController_hello` | 一致 |
| POST | `/login` | 网页登录 | `SysLoginController_login` | 一致 |
| POST | `/logout` | 退出网页登录 | `SysLoginController_logout` | 一致 |
| GET | `/api/sys-class/all` | 查询全部班级 | `SysClassController_getAllClasses` | 一致 |
| GET | `/api/sys-class/one/{id}` | 查询单个班级 | `SysClassController_getOneClass` | 一致 |
| DELETE | `/api/sys-class/one/{id}` | 删除班级及关联学生 | `SysClassController_deleteClass` | 一致 |
| POST | `/api/sys-class/one` | 创建班级 | `SysClassController_addNewClass` | 一致 |
| PUT | `/api/sys-class/one` | 修改班级名称 | `SysClassController_changeClass` | 一致 |
| PUT | `/api/sys-class/one/{id}/status` | 启用或停用班级 | `SysClassController_changeClassStatus` | 一致 |
| POST | `/api/sys-user/one` | 创建单个系统用户 | `SysUserController_createOneSysUser` | 一致 |
| PUT | `/api/sys-user/one` | 修改系统用户信息 | `SysUserController_changeUser` | 一致 |
| POST | `/api/sys-user/import` | Excel批量导入学生 | `SysUserController_importStudents` | 一致 |
| GET | `/api/sys-user/all` | 查询全部系统用户 | `SysUserController_getAllUsers` | 一致 |
| GET | `/api/sys-user/one/{id}` | 查询单个系统用户 | `SysUserController_getOneUser` | 一致 |
| DELETE | `/api/sys-user/one/{id}` | 删除系统用户 | `SysUserController_deleteUser` | 一致 |
| GET | `/api/sys-user/classes/{id}` | 按班级查询学生 | `SysUserController_getClassesUsers` | 一致 |
| PUT | `/api/sys-user/one/{id}/status` | 启用或停用系统用户 | `SysUserController_changeUserStatus` | 一致 |
| GET | `/api/sys-workspace/classes/{id}` | 按班级查询工作空间 | `SysWorkspaceController_getWorkspacesClassId` | 一致 |
| GET | `/api/sys-workspace/one/{id}` | 按学号查询工作空间 | `SysWorkspaceController_getOneWorkspace` | 一致 |
| PUT | `/api/sys-workspace/one/{id}/status` | 启用或暂停工作空间 | `SysWorkspaceController_changeWorkspaceStatus` | 一致 |
| POST | `/api/sys-workspace/one/{id}/reset` | 重置学生项目数据 | `SysWorkspaceController_resetWorkspace` | 一致 |
| POST | `/api/sys-workspace/classes/{classId}/ticket/initialize` | 按班级初始化抢票数据 | `SysWorkspaceController_initializeTicketClass` | 一致 |
| POST | `/api/sys-workspace/classes/{classId}/repair/initialize` | 按班级初始化维修数据 | `SysWorkspaceController_initializeRepairClass` | 一致 |

### 校园抢票（15 个）

| 方法 | 路径 | 摘要 | operationId | 与实现比对 |
| --- | --- | --- | --- | --- |
| GET | `/api/practice/{accessCode}/ticket/users` | 查询抢票模拟用户列表 | `TicketController_getAllUsers` | 一致 |
| GET | `/api/practice/{accessCode}/ticket/users/{userId}` | 按模拟编号查询抢票用户 | `TicketController_getOneUser` | 一致 |
| POST | `/api/practice/{accessCode}/ticket/users/switch` | 切换抢票模拟用户 | `TicketController_switchUser` | 一致 |
| GET | `/api/practice/{accessCode}/ticket/activities` | 查询抢票活动列表 | `TicketController_getAllActivities` | 一致 |
| GET | `/api/practice/{accessCode}/ticket/activities/{activityId}` | 查询抢票活动详情 | `TicketController_getOneActivity` | 一致 |
| POST | `/api/practice/{accessCode}/ticket/activities` | 创建抢票活动草稿 | `TicketController_saveNewActivity` | 一致 |
| PUT | `/api/practice/{accessCode}/ticket/activities/{activityId}` | 修改抢票活动 | `TicketController_updateActivity` | 一致 |
| PUT | `/api/practice/{accessCode}/ticket/activities/{activityId}/status` | 发布或关闭抢票活动 | `TicketController_changeActivityStatus` | 一致 |
| DELETE | `/api/practice/{accessCode}/ticket/activities/{activityId}` | 删除抢票活动草稿 | `TicketController_deleteActivity` | 一致 |
| POST | `/api/practice/{accessCode}/ticket/activities/{activityId}/records` | 报名抢票 | `TicketController_bookTicket` | 一致 |
| GET | `/api/practice/{accessCode}/ticket/activities/{activityId}/records` | 管理员查询活动报名名单 | `TicketController_getActivityRecords` | 一致 |
| GET | `/api/practice/{accessCode}/ticket/users/{userId}/records` | 查询模拟用户票券 | `TicketController_getUserRecords` | 一致 |
| GET | `/api/practice/{accessCode}/ticket/records/{recordId}` | 查询单张票券 | `TicketController_getRecordDetail` | 一致 |
| PUT | `/api/practice/{accessCode}/ticket/records/{recordId}/cancel` | 取消票券 | `TicketController_cancelTicket` | 一致 |
| PUT | `/api/practice/{accessCode}/ticket/records/{recordId}/verify` | 管理员核销票券 | `TicketController_verifyTicket` | 一致 |

### 校园报修（29 个）

| 方法 | 路径 | 摘要 | operationId | 与实现比对 |
| --- | --- | --- | --- | --- |
| GET | `/api/practice/{accessCode}/repair/users` | 查询维修模拟用户列表 | `RepairController_getAllUsers` | 一致 |
| GET | `/api/practice/{accessCode}/repair/users/{userId}` | 查询维修模拟用户详情 | `RepairController_getOneUser` | 一致 |
| POST | `/api/practice/{accessCode}/repair/users/switch` | 切换维修模拟用户 | `RepairController_switchUser` | 一致 |
| POST | `/api/practice/{accessCode}/repair/users` | 创建维修模拟用户 | `RepairController_createUser` | 一致 |
| PUT | `/api/practice/{accessCode}/repair/users/{userId}` | 修改维修模拟用户 | `RepairController_updateUser` | 一致 |
| DELETE | `/api/practice/{accessCode}/repair/users/{userId}` | 删除维修模拟用户 | `RepairController_deleteUser` | 一致 |
| GET | `/api/practice/{accessCode}/repair/devices` | 查询设备列表 | `RepairController_getAllDevices` | 一致 |
| GET | `/api/practice/{accessCode}/repair/devices/{deviceId}` | 查询设备详情 | `RepairController_getOneDevice` | 一致 |
| POST | `/api/practice/{accessCode}/repair/devices` | 创建设备 | `RepairController_createDevice` | 一致 |
| PUT | `/api/practice/{accessCode}/repair/devices/{deviceId}` | 修改设备 | `RepairController_updateDevice` | 一致 |
| PUT | `/api/practice/{accessCode}/repair/devices/{deviceId}/status` | 启用或停用设备 | `RepairController_changeDeviceStatus` | 一致 |
| GET | `/api/practice/{accessCode}/repair/orders` | 按角色查询工单列表 | `RepairController_getOrders` | 一致 |
| GET | `/api/practice/{accessCode}/repair/orders/{orderId}` | 查询工单详情 | `RepairController_getOrderDetail` | 一致 |
| POST | `/api/practice/{accessCode}/repair/orders` | 创建报修工单 | `RepairController_createOrder` | 一致 |
| PUT | `/api/practice/{accessCode}/repair/orders/{orderId}/cancel` | 撤回工单 | `RepairController_cancelOrder` | 一致 |
| PUT | `/api/practice/{accessCode}/repair/orders/{orderId}/assign` | 管理员派单或改派 | `RepairController_assignOrder` | 一致 |
| PUT | `/api/practice/{accessCode}/repair/orders/{orderId}/accept` | 维修师傅接单 | `RepairController_acceptOrder` | 一致 |
| PUT | `/api/practice/{accessCode}/repair/orders/{orderId}/submit` | 提交维修结果 | `RepairController_submitOrder` | 一致 |
| PUT | `/api/practice/{accessCode}/repair/orders/{orderId}/confirm` | 报修人确认完成 | `RepairController_confirmOrder` | 一致 |
| PUT | `/api/practice/{accessCode}/repair/orders/{orderId}/return` | 报修人退回维修 | `RepairController_returnOrder` | 一致 |
| GET | `/api/practice/{accessCode}/repair/orders/{orderId}/process-records` | 查询工单处理时间线 | `RepairController_getProcessRecords` | 一致 |
| POST | `/api/practice/{accessCode}/repair/orders/{orderId}/process-records` | 追加维修过程记录 | `RepairController_addProcessRecord` | 一致 |
| POST | `/api/practice/{accessCode}/repair/images` | 上传故障或维修图片 | `RepairController_uploadImage` | 一致 |
| GET | `/api/practice/{accessCode}/repair/orders/{orderId}/images` | 查询工单图片 | `RepairController_getOrderImages` | 一致 |
| GET | `/api/practice/{accessCode}/repair/images/{imageId}/preview` | 预览图片 | `RepairController_previewImage` | 一致 |
| GET | `/api/practice/{accessCode}/repair/images/{imageId}/download` | 下载图片 | `RepairController_downloadImage` | 一致 |
| DELETE | `/api/practice/{accessCode}/repair/images/{imageId}` | 删除临时图片 | `RepairController_deleteTemporaryImage` | 一致 |
| POST | `/api/practice/{accessCode}/repair/orders/{orderId}/evaluation` | 创建工单评价 | `RepairController_evaluateOrder` | 一致 |
| GET | `/api/practice/{accessCode}/repair/orders/{orderId}/evaluation` | 查询工单评价 | `RepairController_getEvaluation` | 一致 |

## 6. 测试结果汇总

| 验证方式 | 脚本 | 断言数 | 结果 |
| --- | --- | --- | --- |
| 后端纯逻辑单元测试 | `backend/src/test/java/.../SecurityAndContentUtilsTests.java` | 9 | 通过 9，失败 0 |
| 前端契约单元测试 | `frontend/tests/contracts.test.mjs` | 7 | 通过 7，失败 0 |
| 接口 / 权限 / 隔离 / 重置 | `scripts/api-verify.sh` | 246 | 通过 246，失败 0 |
| 浏览器端到端 | `frontend/tests/browser.mjs` | 56 | 通过 56，失败 0 |
| 合计 | — | **318** | **全部通过** |

结论文件：`docs/verification-api.txt`、`docs/verification-browser.txt`、`docs/verification-browser.json`。

## 7. 未完成与本轮范围外

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 任务发布 | 未实现 | 按任务要求留到下一阶段 |
| 成果提交 | 未实现 | 同上 |
| 自动评分 | 未实现 | 同上 |
| AI 辅导 | 未实现 | 同上 |
| 密码自助重置 / 修改 | 未实现 | 参考项目也不具备 |
| 分页查询 | 未实现 | 参考项目接口不分页，前端在已加载数据内分页 |
| 并发压力测试 | 未做 | 已通过行锁设计与代码审查确认并发策略，但未做压测 |
| 图片异步清理的逐文件断言 | 未做 | 任务表机制已实现，重置后文件删除未逐文件断言 |
| 线上 / 生产环境部署 | 未做 | 本轮只做本机验证，未开放公网端口 |
