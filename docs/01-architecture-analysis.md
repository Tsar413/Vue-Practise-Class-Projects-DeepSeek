# 原项目架构与业务分析

> 本文档基于对参考项目源码的实际阅读，commit 见上（`c0f9aa319cec03c0e32849118cae26e76859c701`）。
> 文中所有结论均来自逐文件读取的源码、`pom.xml`、`package.json`、`application.yml` 与前端 JSON 接口清单，未使用推测填充。
> 凡源码中无法确认的内容，均显式标注为「未能从源码确认」。参考项目内的模拟数据全部为虚构数据，本文不记录任何真实学生个人信息。

---

## 1. 参考项目基准

### 1.1 仓库基准信息

| 项目 | 值 | 来源 |
| --- | --- | --- |
| 参考源仓库（只读） | `/home/tsar413/projects/reference/Vue-Practise-Class-Projects` | 本地路径 |
| 远端地址 | `https://github.com/Tsar413/Vue-Practise-Class-Projects.git` | `git remote -v`（origin） |
| 分支 | `main` | `git branch --show-current` |
| commit ID | `c0f9aa319cec03c0e32849118cae26e76859c701` | `git rev-parse HEAD` |
| 受版本控制的文件总数 | **154** | `git ls-files \| wc -l` |
| 复现成果仓库 | `/home/tsar413/projects/Vue-Practise-Class-Projects-DeepSeek` | 本地路径 |

文件构成分布：

| 位置 | 文件数 | 说明 |
| --- | --- | --- |
| `backend/` | 101 | 98 个 `.java` + `pom.xml` + `src/main/resources/application.yml` + `study-vue-practise-backend.iml` |
| `frontend/` | 52 | 41 个位于 `frontend/src/`，其余为 `package.json`、`package-lock.json`、`index.html`、`vite.config.js`、`.env.example`、`tests/` 等 |
| 仓库根 | 1 | `.gitignore` |
| **合计** | **154** | |

> 说明：仓库根目录只有 `.gitignore`，**没有** README，也**没有**任何 `.sql` 建表脚本或数据库迁移目录。数据库表结构由 JPA 的 `spring.jpa.hibernate.ddl-auto: update` 配合实体类上的 `@Table` / `@Column`（以及 MyBatis-Plus 的 `@TableName` / `@TableField`）共同生成与维护。

### 1.2 后端技术栈（版本来自 `backend/pom.xml`）

| 组件 | 版本 / 坐标 | 说明 |
| --- | --- | --- |
| Spring Boot | `spring-boot-starter-parent` **4.1.1** | 父 POM，统一管理多数依赖版本 |
| Java | **21** | `<java.version>21</java.version>` |
| Maven 坐标 | `com.study:study-vue-practise-backend:0.0.1-SNAPSHOT`，`packaging=jar` | `<finalName>vue-practice-backend</finalName>` |
| Spring Web | `spring-boot-starter-web` | 版本由父 POM 管理 |
| Spring Data JPA | `spring-boot-starter-data-jpa` | 负责 DDL 自动更新 |
| MyBatis-Plus | `mybatis-plus-spring-boot4-starter` **3.5.16** | 实际增删改查入口 |
| MyBatis-Plus SQL 解析 | `mybatis-plus-jsqlparser` **3.5.16** | 分页插件所需的 SQL 解析依赖 |
| MySQL 驱动 | `com.mysql:mysql-connector-j`（`runtime`） | 未显式写版本，由父 POM 管理 |
| Thymeleaf | `spring-boot-starter-thymeleaf` | 模板前缀 `classpath:/views/` |
| Spring Mail | `spring-boot-starter-mail` | 在 `pom.xml` 中**声明了两次**（第 74、98 行） |
| Spring Data Redis | `spring-boot-starter-data-redis` | 见下方说明 |
| 密码学工具 | `cn.hutool:hutool-crypto` **5.8.26** | 项目自身鉴权实际使用 JDK `MessageDigest` |
| Lombok | `org.projectlombok:lombok` **1.18.42**（`provided`） | 实体类 `@Data` |
| HTML 清洗 | `org.jsoup:jsoup` **1.23.2** | 富文本处理 |
| WebP 图片支持 | `com.twelvemonkeys.imageio:imageio-webp` **3.12.0** | 图片处理 |
| Excel 解析 | `org.apache.poi:poi-ooxml` **5.5.1** | 学生批量导入 |
| 测试 | `spring-boot-starter-test`、`junit:junit`（均 `test`） | `src/test` 下只有 1 个测试类 |
| 热部署 | `spring-boot-devtools`（`runtime`、`optional`） | 开发期使用 |

> **关于 Redis**：`pom.xml` 引入了 `spring-boot-starter-data-redis`，但 `application.yml` **没有**任何 `spring.data.redis` 配置项，且全部 Java 源码与配置文件中**检索不到** `RedisTemplate`、`StringRedisTemplate`、`@Cacheable` 或 `redis` 字样。本项目的登录凭证并未存放于 Redis，而是存放在 MySQL 的 `sys_login_token` 表。该依赖是否为预留，**未能从源码确认**。

### 1.3 后端运行配置（版本来自 `backend/src/main/resources/application.yml`）

| 配置项 | 值 |
| --- | --- |
| 服务端口 | `8100`（`server.port`） |
| 数据源驱动 | `com.mysql.cj.jdbc.Driver` |
| JDBC URL | `jdbc:mysql://127.0.0.1:3306/vue_practise_backend?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8&serverTimezone=GMT%2B8` |
| 数据库账号 / 密码 | 均为**空值**（`username:` 与 `password:` 后无内容） |
| JPA DDL 策略 | `ddl-auto: update`，`show-sql: true`，`open-in-view: false` |
| 上传体积限制 | `max-file-size: 5MB`，`max-request-size: 6MB` |
| MyBatis-Plus | `map-underscore-to-camel-case: true`，`log-impl: StdOutImpl` |
| 报修图片存储根目录 | `repair.files.root: /var/lib/vue-practice/repair-files`（注释中另有 `./repair-files` 备选） |
| CORS 允许来源 | `http://localhost:5173`、`http://127.0.0.1:5173`、`http://localhost:4173`、`http://127.0.0.1:4173` |

### 1.4 前端技术栈（版本来自 `frontend/package.json`）

`package.json` 声明：`name = vue-practice-platform`，`version = 1.0.0`，`private = true`，`type = module`。

| 依赖 | `package.json` 声明范围 | `package-lock.json` 锁定版本 |
| --- | --- | --- |
| `vue` | `^3.5.0` | 3.5.43 |
| `vue-router` | `^4.5.0` | 4.6.4 |
| `pinia` | `^3.0.0` | 3.0.4 |
| `axios` | `^1.8.0` | 1.20.0 |
| `marked` | `^15.0.0` | 15.0.12 |
| `dompurify` | `^3.2.0` | 3.4.16 |
| `vite`（dev） | `^7.0.0` | 7.3.7 |
| `@vitejs/plugin-vue`（dev） | `^6.0.0` | 6.0.9 |
| `@playwright/test`（dev） | `^1.55.0` | 1.63.0 |

> `package.json` 中使用的是 `^` 范围；上表第三列为 `package-lock.json`（`lockfileVersion: 3`，共 133 个条目）中记录的锁定版本。

脚本与运行参数：

| 项 | 值 | 来源 |
| --- | --- | --- |
| `npm run dev` | `vite --host 0.0.0.0` | `frontend/package.json` |
| `npm run build` | `vite build` | 同上 |
| `npm run preview` | `vite preview --host 0.0.0.0` | 同上 |
| `npm test` | `node --test tests/*.test.mjs` | 同上 |
| 开发端口 | 5173（`strictPort: true`） | `frontend/vite.config.js` |
| 预览端口 | 4173（`strictPort: true`） | 同上 |
| 前端环境变量 | `VITE_API_BASE_URL=http://localhost:8100` | `frontend/.env.example` |

前端启动时若 `VITE_API_BASE_URL` 未配置或不是完整的 `http(s)://` 地址，`frontend/src/api/client.js` 会直接抛出 `请配置完整的后端地址 VITE_API_BASE_URL`，即**不使用 Vite 代理**，由浏览器直连后端。

---

## 2. 后端目录结构与包职责

实际的包路径为 `backend/src/main/java/com/study/vuePractiseBackend/`，各目录文件数与职责如下（文件数由实际目录统计）：

| 目录 / 包 | 文件数 | 职责 |
| --- | --- | --- |
| 根包（默认包） | 3 | `StudyVuePractiseBackendApplication.java`（`@SpringBootApplication` 启动类）、`ServletInitializer.java`（`SpringBootServletInitializer` 子类，支持打 WAR 部署）、`HelloController.java`（`GET /hello`，返回纯文本 `Hello Spring Boot!`） |
| `common/` | 1 | `Result.java`：统一响应包装 `{code, message, data}`，静态工厂 `success` / `error` / `unauthorized` / `badRequest` |
| `config/` | 3 | `WebMvcConfig.java`（CORS 与两个拦截器的注册和顺序）、`RepairFileProperties.java`（`@ConfigurationProperties("repair.files")`，图片根目录，默认 `./repair-files`）、`RepairSchedulingConfig.java`（`@EnableScheduling`） |
| `controller/` | 6 | 系统侧：`SysLoginController`、`SysClassController`、`SysUserController`、`SysWorkspaceController`；业务侧：`TicketController`（`/api/practice/{accessCode}/ticket`）、`RepairController`（`/api/practice/{accessCode}/repair`） |
| `dto/` | 16 | 入参/回参对象。系统侧如 `SysLoginDTO`、`SysLoginReturnDTO`、`SysUserDTO`、`SysClassDTO`、`StudentImportResultDTO`；抢票侧 `TicketActivityDTO`、`TicketActivityUpdateDTO`、`TicketActivityStatusDTO`；报修侧 `RepairAssignDTO`、`RepairDeviceDTO`、`RepairEvaluationDTO`、`RepairOrderCreateDTO`、`RepairProcessDTO`、`RepairReturnDTO`、`RepairSubmitDTO`、`RepairUserDTO` |
| `entity/` | 15 | 15 个实体，与 15 张数据表一一对应，详见第 3 节 |
| `exception/` | 3 | `BusinessExceptions.java`（静态工厂，产出 `ResponseStatusException` 的 404/403/409）、`ExcelImportException.java`（导入校验异常）、`GlobalExceptionHandler.java`（`@RestControllerAdvice`，把路由错误、参数错误、唯一键冲突、约束冲突、业务状态异常、上传超限等统一转成 `Result`，且保持 HTTP 状态码与 `code` 一致） |
| `interceptor/` | 2 | `SysLoginInterceptor.java`（网页登录凭证与角色权限）、`ApiAccessInterceptor.java`（学生长期访问码 → 工作空间） |
| `mapper/` | 15 | 15 个 MyBatis-Plus `BaseMapper` 接口，与实体一一对应。含 `SysWorkspaceMapper`（额外定义 `selectByStudentIdForUpdate`，用行锁串行化重置/初始化/删除） |
| `service/` | 11 | 服务接口：`SysLoginService`、`SysClassService`、`SysUserService`、`SysWorkspaceService`、`TicketUserService`、`TicketActivityService`、`TicketRecordService`、`RepairUserService`、`RepairDeviceService`、`RepairOrderService`、`RepairAttachmentService` |
| `service/impl/` | 12 | 上述 11 个接口的实现，外加 `RepairFileCleanupService`（图片异步清理任务，带 `@Scheduled`） |
| `util/` | 7 | `TokenUtil.java`（随机 Token 与 SHA-256 哈希）、`PasswordUtil.java`（随机盐与密码哈希）、`TicketMockDataUtil.java` / `RepairMockDataUtil.java`（初始化模拟数据）、`RepairFileUtil.java`、`RepairContentUtil.java`（富文本清洗）、`StudentExcelUtil.java`（Excel 解析） |
| `vo/` | 3 | `RepairImageVO`、`RepairImageResource`（图片响应资源）、`RepairOrderDetailVO`（工单详情，附带报修人/维修人姓名） |
| `resources/` | 1 | `application.yml` |
| `src/test/java/...` | 1 | `VuePractiseBackendStudyApplicationTests.java` |

后端 Java 文件合计 **98** 个（含测试 1 个）。

---

## 3. 数据库结构（15 张表）

表名与字段均由 `backend/src/main/java/com/study/vuePractiseBackend/entity/*.java` 逐一读取确认。类型依实体上的 `@Column` / `@TableField` 与 Java 类型推定；`workspace_id` 列表示该表是否带学生工作空间外键。

### 3.1 系统与鉴权域（5 张表）

| # | 表名 | 用途 | 关键字段 | `workspace_id` |
| --- | --- | --- | --- | --- |
| 1 | `sys_user` | 系统账号（教师与学生共表，靠 `role` 区分） | `id`（String 主键，`IdType.INPUT`，学生即学号）、`username`、`real_name`、`class_id`、`role`（`STUDENT`/`TEACHER`）、`password_hash`、`password_salt`、`password_algorithm`、`status`（1 启用 / 0 停用）、`last_login_time`、`create_time`、`update_time`。密码三字段带 `@JsonIgnore` | 否 |
| 2 | `sys_class` | 班级 | `id`（String 主键，`IdType.INPUT`）、`class_name`、`status`（1 启用 / 0 停用）、`create_time`、`update_time` | 否 |
| 3 | `sys_workspace` | 学生实训工作空间，每名学生一条 | `id`（Long 自增）、`student_id`（**唯一**，指向 `sys_user.id`）、`status`（1 启用 / 0 暂停）、`create_time`、`update_time` | 否（本身即工作空间根） |
| 4 | `sys_login_token` | 网页登录 Token 与学生长期 API 访问码 | `id`（Long 自增）、`user_id`（**唯一**，指向 `sys_user.id`，即一人一行）、`token_hash`（**唯一**，长度 64，SHA-256 十六进制）、`api_access_code`（**唯一**，长度 64，**原值存放**）、`create_time`（每次登录刷新）、`expire_time`、`revoke_time`（未撤销时为空） | 否 |
| 5 | `repair_file_delete_task` | 报修图片文件的异步删除任务队列（随业务事务提交，后台重试） | `id`（Long 自增）、`image_url`（长度 500）、`create_time` | 否 |

### 3.2 抢票项目域（3 张表）

| # | 表名 | 用途 | 关键字段 | `workspace_id` |
| --- | --- | --- | --- | --- |
| 6 | `ticket_user` | 抢票项目的**模拟业务用户**（非系统账号） | `id`（Long 自增）、`user_no`、`real_name`、`workspace_id`、`phone`、`department`、`campus`、`role`（`USER`/`ADMIN`）、`status`（1 启用 / 0 停用） | **是** |
| 7 | `ticket_activity` | 抢票活动 | `id`（Long 自增）、`activity_name`、`workspace_id`、`description`（`LONGTEXT`）、`cover_url`、`campus`、`location`、`status`（0 草稿 / 1 已发布 / 2 已关闭）、`quota`、`booked_count`、`booking_start_time`、`booking_end_time`、`activity_start_time`、`activity_end_time` | **是** |
| 8 | `ticket_record` | 报名记录 / 票券 | `id`（Long 自增）、`workspace_id`、`activity_id`、`user_id`（指向 `ticket_user.id`）、`ticket_no`（长度 64，形如 `TP` + UUID）、`status`（0 已取消 / 1 有效 / 2 已核销）、`booking_time`、`cancel_time`、`verify_time`、`verify_user_id`（核销管理员，指向 `ticket_user.id`）、`create_time`、`update_time` | **是** |

### 3.3 报修项目域（7 张表）

| # | 表名 | 用途 | 关键字段 | `workspace_id` |
| --- | --- | --- | --- | --- |
| 9 | `repair_user` | 报修项目的**模拟业务用户** | `id`（Long 自增）、`workspace_id`、`user_no`（同一空间内不重复）、`real_name`、`phone`、`department`、`role`（`REPORTER` 报修人 / `MAINTAINER` 维修人员 / `ADMIN` 项目管理员）、`status`（1 正常 / 0 停用）、`create_time`、`update_time` | **是** |
| 10 | `repair_device` | 设备台账 | `id`（Long 自增）、`workspace_id`、`device_no`（同一空间内不重复）、`device_name`、`device_type`、`campus`、`location`、`status`（1 在用 / 0 停用，**不表示工单进度**）、`remark`、`create_time`、`update_time` | **是** |
| 11 | `repair_order` | 报修工单 | `id`（Long 自增）、`workspace_id`、`order_no`（长度 64，形如 `BX` + UUID，同一空间内唯一）、`device_id`（可空，未登记设备允许为空）、`device_name`、`device_type`、`location`、`title`、`description`（`TEXT`）、`reporter_id`、`contact_phone`、`maintainer_id`（未分派为空）、`status`（0~5，见第 7 节）、`campus`、`repair_result`（`TEXT`，最近一次提交的维修结果）、`completed_time`、`cancel_time`、`create_time`、`update_time` | **是** |
| 12 | `repair_process_record` | 工单处理记录 / 时间线 | `id`（Long 自增）、`workspace_id`、`order_id`、`operator_id`、`action`（长度 30，取值见第 7 节）、`from_status`（创建时为空）、`to_status`、`target_user_id`（分派目标维修人员，其他操作为空）、`content`（`TEXT`，维修说明 / 退回原因等）、`create_time`、`update_time` | **是** |
| 13 | `repair_order_image` | 工单与图片的关联（已绑定图片） | `id`（Long 自增）、`workspace_id`、`order_id`、`image_url`（长度 500，存储根目录下的相对路径）、`image_type`（1 故障图片 / 2 维修结果图片）、`sort_order`（展示顺序）、`uploader_id`、`attachment_id`、`process_record_id`（可空）、`create_time`、`update_time` | **是** |
| 14 | `repair_attachment` | 图片附件（上传暂存 → 绑定后转已关联） | `id`（Long 自增）、`workspace_id`、`uploader_id`、`image_type`（1 故障图片 / 2 维修图片）、`image_url`、`original_name`、`content_type`、`file_size`、`status`（0 临时未关联 / 1 已关联）、`create_time`、`update_time` | **是** |
| 15 | `repair_evaluation` | 工单评价 | `id`（Long 自增）、`workspace_id`、`order_id`、`user_id`（评价人）、`score`（1—5，实体注释说明范围在参数校验中限制）、`content`（长度 1000）、`create_time`、`update_time` | **是** |

### 3.4 汇总

- **15 张表**，其中 **10 张业务表带 `workspace_id`**（`ticket_user`、`ticket_activity`、`ticket_record`、`repair_user`、`repair_device`、`repair_order`、`repair_process_record`、`repair_order_image`、`repair_attachment`、`repair_evaluation`）。
- 不带 `workspace_id` 的 5 张是：3 张系统表（`sys_user`、`sys_class`、`sys_workspace`——后者通过 `student_id` 反向关联）、1 张鉴权表（`sys_login_token`）、1 张清理任务表（`repair_file_delete_task`）。
- 空间归属关系链：`sys_user.id` → `sys_workspace.student_id` → 各业务表 `workspace_id`。
- 时间字段普遍使用 `LocalDateTime` 并配 `@JsonFormat`（多为 `yyyy-MM-dd HH:mm:ss`；`TicketActivity` 的四个业务时间额外标注 `timezone = "GMT+8"`）。

---

## 4. 鉴权与权限模型

本项目存在**两套彼此独立的凭证体系**：面向平台网页的短期登录 Token，和面向学生自建 Vue 项目的长期 API 访问码。二者共用 `sys_login_token` 表、同一行记录，但用途、存放方式与失效规则完全不同。

### 4.1 网页登录 Token

| 环节 | 实现 | 源码位置 |
| --- | --- | --- |
| 生成 | `SecureRandom` 取 32 字节，`HexFormat` 转十六进制 → **64 位小写十六进制字符串** | `util/TokenUtil.java#generateToken` |
| 存放（**散列**） | 数据库只存 **SHA-256 哈希**（`token_hash`，64 位十六进制）；原始 Token 仅在登录响应中返回前端一次 | `TokenUtil.hashToken`、`SysLoginServiceImpl` 第 79—81 行 |
| 过期 | 每次登录时 `expireTime = now.plusDays(1)`，即**登录后 1 天**；`create_time` 同时被刷新 | `SysLoginServiceImpl` 第 77—78、198—199 行 |
| 撤销 | `/logout` 按 `token_hash` 匹配且 `revoke_time IS NULL` 时写入 `revoke_time = now`；用户被停用或删除时由 `SysUserServiceImpl` 调用 `revokeByUserId` / `deleteByUserId` | `SysLoginServiceImpl#logout`、`SysUserServiceImpl` 第 301、314 行 |
| 复登 | 因 `user_id` 唯一，同账号重复登录**复用同一行**：换 `token_hash`、保留 `api_access_code`、显式 `set("revoke_time", null)` 清空撤销时间 | `SysLoginServiceImpl#updateLoginToken` |
| 传输 | 请求头 `Authorization: Bearer <原始Token>` | `SysLoginInterceptor` 第 50—58 行 |
| 前端保存 | `sessionStorage`，键名 `vue-practice-session`（关闭标签页即失效） | `frontend/src/api/client.js` 第 5—6 行 |
| 返回值 | 登录响应含 `status`、`userId`、`token`、`realName`、`role`，学生额外含 `apiAccessCode`；**不返回过期时间** | `SysLoginServiceImpl` 第 101—111 行 |

配套的账号密码规则：

- 密码哈希为 `SHA-256(salt + ":" + password)`，盐为 32 字节 `SecureRandom` 的 64 位十六进制字符串；`password_algorithm` 必须等于 `"SHA-256"` 才参与校验；比较使用 `MessageDigest.isEqual`（常量时间比较）。见 `util/PasswordUtil.java` 与 `SysLoginServiceImpl#matchesPassword`。
- 登录失败按 `status` 负值区分：`-2` 参数为空、`-3` 超长（>50）、`-4` 账号或密码错误（账号不存在与密码错误**统一处理**，不泄露账号是否存在）、`-5` 账号已停用、`-6` 班级不存在或已停用、`-7` 角色异常。`SysLoginController` 将其映射为 400/401/403，登录成功响应带 `Cache-Control: no-store`。
- 新建账号（单个创建或 Excel 导入）初始密码为 `123456`，见 `SysUserServiceImpl` 第 95 行。

### 4.2 学生长期 API 访问码

| 特征 | 实现 | 源码位置 |
| --- | --- | --- |
| 生成 | 同样调用 `TokenUtil.generateToken()`，得到 64 位小写十六进制字符串 | `SysLoginServiceImpl` 第 85 行 |
| 生成条件 | **仅当角色为 `STUDENT` 且当前访问码为空/空白**时生成；教师不生成 | 同上第 84 行 |
| 存放（**原值**） | 直接以明文原值存入 `sys_login_token.api_access_code`（唯一约束），**不做任何哈希** | 实体 `SysLoginToken` 第 37—39 行 |
| 查询方式 | 拦截器按**原值**等值查询：`.eq("api_access_code", accessCode)` | `ApiAccessInterceptor` 第 67—69 行 |
| 不随退出失效 | 复登时 `api_access_code` 被原样保留回写；`ApiAccessInterceptor` 中**显式注释**「不检查 expireTime、revokeTime。这两个字段控制网页登录Token，网页退出或登录过期不影响长期API访问码」 | `SysLoginServiceImpl` 第 83、197 行；`ApiAccessInterceptor` 第 75—79 行 |
| 传递方式 | 作为路径变量出现在 URL 中：`/api/practice/{accessCode}/{ticket\|repair}/...` | `TicketController` / `RepairController` 的 `@RequestMapping` |

`ApiAccessInterceptor` 的完整校验链（任一失败即以 `{"code":...,"message":...,"data":null}` 拒绝，并带 `Cache-Control: no-store`）：

1. 从 Spring 匹配到的路径变量中取 `accessCode`，必须匹配 `[0-9a-f]{64}`，否则 401「API访问码格式错误」/「缺少API访问码」；
2. 按原值查 `sys_login_token`，查不到 → 401「API访问码无效」；
3. **跳过** `expireTime` 与 `revokeTime` 检查；
4. 取 `sys_user`：不存在 → 403；角色**必须为 `STUDENT`**（教师持访问码访问会被拒）→ 403「访问码所属账号不是学生」；
5. `status` 必须为 1 → 403「学生账号已停用」；
6. `class_id` 非空且对应 `sys_class` 存在、`status = 1` → 否则 403；
7. 按 `sys_workspace.student_id = user.id` 查工作空间：不存在 → 403「工作空间不存在」；`status != 1` → 403「工作空间已暂停」；
8. 把 `workspace.getId()` 与 `user.getId()` 写入**本次请求的属性**（`practiceWorkspaceId`、`practiceStudentId`），源码注释明确「只在当前请求中保存，不使用全局变量或ThreadLocal」。

### 4.3 角色权限边界（教师 / 学生）

**系统侧（Bearer Token，`SysLoginInterceptor`）**

| 角色 | 允许范围 |
| --- | --- |
| `TEACHER` | `hasPermission()` 直接 `return true`，即**所有系统接口全部放行**，且不校验班级归属 |
| `STUDENT` | 仅允许三个接口，且路径中的 `{id}` 必须**等于本人账号 id**：`GET /api/sys-user/one/{id}`、`GET /api/sys-workspace/one/{id}`、`POST /api/sys-workspace/one/{id}/reset`。其余一律 403「无权执行此操作」 |
| 其他角色 | 第 114 行兜底：既非 `STUDENT` 也非 `TEACHER` → 403「账号角色异常」 |

学生角色的校验顺序（`SysLoginInterceptor#preHandle`）：Token 格式 → 哈希查表 → `revoke_time` 是否为空 → `expire_time` 是否晚于**后端当前时间** → 用户存在 → `user.status == 1` → 若是 `STUDENT` 则 `class_id` 非空且班级存在且 `sys_class.status == 1` → 接口级权限与数据归属。接口模板取自 `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`，路径变量取自 `HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE`（即按**实际匹配到的接口模板**判定，而非按原始 URL 字符串），`/error`、`/hello`、`/login`、`/logout` 与 `/api/practice/**` 被排除在校验之外。

**业务侧（访问码，`ApiAccessInterceptor` + 服务层）**

访问码已经隐含了「哪个学生的哪个空间」，因此业务侧的权限不再看系统角色，而看**空间内的模拟业务角色**：

| 项目 | 模拟角色 | 服务层约束 |
| --- | --- | --- |
| 抢票 | `USER` | 只能报名（`bookTicket`）与取消（`cancelTicket`）；`requireUser(...,"USER")` 强制角色；取消时还要求 `record.userId == userId`，即「只能取消本人的票券」 |
| 抢票 | `ADMIN` | 创建/修改/发布/关闭/删除活动、核销票券、查询活动报名名单；`checkAdmin` 要求该 `ticket_user` 属于**当前 workspace** 且 `role = ADMIN` 且 `status = 1` |
| 报修 | `REPORTER` | 创建工单；撤回**本人**工单；确认完成、退回、评价均要求是**原报修人**（`checkOriginalReporter`） |
| 报修 | `MAINTAINER` | 仅**当前被分派**的师傅可接单、追加维修记录、提交结果（`checkAssignedMaintainer`）；列表可按 `scope=ASSIGNED/PARTICIPATED` 过滤，后者表示曾作为 `operator_id` 或 `target_user_id` 出现在处理记录中 |
| 报修 | `ADMIN` | 派单/改派；工单列表不追加用户归属条件，可查询当前空间全部工单；查看详情/处理记录/评价时不设归属限制 |
| 报修 | 任意 | `getOrders` 中 `REPORTER` 只能看自己提交的、`MAINTAINER` 默认只能看分派给自己的；`checkViewPermission` 允许 ADMIN 全看、REPORTER 看本人的、MAINTAINER 看分派给本人或曾参与处理的，其余 403「无权查看此工单」 |

**两拦截器的分工与边界**

| 维度 | `SysLoginInterceptor` | `ApiAccessInterceptor` |
| --- | --- | --- |
| 注册路径 | `/**`，排除 `/login`、`/logout`、`/error`、`/hello`、`/api/practice/**` | `/api/practice/**` |
| 执行顺序 | `order(1)` | `order(0)`（先执行） |
| 凭证 | `Authorization: Bearer <64位Token>` | URL 路径变量 `{accessCode}` |
| 校验数据 | `sys_login_token.token_hash` | `sys_login_token.api_access_code` |
| 检查过期/撤销 | **检查** `expire_time`、`revoke_time` | **不检查** |
| 角色来源 | `sys_user.role`（`TEACHER`/`STUDENT`） | 要求 `sys_user.role == STUDENT`，业务角色另取自 `ticket_user` / `repair_user` |
| 产物 | 请求属性 `loginUserId`、`loginUserRole` | 请求属性 `practiceWorkspaceId`、`practiceStudentId` |
| 放行预检 | 是（`CorsUtils.isPreFlightRequest`） | 是 |

由于两条路径模式**完全不重叠**，系统接口只认 Token、业务接口只认访问码，学生自建的 Vue 项目无需（也不应）携带网页 Token，平台网页调用业务接口时同样只靠 URL 里的访问码。控制器通过 `@RequestAttribute(ApiAccessInterceptor.WORKSPACE_ID) Long workspaceId` 注入，服务层无法自行指定工作空间。

CORS 由 `WebMvcConfig#addCorsMappings` 统一配置，作用于 `/**`，允许 `GET/POST/PUT/DELETE/OPTIONS`，允许请求头 `Authorization`、`Content-Type`、`Accept`，暴露响应头 `Content-Disposition`、`Content-Type`，`allowCredentials(false)`，`maxAge(3600)`。允许来源取自 `app.cors.allowed-origins`（默认 5173 / 4173 的 localhost 与 127.0.0.1）。

---

## 5. 接口清单

以下三张清单**由 python3 直接读取前端 JSON 契约文件生成**，未经人工誊抄。为避免破坏 Markdown 表格，摘要中的换行与竖线已做转义处理。

- `frontend/src/data/system-spec.json`：**23** 个
- `frontend/src/data/ticket/*/operations.json`：**15** 个（`users` 3 + `activities` 6 + `registration` 2 + `tickets` 4，与 `frontend/src/data/ticket/index.js` 的展开顺序一致）
- `frontend/src/data/repair/*/operations.json`：**29** 个（`users` 6 + `devices` 5 + `orders` 9 + `process` 2 + `images` 5 + `evaluation` 2，与 `frontend/src/data/repair/index.js` 一致）

### 5.1 系统与账号接口（`frontend/src/data/system-spec.json`，共 23 个）

**公开与登录**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `GET` | `/hello` | 服务连通检查 | `HelloController_hello` |
| `POST` | `/login` | 网页登录 | `SysLoginController_login` |
| `POST` | `/logout` | 退出网页登录 | `SysLoginController_logout` |

**班级管理**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `GET` | `/api/sys-class/all` | 查询全部班级 | `SysClassController_getAllClasses` |
| `GET` | `/api/sys-class/one/{id}` | 查询单个班级 | `SysClassController_getOneClass` |
| `DELETE` | `/api/sys-class/one/{id}` | 删除班级及关联学生 | `SysClassController_deleteClass` |
| `POST` | `/api/sys-class/one` | 创建班级 | `SysClassController_addNewClass` |
| `PUT` | `/api/sys-class/one` | 修改班级名称 | `SysClassController_changeClass` |
| `PUT` | `/api/sys-class/one/{id}/status` | 启用或停用班级 | `SysClassController_changeClassStatus` |

**系统用户**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `POST` | `/api/sys-user/one` | 创建单个系统用户 | `SysUserController_createOneSysUser` |
| `PUT` | `/api/sys-user/one` | 修改系统用户信息 | `SysUserController_changeUser` |
| `POST` | `/api/sys-user/import` | Excel批量导入学生 | `SysUserController_importStudents` |
| `GET` | `/api/sys-user/all` | 查询全部系统用户 | `SysUserController_getAllUsers` |
| `GET` | `/api/sys-user/one/{id}` | 查询单个系统用户 | `SysUserController_getOneUser` |
| `DELETE` | `/api/sys-user/one/{id}` | 删除系统用户 | `SysUserController_deleteUser` |
| `GET` | `/api/sys-user/classes/{id}` | 按班级查询学生 | `SysUserController_getClassesUsers` |
| `PUT` | `/api/sys-user/one/{id}/status` | 启用或停用系统用户 | `SysUserController_changeUserStatus` |

**工作空间**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `GET` | `/api/sys-workspace/classes/{id}` | 按班级查询工作空间 | `SysWorkspaceController_getWorkspacesClassId` |
| `GET` | `/api/sys-workspace/one/{id}` | 按学号查询工作空间 | `SysWorkspaceController_getOneWorkspace` |
| `PUT` | `/api/sys-workspace/one/{id}/status` | 启用或暂停工作空间 | `SysWorkspaceController_changeWorkspaceStatus` |
| `POST` | `/api/sys-workspace/one/{id}/reset` | 重置学生项目数据 | `SysWorkspaceController_resetWorkspace` |
| `POST` | `/api/sys-workspace/classes/{classId}/ticket/initialize` | 按班级初始化抢票数据 | `SysWorkspaceController_initializeTicketClass` |
| `POST` | `/api/sys-workspace/classes/{classId}/repair/initialize` | 按班级初始化维修数据 | `SysWorkspaceController_initializeRepairClass` |

系统与账号接口合计：**23** 个。

### 5.2 抢票项目接口（`frontend/src/data/ticket/*/operations.json`）

**用户管理（`ticket/users/operations.json`，3 个）**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `GET` | `/api/practice/{accessCode}/ticket/users` | 查询抢票模拟用户列表 | `TicketController_getAllUsers` |
| `GET` | `/api/practice/{accessCode}/ticket/users/{userId}` | 按模拟编号查询抢票用户 | `TicketController_getOneUser` |
| `POST` | `/api/practice/{accessCode}/ticket/users/switch` | 切换抢票模拟用户 | `TicketController_switchUser` |

**活动管理（`ticket/activities/operations.json`，6 个）**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `GET` | `/api/practice/{accessCode}/ticket/activities` | 查询抢票活动列表 | `TicketController_getAllActivities` |
| `GET` | `/api/practice/{accessCode}/ticket/activities/{activityId}` | 查询抢票活动详情 | `TicketController_getOneActivity` |
| `POST` | `/api/practice/{accessCode}/ticket/activities` | 创建抢票活动草稿 | `TicketController_saveNewActivity` |
| `PUT` | `/api/practice/{accessCode}/ticket/activities/{activityId}` | 修改抢票活动 | `TicketController_updateActivity` |
| `PUT` | `/api/practice/{accessCode}/ticket/activities/{activityId}/status` | 发布或关闭抢票活动 | `TicketController_changeActivityStatus` |
| `DELETE` | `/api/practice/{accessCode}/ticket/activities/{activityId}` | 删除抢票活动草稿 | `TicketController_deleteActivity` |

**报名管理（`ticket/registration/operations.json`，2 个）**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `POST` | `/api/practice/{accessCode}/ticket/activities/{activityId}/records` | 报名抢票 | `TicketController_bookTicket` |
| `GET` | `/api/practice/{accessCode}/ticket/activities/{activityId}/records` | 管理员查询活动报名名单 | `TicketController_getActivityRecords` |

**票券管理（`ticket/tickets/operations.json`，4 个）**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `GET` | `/api/practice/{accessCode}/ticket/users/{userId}/records` | 查询模拟用户票券 | `TicketController_getUserRecords` |
| `GET` | `/api/practice/{accessCode}/ticket/records/{recordId}` | 查询单张票券 | `TicketController_getRecordDetail` |
| `PUT` | `/api/practice/{accessCode}/ticket/records/{recordId}/cancel` | 取消票券 | `TicketController_cancelTicket` |
| `PUT` | `/api/practice/{accessCode}/ticket/records/{recordId}/verify` | 管理员核销票券 | `TicketController_verifyTicket` |

抢票项目接口合计：**15** 个。

### 5.3 报修项目接口（`frontend/src/data/repair/*/operations.json`）

**用户管理（`repair/users/operations.json`，6 个）**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `GET` | `/api/practice/{accessCode}/repair/users` | 查询维修模拟用户列表 | `RepairController_getAllUsers` |
| `GET` | `/api/practice/{accessCode}/repair/users/{userId}` | 查询维修模拟用户详情 | `RepairController_getOneUser` |
| `POST` | `/api/practice/{accessCode}/repair/users/switch` | 切换维修模拟用户 | `RepairController_switchUser` |
| `POST` | `/api/practice/{accessCode}/repair/users` | 创建维修模拟用户 | `RepairController_createUser` |
| `PUT` | `/api/practice/{accessCode}/repair/users/{userId}` | 修改维修模拟用户 | `RepairController_updateUser` |
| `DELETE` | `/api/practice/{accessCode}/repair/users/{userId}` | 删除维修模拟用户 | `RepairController_deleteUser` |

**设备管理（`repair/devices/operations.json`，5 个）**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `GET` | `/api/practice/{accessCode}/repair/devices` | 查询设备列表 | `RepairController_getAllDevices` |
| `GET` | `/api/practice/{accessCode}/repair/devices/{deviceId}` | 查询设备详情 | `RepairController_getOneDevice` |
| `POST` | `/api/practice/{accessCode}/repair/devices` | 创建设备 | `RepairController_createDevice` |
| `PUT` | `/api/practice/{accessCode}/repair/devices/{deviceId}` | 修改设备 | `RepairController_updateDevice` |
| `PUT` | `/api/practice/{accessCode}/repair/devices/{deviceId}/status` | 启用或停用设备 | `RepairController_changeDeviceStatus` |

**工单管理（`repair/orders/operations.json`，9 个）**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `GET` | `/api/practice/{accessCode}/repair/orders` | 按角色查询工单列表 | `RepairController_getOrders` |
| `GET` | `/api/practice/{accessCode}/repair/orders/{orderId}` | 查询工单详情 | `RepairController_getOrderDetail` |
| `POST` | `/api/practice/{accessCode}/repair/orders` | 创建报修工单 | `RepairController_createOrder` |
| `PUT` | `/api/practice/{accessCode}/repair/orders/{orderId}/cancel` | 撤回工单 | `RepairController_cancelOrder` |
| `PUT` | `/api/practice/{accessCode}/repair/orders/{orderId}/assign` | 管理员派单或改派 | `RepairController_assignOrder` |
| `PUT` | `/api/practice/{accessCode}/repair/orders/{orderId}/accept` | 维修师傅接单 | `RepairController_acceptOrder` |
| `PUT` | `/api/practice/{accessCode}/repair/orders/{orderId}/submit` | 提交维修结果 | `RepairController_submitOrder` |
| `PUT` | `/api/practice/{accessCode}/repair/orders/{orderId}/confirm` | 报修人确认完成 | `RepairController_confirmOrder` |
| `PUT` | `/api/practice/{accessCode}/repair/orders/{orderId}/return` | 报修人退回维修 | `RepairController_returnOrder` |

**处理记录（`repair/process/operations.json`，2 个）**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `GET` | `/api/practice/{accessCode}/repair/orders/{orderId}/process-records` | 查询工单处理时间线 | `RepairController_getProcessRecords` |
| `POST` | `/api/practice/{accessCode}/repair/orders/{orderId}/process-records` | 追加维修过程记录 | `RepairController_addProcessRecord` |

**图片管理（`repair/images/operations.json`，5 个）**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `POST` | `/api/practice/{accessCode}/repair/images` | 上传故障或维修图片 | `RepairController_uploadImage` |
| `GET` | `/api/practice/{accessCode}/repair/orders/{orderId}/images` | 查询工单图片 | `RepairController_getOrderImages` |
| `GET` | `/api/practice/{accessCode}/repair/images/{imageId}/preview` | 预览图片 | `RepairController_previewImage` |
| `GET` | `/api/practice/{accessCode}/repair/images/{imageId}/download` | 下载图片 | `RepairController_downloadImage` |
| `DELETE` | `/api/practice/{accessCode}/repair/images/{imageId}` | 删除临时图片 | `RepairController_deleteTemporaryImage` |

**工单评价（`repair/evaluation/operations.json`，2 个）**

| 方法 | 路径 | 摘要 | operationId |
| --- | --- | --- | --- |
| `POST` | `/api/practice/{accessCode}/repair/orders/{orderId}/evaluation` | 创建工单评价 | `RepairController_evaluateOrder` |
| `GET` | `/api/practice/{accessCode}/repair/orders/{orderId}/evaluation` | 查询工单评价 | `RepairController_getEvaluation` |

报修项目接口合计：**29** 个。

三类接口总计：**67** 个（23 + 15 + 29）。

> 分组名称与描述来自 `frontend/src/data/ticket/groups.json` 与 `frontend/src/data/repair/groups.json`；系统侧的分组规则写在 `frontend/src/data/doc-groups.js`（按路径前缀把 `/api/sys-class/`、`/api/sys-user/`、`/api/sys-workspace/` 分别归入班级管理、系统用户、工作空间，非 `/api/` 开头的归入登录与服务）。该文件对未匹配到分组的接口会**直接抛出异常**（`接口未配置功能目录`），因此分组覆盖是完备的。

---

## 6. workspace 隔离机制

### 6.1 `workspaceId` 如何从访问码解析

解析链完全由 `ApiAccessInterceptor` 完成，业务代码无法参与指定：

```
URL 路径变量 {accessCode}（64位小写十六进制）
   └─ sys_login_token.api_access_code（原值等值匹配）
        └─ sys_login_token.user_id
             └─ sys_user（必须 role=STUDENT、status=1、class_id 有效且班级 status=1）
                  └─ sys_workspace.student_id = sys_user.id（必须存在且 status=1）
                       └─ request.setAttribute("practiceWorkspaceId", workspace.getId())
```

控制器统一以 `@RequestAttribute(ApiAccessInterceptor.WORKSPACE_ID) Long workspaceId` 取出，再逐层传入 service 方法。以 `TicketController#getAllActivities` 为例，源码中带有强制注释：

```java
LambdaQueryWrapper<TicketActivity> wrapper = new LambdaQueryWrapper<>();
// 必须保留：所有查询限定在当前访问码对应的空间。
wrapper.eq(TicketActivity::getWorkspaceId, workspaceId);
```

### 6.2 为什么客户端不能提交 `workspaceId`

1. **接口签名里没有这个入口**：全部 44 个业务接口（15 抢票 + 29 报修）的路径、查询参数与请求体中，都不存在 `workspaceId` 参数，唯一的位置信息载体是 URL 里的 `{accessCode}`。前端 `frontend/src/api/client.js` 的 `practicePath()` 也只拼接 `/api/practice/${encodeURIComponent(code)}/${project}${suffix}`。
2. **由凭证推导而非由请求声明**：`workspaceId` 是服务端从访问码反查出来的**派生值**，客户端即使伪造也无法注入——控制器是从 `@RequestAttribute` 取，而不是从 `@RequestParam` / `@RequestBody` 取。
3. **请求级作用域**：拦截器把工作空间放在 `request` attribute 上，源码注释明确「只在当前请求中保存，不使用全局变量或ThreadLocal」，避免并发请求之间串号。
4. **写入同样受限**：所有写操作在落库前都带 `workspace_id`。例如 `saveNewActivity` 强制 `activity.setWorkspaceId(workspaceId)`；`createOrder` 强制 `order.setWorkspaceId(workspaceId)`；`createProcessRecord` 从 `order.getWorkspaceId()` 继承，而非从入参取。
5. **二级归属校验**：即便拿到了正确的工作空间，服务层还会校验目标对象是否**属于该空间**。`requireUser(workspaceId, userId)` 用 `.eq(workspaceId).eq(id)` 双条件查询模拟用户，`requireActivity` / `requireRecord` / `requireOrder` 同理，越权访问别的空间的 ID 只会得到 404「不存在」。
6. **跨空间并发安全**：工作空间的写操作统一先执行 `SELECT ... FOR UPDATE` 行锁（`lockWorkspace`，抢票与报修服务各自实现，均查 `sys_workspace` 并校验 `status == 1`），与重置、按班级初始化、删除空间使用**同一把行锁**，因此「重置正在删除数据」与「学生正在抢票」不会交错。

### 6.3 各业务表的 `workspace_id` 覆盖

第 3 节已列明：15 张表中 10 张业务表带 `workspace_id`，覆盖抢票 3 张（用户/活动/票券）与报修 7 张（用户/设备/工单/处理记录/工单图片/附件/评价）。

| 表 | 是否带 `workspace_id` | 隔离方式 |
| --- | --- | --- |
| `ticket_user`、`ticket_activity`、`ticket_record` | 是 | 所有查询/写入均带 `.eq(workspace_id, workspaceId)` |
| `repair_user`、`repair_device`、`repair_order`、`repair_process_record`、`repair_order_image`、`repair_attachment`、`repair_evaluation` | 是 | 同上；图片读取还会二次校验工单归属 |
| `sys_workspace` | 否 | 自身即空间根，`student_id` 唯一 |
| `sys_user`、`sys_class`、`sys_login_token` | 否 | 属于平台层，不属于某个业务空间 |
| `repair_file_delete_task` | 否 | 仅存相对路径的清理队列，不含业务归属 |

图片的隔离还有一层：`repair_attachment` 的 `image_url` 是**服务器存储根目录下的相对路径**，取图/下载时按 `workspace_id` 反查附件记录后才读取文件，路径由服务端拼装，不接受客户端传入的文件路径。图片上传限制为单文件不超过 5MB（`application.yml` 的 `max-file-size: 5MB`，`max-request-size: 6MB`），一次业务操作最多关联 6 张（`requireAttachments` 中 `imageIds.size() > 6` 直接拒绝）。

---

## 7. 两个项目的状态机

### 7.1 抢票项目

#### 7.1.1 活动状态（`ticket_activity.status`）

| 值 | 含义 | 允许的迁移 |
| --- | --- | --- |
| `0` | 草稿 | → `1` 已发布 |
| `1` | 已发布 | → `2` 已关闭 |
| `2` | 已关闭 | 终态，无后续迁移 |

依据 `service/impl/TicketActivityServiceImpl.java`：

| 操作 | 方法 | 前置状态 | 结果 | 关键校验 |
| --- | --- | --- | --- | --- |
| 创建 | `saveNewActivity` | — | 强制写入 `status = 0`、`bookedCount = 0` | 操作人必须是**当前空间内** `role = ADMIN` 且 `status = 1` 的 `ticket_user`（否则控制器返回 403）；校区只能是「新吴校区」或「藕塘校区」；`quota > 0`；`报名开始 < 报名结束 ≤ 活动开始 < 活动结束`。注释明确「状态、报名人数由后端设置」 |
| 修改 | `updateActivity` | 仅 `0` 或 `1` | 状态不变 | 其他状态抛 409「当前活动状态不允许修改」；**已发布且已开始报名**（`now >= bookingStartTime`）后四个时间字段任一变化即 409「报名开始后不能修改报名及活动时间」；`quota` 不得小于 `bookedCount` |
| 发布 | `changeActivityStatus`（目标 1） | `0` → `1` | 已发布 | 目标状态只允许 `1` 或 `2`；`checkPublishable` 复核名称、地点、校区、名额、人数与四个时间，且 `now < bookingEndTime`，否则 409「报名已经结束，不能发布活动」 |
| 关闭 | `changeActivityStatus`（目标 2） | `1` → `2` | 已关闭，**保留报名记录** | 源码注释「已发布活动可以关闭，保留报名记录」 |
| 删除 | `deleteActivity` | 仅 `0` | 物理删除 | 非草稿 → 409「只能删除草稿活动」；已有任何 `ticket_record`（**含已取消**）或 `bookedCount != 0` → 409「活动已有抢票记录或报名人数，不能删除」 |
| 幂等 | `changeActivityStatus` | 目标 == 当前 | 直接返回原活动 | 注释「重复请求保持幂等」 |

非法迁移（`0 → 2`、`2 → 1` 等）统一抛出 409「仅允许草稿发布，或已发布活动关闭」。

#### 7.1.2 票券状态（`ticket_record.status`）

| 值 | 含义 |
| --- | --- |
| `0` | 已取消 |
| `1` | 有效 |
| `2` | 已核销 |

依据 `service/impl/TicketRecordServiceImpl.java`：

| 操作 | 允许的前置状态 | 结果状态 | 对 `booked_count` 的影响 |
| --- | --- | --- | --- |
| 报名 `bookTicket` | 新建 | `1` 有效 | **+1**（`activity.setBookedCount(bookedCount + 1)`） |
| 取消 `cancelTicket` | 仅 `1` | `0` 已取消，写 `cancelTime` | **−1**（`bookedCount - 1`） |
| 核销 `verifyTicket` | 仅 `1` | `2` 已核销，写 `verifyTime`、`verifyUserId` | **不变**，源码注释：「核销不释放名额，不改变bookedCount」 |

关键规则与边界：

- **报名**：要求
  - 操作人 `requireUser(workspaceId, userId, "USER")` —— 只有 `USER` 能报名，`ADMIN` 被拒（提示「仅普通用户可以报名或取消票券」）；
  - 活动 `status` 必须为 `1`，否则 409「活动未发布或已关闭，不能报名」；
  - 时间窗口为**左闭右开**：`now >= bookingStartTime` 且 `now < bookingEndTime`（源码注释「开始时间包含，结束时间不包含」）；
  - **去重**：同一 `workspace + activity + user` 下存在 `status in (1, 2)` 的记录即拒绝（「有效和已核销票券均禁止重复报名」）——即已核销也**不能**再报；只有已取消（`0`）才允许重新报名；
  - 名额：先 `checkActivityCounts`（校验 `quota > 0`、`0 <= bookedCount <= quota`），再要求 `bookedCount < quota`，否则 409「活动名额已满」；
  - 生成 `ticketNo = "TP" + UUID(无横线)`。
- **取消**：要求操作人是票券本人（否则 403「只能取消本人的票券」）、活动 `activityStartTime` 必须存在且 `now < activityStartTime`（否则 409「活动已开始，不能取消票券」）、`bookedCount > 0`；`status == 0` 时**幂等返回原记录且不再扣减人数**；`status == 2`（已核销）抛 409「仅有效票券可以取消」。
- **核销**：仅 `ADMIN`（`requireUser(..., "ADMIN")`）；`0` → 409「票券已取消，不能核销」，`2` → 409「票券已核销，不能重复核销」；写 `verifyTime` 与 `verifyUserId`。
- **加锁顺序**统一为「工作空间 → 活动 → 记录」，源码注释两次强调（`cancelTicket` 的「先读取活动ID，再按活动、记录的顺序加锁」、`verifyTicket` 的「加锁顺序：工作空间 → 活动 → 记录」），用于避免与活动名额更新相互死锁。

#### 7.1.3 `booked_count` 变化一览

| 事件 | `ticket_record.status` 变化 | `booked_count` |
| --- | --- | --- |
| 报名成功 | 新建 → `1` | +1 |
| 取消成功 | `1` → `0` | −1 |
| 重复取消（已是 `0`） | `0` → `0`（幂等返回） | 不变 |
| 核销 | `1` → `2` | **不变** |
| 关闭活动 | 记录不变 | 不变（保留报名记录） |
| 删除草稿活动 | 无记录 | 必须为 0 才允许删除 |

### 7.2 报修项目

#### 7.2.1 工单状态（`repair_order.status`）

| 值 | 含义 |
| --- | --- |
| `0` | 已撤销 |
| `1` | 待分派 |
| `2` | 待接单 |
| `3` | 维修中 |
| `4` | 待确认 |
| `5` | 已完成 |

#### 7.2.2 各角色可执行的动作

依据 `service/impl/RepairOrderServiceImpl.java`：

| 动作 | 执行角色（空间内模拟角色） | 前置状态 | 目标状态 | 额外约束 |
| --- | --- | --- | --- | --- |
| 创建工单 `createOrder` | `REPORTER` | — | `1` 待分派 | 非 `REPORTER` → 403「仅报修人可以创建工单」；标题、描述（HTML 清洗后）必填；带 `deviceId` 时校验设备属于本空间且 `status = 1`（停用设备 → 409），并从设备快照 `deviceName/deviceType/campus/location`；不带设备时必须自填设备名称、类型、校区、地点；联系电话取入参，为空则回落到报修人档案电话，仍为空则 400 |
| 撤回工单 `cancelOrder` | `REPORTER`（必须是**原报修人**） | `1` 或 `2` | `0` 已撤销 | 非 `REPORTER` → 403「仅原报修人可以撤回工单」；非本人 → 403「只能撤回本人提交的工单」；已是 `0` → **幂等返回且不追加记录**；`3/4/5` → 409「只能撤回待分派或待接单的工单」。**保留**原维修人员、图片与历史记录 |
| 派单 / 改派 `assignOrder` | `ADMIN` | `1` 或 `2` | `2` 待接单 | 非 `ADMIN` → 403「仅管理员可以派单」；目标用户必须 `role = MAINTAINER`（否则 403「目标用户必须是维修师傅」）；`fromStatus == 2` 且目标与当前 `maintainerId` 相同时**幂等返回**，不更新、不追加记录；否则写入 `maintainerId` |
| 接单 `acceptOrder` | `MAINTAINER`（必须是**当前被分派**者） | `2` | `3` 维修中 | 非 `MAINTAINER` → 403「仅维修师傅可以接单」；非被分派者 → 403「仅当前被分派的维修师傅可以操作」；已是 `3` → 幂等返回；其他状态 → 409「仅待接单工单可以接单」 |
| 追加维修记录 `addProcessRecord` | `MAINTAINER`（当前被分派者） | `3` | `3`（**状态不变**） | 源码注释：「只追加维修说明时可以与原状态相同」；`content` 必填（≤2000）；图片选传，最多 6 张，必须为本人上传、`imageType = 2`、`status = 0`（未关联）的维修图片 |
| 提交维修结果 `submitOrder` | `MAINTAINER`（当前被分派者） | `3` | `4` 待确认 | `repairResult` 必填（≤2000）；**维修后图片必传 1～6 张**（缺图报 400「请提供1～6张维修后图片」）；已是 `4` → 幂等返回且**不再校验图片**；`completedTime` **不在此处设置**（源码注释：「completedTime 在报修人确认完成时设置」） |
| 退回维修 `returnOrder` | `REPORTER`（原报修人） | `4` | `3` 维修中 | 非 `4` → 409「仅待确认工单可以退回维修」；`content`（退回原因）必填（≤2000）；源码注释：「保留原师傅、上一轮维修说明、图片及历史；重新提交必须使用新附件」 |
| 确认完成 `confirmOrder` | `REPORTER`（原报修人） | `4` | `5` 已完成 | 非 `4` → 409「仅待确认工单可以确认完成」；已是 `5` → 幂等返回；写入 `completedTime` |
| 评价 `evaluateOrder` | `REPORTER`（原报修人） | `5` 已完成 | 状态不变，新增 `repair_evaluation` | 非 `5` → 409「仅已完成工单可以评价」；`score` 必须 1—5；`content` 选填（≤1000）；**一单一评**，已存在评价 → 409「该工单已经评价，不能重复评价」 |

角色归属校验辅助方法：`checkOriginalReporter`（要求 `role = REPORTER` 且 `order.reporterId == operator.id`）、`checkAssignedMaintainer`（要求 `order.maintainerId == operatorId`）、`checkViewPermission`（ADMIN 全通；REPORTER 限本人提交；MAINTAINER 限被分派或曾参与处理）。

#### 7.2.3 处理记录 `action` 取值

取值定义见实体 `RepairProcessRecord` 的注释与各方法的实际调用，均为**大写英文枚举字符串**（字段长度 30）：

| `action` | 含义 | 触发方法 | `from_status` → `to_status` | `operator_id` 角色 | `target_user_id` |
| --- | --- | --- | --- | --- | --- |
| `CREATE` | 创建 | `createOrder` | `null` → `1` | `REPORTER` | 空 |
| `ASSIGN` | 分派 | `assignOrder` | `1` 或 `2` → `2` | `ADMIN` | 目标维修师傅 id |
| `ACCEPT` | 接单 | `acceptOrder` | `2` → `3` | `MAINTAINER` | 空 |
| `RECORD` | 记录维修情况 | `addProcessRecord` | `3` → `3` | `MAINTAINER` | 空 |
| `SUBMIT` | 提交结果 | `submitOrder` | `3` → `4` | `MAINTAINER` | 空 |
| `RETURN` | 退回 | `returnOrder` | `4` → `3` | `REPORTER` | 空 |
| `CONFIRM` | 确认完成 | `confirmOrder` | `4` → `5` | `REPORTER` | 空 |
| `CANCEL` | 撤销 | `cancelOrder` | `1` 或 `2` → `0` | `REPORTER` | 空 |

处理记录是**只追加**的时间线：`getProcessRecords` 在校验查看权限后返回该工单的全部记录（源码注释「不只返回本人操作」），按 `id` 升序。`target_user_id` 被写入后，`ASSIGN` 记录同时成为「师傅曾参与哪些工单」的判定依据（`findParticipatedOrderIds` 与 `checkViewPermission` 都用 `operator_id = 我 OR target_user_id = 我`）。所有幂等分支（重复撤回、重复分派同一师傅、重复接单、重复提交）都**不会**追加处理记录。

#### 7.2.4 图片状态机（辅助）

`repair_attachment.status`：`0` 临时未关联 → `1` 已关联。上传后为 `0`；被工单绑定（`bindAttachments`）时以「带 `status = 0` 条件的 UPDATE」原子置为 `1`，若影响行数不为 1 则 409「图片状态已变化，请重新提交」，从而防止同一张图被两个工单复用。绑定成功后写入 `repair_order_image`，并以 `process_record_id` 关联当时的那条处理记录，`sort_order` 保留前端提交顺序。`image_type`：`1` 故障图片（报修时绑定）、`2` 维修图片（追加记录或提交结果时绑定），两者不可混用。

---

## 8. 重置规则

### 8.1 入口与参数

| 项 | 值 |
| --- | --- |
| 接口 | `POST /api/sys-workspace/one/{id}/reset`，查询参数 `project` |
| 服务方法 | `SysWorkspaceServiceImpl#resetWorkspace(String studentId, String project)` |
| 事务 | `@Transactional(isolation = Isolation.READ_COMMITTED)` |
| `project` 取值 | `TICKET` / `REPAIR` / `ALL`（先 `trim()` 再 `toUpperCase(Locale.ROOT)`，即大小写不敏感） |
| 前端入口 | `frontend/src/views/Reset.vue`（学生自助）与 `frontend/src/views/Management.vue`（教师对学生重置） |

返回值约定（由 `SysWorkspaceController` 映射为 HTTP 状态）：`1` 成功；`-2` 学号为空 → 400；`-3` 学号超过 50 字符 → 400；`-4` 工作空间不存在 → 404；`-5` `project` 为空或不是三者之一 → 400「项目参数只能为TICKET、REPAIR或ALL」；`-6` 用户不存在 → 404；`-7` 目标不是 `STUDENT` → 400「只能重置学生的工作空间」。

### 8.2 三个取值的作用范围

| `project` | 清理并重建的表 | 说明 |
| --- | --- | --- |
| `TICKET` | `ticket_record` → `ticket_activity` → `ticket_user` | 仅抢票项目数据 |
| `REPAIR` | `repair_order_image` → `repair_attachment` → `repair_evaluation` → `repair_process_record` → `repair_order` → `repair_device` → `repair_user`，并投递图片文件清理任务 | 仅报修项目数据 |
| `ALL` | 上述两组全部 | 两个项目一起恢复 |

代码结构（`SysWorkspaceServiceImpl` 第 311—318 行）：

```java
if ("TICKET".equals(project) || "ALL".equals(project)) {
    deleteTicketData(workspaceId);
    createTicketData(workspaceId, sysUser, now);
}
if ("REPAIR".equals(project) || "ALL".equals(project)) {
    deleteRepairData(workspaceId);
    createRepairData(workspaceId, sysUser, now);
}
```

**重置是「先删后建」而不是「标记清零」**：删除后立即用 `TicketMockDataUtil` / `RepairMockDataUtil` 重新生成初始模拟数据，因此活动 ID、工单 ID、票券号等都会变化。前端据此在多处提示「重置后，请重新查询数据，不要继续使用原来的活动或工单ID」（`Home.vue`、`Addresses.vue`、`Reset.vue`、`Management.vue` 均有该提示）。`sys_workspace` 行本身**保留**（连同其 `id` 与 `status`），API 访问码也**保持不变**。

### 8.3 「只影响当前学生与指定项目」的依据

1. **目标空间唯一由学号确定**：方法入参只有 `studentId`；`baseMapper.selectByStudentIdForUpdate(studentId)` 按 `student_id` 取唯一一行并加行锁，得到该学生唯一的 `workspaceId`。
2. **取锁而非信任入参**：不使用客户端传入的空间 id，且该行锁与「按班级初始化」「删除用户时清理」使用**同一把锁**（源码注释：「与初始化、删除使用同一把工作空间行锁」），保证同一学生的重置、初始化、删除、业务写操作串行。
3. **所有删除都以 `workspace_id` 为唯一条件**：`deleteTicketData` / `deleteRepairData` 内部每一条 delete 都是 `new QueryWrapper<X>().eq("workspace_id", workspaceId)` 或 `LambdaQueryWrapper.eq(X::getWorkspaceId, workspaceId)`，没有其他过滤条件，也因此**不可能**触及其他学生的数据。
4. **项目维度由 `project` 分支严格限定**：两个 `if` 分别只调用对应项目的删除与重建方法，`TICKET` 分支不会执行任何 `repair*Mapper` 操作，反之亦然；`ALL` 才两者都做。非法取值在第 294 行即被拒绝（返回 `-5`），不会走到删除逻辑。
5. **角色兜底**：第 306—308 行要求 `sysUser.getRole()` 必须为 `STUDENT`，教师账号无法被重置（返回 `-7`）。
6. **学生自助路径的额外约束**：系统侧 `SysLoginInterceptor#hasPermission` 只允许学生调用 `POST /api/sys-workspace/one/{id}/reset`，且路径中的 `{id}` 必须**等于自己的账号 id**（`user.getId().equals(requestedId)`），因此学生无法通过该接口重置他人。

### 8.4 报修重置中的文件清理

图片是「数据库记录 + 磁盘文件」双份数据，`deleteRepairData` 的处理顺序为：

1. 先**读取**待删的 `repair_attachment.image_url` 与 `repair_order_image.image_url`，去重收集为路径集合；
2. `repairFileCleanupService.enqueue(paths)` 把路径写入 `repair_file_delete_task`——该方法标注 `@Transactional(propagation = Propagation.MANDATORY)`，即**必须加入调用方事务**（源码注释：「任务与当前业务事务一起提交，回滚时不删除原图片」）；
3. 再依次删除 `repair_order_image`、`repair_attachment`、`repair_evaluation`、`repair_process_record`、`repair_order`、`repair_device`、`repair_user`。

真正的磁盘删除由后台任务异步完成：`RepairFileCleanupService#clean` 带 `@Scheduled(fixedDelay = 30000, initialDelay = 30000)`，每轮最多取 100 条任务（`LIMIT 100`）按 id 升序处理，**先删文件再删任务**，失败则保留任务待下次重试（`log.warn("图片清理失败，保留任务待重试")`）。定时能力由 `config/RepairSchedulingConfig` 的 `@EnableScheduling` 开启。

### 8.5 与「按班级初始化」的区别

| 维度 | 重置 `resetWorkspace` | 按班级初始化 `initializeTicketClass` / `initializeRepairClass` |
| --- | --- | --- |
| 接口 | `POST /api/sys-workspace/one/{id}/reset` | `POST /api/sys-workspace/classes/{classId}/ticket/initialize`、`.../repair/initialize` |
| 作用对象 | 单个学生 | 班级内全部 `role = STUDENT` 的学生（按 `id` 升序） |
| 是否先删除 | **先删后建**，无条件覆盖 | **不删除**：`hasTicketData` / `hasRepairData` 为真即 `continue` 跳过该学生 |
| 典型用途 | 学生自己把项目恢复初始状态重新练习 | 教师为全班准备初始项目数据；对已有数据的学生**幂等跳过** |

两者都对每个学生的空间行加同一把 `FOR UPDATE` 锁，且当学生缺少工作空间行时直接抛 `IllegalStateException("学生工作空间不存在：" + 学号)`。

### 8.6 前端的重置约束（客户端行为，非服务端强制）

`frontend/src/views/Reset.vue` 与 `composables/cooldown.js` 实现了一套**仅存在于浏览器**的节流：重置成功后 `cool.start(300000)`，即 5 分钟冷却，两个项目**共用**同一倒计时（`localStorage` 键为 `practice:${userId}:reset:deadline`）；提交时用 `exclusive()` 通过 `navigator.locks`（不支持时退化为 `localStorage` 锁，超时 90 秒）防止多标签页重复提交；成功后写入 `localStorage` 的 `practice:${userId}:reset-event` 并派发 `practice-reset` 事件，各页面据此清空缓存的 ID 并重新查询。接口文档页的「在线尝试」对学生另有 10 秒冷却（`cool.start(10000)`）。这些约束由前端自行实现，**服务端未发现对应的重置频率限制**。

---

## 9. 前端页面、路由与接口调用关系

### 9.1 路由表（`frontend/src/router.js`）

使用 `createWebHistory`，`scrollBehavior` 恒为回到顶部，末尾有全捕获重定向到 `/`。

| 路径 | 组件 | 路由 meta | 页面职责 |
| --- | --- | --- | --- |
| `/login` | `views/Login.vue` | `public: true`，`title: 账号登录` | 账号登录表单，提交 `POST /login` |
| `/` | `views/Home.vue` | `title: 实训首页` | 按角色展示不同首页；学生额外查询并展示本人工作空间 |
| `/teacher/classes` | `views/Management.vue`（`props.kind = 'classes'`） | `teacher: true` | 班级管理 |
| `/teacher/users` | `views/Management.vue`（`props.kind = 'users'`） | `teacher: true` | 系统用户管理 |
| `/teacher/workspaces` | `views/Management.vue`（`props.kind = 'workspaces'`） | `teacher: true` | 工作空间管理 |
| `/teacher/import` | `views/Import.vue` | `teacher: true` | Excel 批量导入学生 |
| `/data` | `views/ProjectData.vue` | `student: true` | 查看本人两个项目的模拟数据 |
| `/addresses` | `views/Addresses.vue` | `student: true` | 展示本人两个项目的接口基础地址 |
| `/docs` | `views/Docs.vue` | `title: 接口文档` | 接口文档与在线尝试（教师可看系统管理分组） |
| `/reset` | `views/Reset.vue` | `student: true` | 学生自助重置本人项目数据 |
| `/:pathMatch(.*)*` | — | — | `redirect: '/'` |

全局前置守卫（`router.beforeEach`）逻辑：未登录且目标非 `public` → `/login`；已登录访问 `/login` → `/`；`meta.teacher` 为真而当前用户不是教师 → `/`；`meta.student` 为真而当前用户是教师 → `/`；随后设置 `document.title` 为 `${meta.title} · Vue实训管理平台`。

### 9.2 每个页面的职责与调用的接口族

| 页面 | 职责 | 调用的接口族 | 关键实现位置 |
| --- | --- | --- | --- |
| `views/Login.vue` | 学号/工号 + 密码登录；校验响应完整性（必须有 `token` 且角色属于 `TEACHER`/`STUDENT`）；成功后写入 `sessionStorage` | 系统族：`POST /login` | `stores/auth.js#login` |
| `views/Home.vue` | 首页。教师显示「教学工作台」入口；学生进入时自动查询本人工作空间并渲染详情与重置提示 | 系统族：`GET /api/sys-workspace/one/{userId}` | `Home.vue` 第 7—8 行 |
| `views/Management.vue` | 由 `kind` 复用三种管理页（班级 / 用户 / 工作空间）。支持「查询全部 / 按编号 / 按班级」三种模式、新增与编辑弹窗、详情弹窗、启停、删除、重置、按班级初始化 | 系统族：班级 → `GET /api/sys-class/all`、`GET /api/sys-class/one/{id}`、`POST\|PUT /api/sys-class/one`、`PUT /api/sys-class/one/{id}/status`、`DELETE /api/sys-class/one/{id}`；用户 → `GET /api/sys-user/all`、`GET /api/sys-user/one/{id}`、`GET /api/sys-user/classes/{id}`、`POST\|PUT /api/sys-user/one`、`PUT /api/sys-user/one/{id}/status`、`DELETE /api/sys-user/one/{id}`；工作空间 → `GET /api/sys-workspace/classes/{id}`、`GET /api/sys-workspace/one/{id}`、`PUT /api/sys-workspace/one/{id}/status`、`POST /api/sys-workspace/one/{id}/reset`、`POST /api/sys-workspace/classes/{classId}/(ticket\|repair)/initialize` | `Management.vue` 第 9、13、15、16、19 行（`roots` 映射 + `load` / `save` / `show` / `confirm`）。注意班级页的下拉只提供「查询全部 / 按编号」，因此不打 `sys-class` 的按班级查询；用户页与工作空间页才有「按班级」模式 |
| `views/Import.vue` | 上传 Excel（前端预校验扩展名 `.xls/.xlsx` 与 5MB 上限），以 `multipart/form-data` 提交，展示 `total/successCount/skippedCount/failedCount` 与明细 | 系统族：`POST /api/sys-user/import` | `Import.vue` 第 5 行 |
| `views/ProjectData.vue` | 学生查看本人两个项目的模拟数据。项目切换（ticket/repair）、模拟身份选择、按标签页查询列表、详情弹窗，报修工单还可展开处理记录 / 工单图片（预览、下载）/ 工单评价。监听 `practice-reset` 与 `storage` 事件在重置后自动刷新 | 业务族（访问码）：列表 `GET /users`、`GET /activities`、`GET /devices`、`GET /orders?operatorId=&scope=`、`GET /users/{id}/records`；详情 `GET /users/{userNo}`（抢票用户详情用模拟编号）、`GET /activities/{id}`、`GET /records/{id}`、`GET /devices/{id}`、`GET /orders/{id}?operatorId=`；报修工单附属 `GET /orders/{id}/process-records`、`GET /orders/{id}/images`、`GET /orders/{id}/evaluation`；图片 `GET /images/{id}/preview`、`GET /images/{id}/download` | `ProjectData.vue` 第 17—33 行；标签页映射 `ticket → users/activities/records`、`repair → users/devices/orders`；`scope` 仅当所选模拟身份为 `MAINTAINER` 时提交 |
| `views/Addresses.vue` | 展示 `practiceBase('ticket')` 与 `practiceBase('repair')` 两个完整基础地址、复制按钮、Axios 示例代码，并用词条区分「网页登录凭证 / 实训访问码 / 学号与模拟身份 / 重置后的数据」 | 纯前端计算，不发请求 | `Addresses.vue` 第 2—5 行 |
| `views/Docs.vue` | 接口文档浏览器。按项目（教师含「系统管理」）与功能目录折叠展示；本地搜索；状态与角色字典说明；对学生开放「在线尝试」并施加 10 秒冷却；监听重置事件刷新 | 系统族：教师首次进入时动态 `import('../data/system-spec.json')`；实际调用由 `components/Endpoint.vue` 逐条发起 | `Docs.vue` 第 9—34、36 行 |
| `views/Reset.vue` | 学生选择 `TICKET/REPAIR/ALL` 后二次确认重置；成功后启动 5 分钟冷却、广播重置事件并主动重新拉取项目数据 | 系统族：`POST /api/sys-workspace/one/{id}/reset?project=`；随后业务族：`GET /activities`（ticket）或 `GET /devices`（repair） | `Reset.vue` 第 8 行 |
| `components/Endpoint.vue` | 单条接口的文档与执行器：渲染 Markdown 描述（`marked` + `DOMPurify`）、参数表、请求体、响应示例；「填入示例」「复制地址」「发送」；按 `schema.js` 做前端校验；GET 与图片预览/下载无需冷却，其余请求对学生施加 10 秒冷却并用 `navigator.locks` 串行化 | 按 `op.project` 走系统族（`system` axios 实例，带 Bearer）或业务族（`practice` axios 实例，URL 内嵌访问码） | `components/Endpoint.vue` 第 6—13、29—44 行 |

### 9.3 两个 axios 实例与调用约定（`frontend/src/api/client.js`）

| 实例 / 函数 | 用途 | 凭证注入方式 |
| --- | --- | --- |
| `system` | 系统侧接口（`/login`、`/api/sys-*`） | 请求拦截器从 `sessionStorage` 的会话中取 `token`，除 `/login` 外统一加 `Authorization: Bearer <token>` |
| `practice` | 业务侧接口 | **不加请求头**；凭证是 URL 里的访问码，由 `practicePath()` 拼接 |
| `sys(method, url, data, params)` | `system` 的语义化封装，经 `unwrap` 解包 | — |
| `query(project, path, params)` | `practice` 的查询封装 | 经 `practicePath()` |
| `practicePath(project, suffix)` | 生成 `/api/practice/{encodeURIComponent(apiAccessCode)}/{ticket\|repair}{suffix}`；无访问码时抛错「当前账号没有实训访问码，请重新登录或联系教师。」；`project` 不在 `['ticket','repair']` 时抛「项目不存在」 | 访问码来自登录响应并存入会话 |
| `binary(path)` | 以 `blob` 拉取图片，`validateStatus` 恒真后自行区分 JSON 错误与二进制成功 | 经 `practice` 实例 |
| `unwrap(response)` | 统一解包 `Result`：`code !== 200` 即抛 `message`，否则返回 `data` | — |
| `errorText(e)` | 错误文案归一：优先响应体 `message`，其次超时/网络错误专用文案 | — |

响应拦截器在遇到 `401`（无论是响应体 `code === 401` 还是 HTTP 401，且 URL 不是 `/login`）时清除会话并派发 `session-expired` 事件；`App.vue` 监听该事件，调用 `auth.clear()` 并跳回 `/login`，同时提示「登录已失效，请重新登录。」。

### 9.4 导航与布局

`App.vue` 依据 `auth.teacher` 渲染两套导航：

- 教师：`/` 教学首页、`/teacher/classes` 班级管理、`/teacher/users` 系统用户、`/teacher/workspaces` 工作空间、`/teacher/import` 学生导入、`/docs` 接口文档。
- 学生：`/` 实训首页、`/data` 我的项目数据、`/addresses` 接口地址、`/docs` 接口文档、`/reset` 数据重置。

顶栏显示姓名与角色标签，提供「退出登录」（先调 `POST /logout`，无论成败都在 `finally` 中清理本地会话并跳转登录页）；全局提示由 `composables/ui.js` 的 `ui.message` / `ui.error` 驱动；进行中的请求状态保存在 `stores/requests.js` 的 `pendingRequests` 中，用于切页后保持按钮忙碌态。

---

## 10. 已实现 / 部分实现 / 未实现功能清单

以下判断均基于已阅读的源码事实。凡证据不足者一律标注「未确认」，不做补白。

### 10.1 已实现

| 功能 | 证据 |
| --- | --- |
| 账号登录 / 退出，含失败码分级 | `SysLoginController`（`/login`、`/logout`）、`SysLoginServiceImpl` |
| 密码加盐哈希（SHA-256 + 32 字节随机盐，常量时间比较） | `util/PasswordUtil.java`、`SysLoginServiceImpl#matchesPassword` |
| 登录 Token 散列存储、1 天过期、退出撤销 | `util/TokenUtil.java`、`SysLoginServiceImpl`、`SysLoginInterceptor`、实体 `SysLoginToken` |
| 学生长期 API 访问码（原值存储、不随退出失效） | `SysLoginServiceImpl` 第 83—86、196—197 行；`ApiAccessInterceptor` 第 75—79 行 |
| 双拦截器分离与注册顺序 | `config/WebMvcConfig.java#addInterceptors` |
| 教师 / 学生接口级权限与学生数据归属校验 | `SysLoginInterceptor#hasPermission` |
| 班级增删改查与启停 | `SysClassController`、`SysClassServiceImpl` |
| 系统用户增删改查、启停、按班级查询 | `SysUserController`、`SysUserServiceImpl` |
| Excel 批量导入学生（POI 5.5.1，返回明细报告） | `SysUserController#importStudents`、`util/StudentExcelUtil.java`、`dto/StudentImportResultDTO.java` |
| 工作空间自动创建 / 状态同步 / 暂停 / 删除时连带清理 | `SysWorkspaceServiceImpl#ensureStudentWorkspace` 等；由 `SysUserServiceImpl` 第 104、263、265、298、316 行调用 |
| 工作空间按学号查询、按班级查询、启停、重置 | `SysWorkspaceController`、`SysWorkspaceServiceImpl` |
| 按班级初始化抢票 / 报修数据（已有数据的空间自动跳过） | `initializeTicketClass`、`initializeRepairClass`、`hasTicketData`、`hasRepairData` |
| 抢票：活动增删改查、发布、关闭、报名、取消、核销、名单查询、票券详情 | `TicketController`（15 个接口）、`TicketActivityServiceImpl`、`TicketRecordServiceImpl` |
| 抢票并发控制：工作空间行锁 + 活动/记录 `FOR UPDATE` + 统一加锁顺序 | `lockWorkspace`、`findActivityForUpdate`、`requireActivity(..., true)`、`requireRecord(..., true)` |
| 报修：模拟用户增删改查与切换 | `RepairController`、`RepairUserServiceImpl` |
| 报修：设备增删改查与启停 | `RepairController`、`RepairDeviceServiceImpl` |
| 报修：工单全流程（创建、撤回、派单/改派、接单、追加记录、提交、退回、确认、评价） | `RepairOrderServiceImpl`（911 行，9 个状态迁移方法） |
| 报修：处理记录只追加时间线 | `RepairProcessRecord`、`getProcessRecords` |
| 报修：图片上传（5MB 限制）、绑定去重、预览、下载、删除临时图 | `RepairController` 图片 5 接口、`RepairAttachmentServiceImpl`、`RepairFileUtil` |
| 报修：图片磁盘文件的延迟清理与失败重试 | `RepairFileCleanupService`（`@Scheduled(fixedDelay = 30000)`）、`entity/RepairFileDeleteTask.java` |
| 报修：工单图片与归属校验（附件-工单图片分离） | `RepairOrderImage`、`RepairAttachment`、`requireAttachments` / `bindAttachments` |
| 统一响应包装与全局异常映射（400/401/403/404/405/409/413/415/500） | `common/Result.java`、`exception/GlobalExceptionHandler.java`、`exception/BusinessExceptions.java` |
| CORS 配置（可配置来源白名单） | `config/WebMvcConfig.java#addCorsMappings` |
| 初始化模拟数据生成器 | `util/TicketMockDataUtil.java`、`util/RepairMockDataUtil.java` |
| 前端：登录、首页、三类管理页、学生导入、项目数据浏览、接口地址、接口文档与在线尝试、自助重置 | `frontend/src/views/` 8 个页面 |
| 前端：接口文档数据驱动（67 个接口全部来自 JSON 契约，含参数与响应 schema） | `frontend/src/data/`（16 个 JSON）+ `components/Endpoint.vue`、`components/SchemaTable.vue`、`api/schema.js`、`api/project-contract.js`、`data/doc-groups.js` |
| 前端：在线尝试的前端校验与冷却、多标签页互斥、重置事件广播 | `composables/cooldown.js`、`composables/cooldown-core.js`、`stores/requests.js` |
| 前端：重置事件跨页同步 | `Views/ProjectData.vue`、`Docs.vue`、`Reset.vue` 均监听 `practice-reset` 与 `storage` |
| 前端：契约测试 | `frontend/tests/contracts.test.mjs`（7 个用例，覆盖冷却隔离、校验、文档隔离、分组完备性、示例合法性、跨项目字段契约、路径后缀） |
| 浏览器端测试脚本 | `frontend/tests/browser.mjs`、`concurrency-browser.mjs`、`docs-isolation.mjs`、`edge-browser.mjs`、`visual-initial.mjs`（基于已声明的 `@playwright/test`） |

### 10.2 部分实现

| 功能 | 现状 | 缺口 / 依据 |
| --- | --- | --- |
| 自动化测试覆盖 | 后端仅有 1 个测试类 `VuePractiseBackendStudyApplicationTests.java`（Spring Boot 默认生成的上下文加载测试）；前端有 `contracts.test.mjs` 与 5 个 Playwright 脚本 | 业务状态机、权限边界、并发抢票等核心逻辑**未发现**单元测试或集成测试 |
| Redis | `pom.xml` 引入 `spring-boot-starter-data-redis` | `application.yml` 无任何 Redis 配置，Java 源码中检索不到 Redis API 调用；该依赖在当前实现中**未被使用**，是否为预留**未能从源码确认** |
| 分页 | `pom.xml` 引入 `mybatis-plus-jsqlparser` 并注明「分页插件需要的SQL解析依赖」 | 全部列表接口（`getAllUsers`、`getAllActivities`、`getOrders` 等）都是全量 `selectList` + `orderBy`，源码中**未发现**分页插件的注册或 `Page` 对象的使用 |
| 接口频率限制 | 前端对「在线尝试」有 10 秒冷却、对重置有 5 分钟冷却，均以 `localStorage` + `navigator.locks` 在浏览器内实现 | 服务端**未发现**限流、防重放或幂等键机制；绕过前端直接请求后端即可不受冷却约束（接口文档页面自己也说明了「你的独立 Vue 项目直接请求后端不受此限制」） |
| 活动状态 `2`（已关闭） | `1 → 2` 关闭已实现，并保留报名记录 | 关闭后**未发现**重新开启（`2 → 1`）或重新编辑的路径；`updateActivity` 对 `status = 2` 直接 409 |
| 已关闭 / 已发布活动的删除 | 仅草稿（`status = 0`）可删除 | 已发布或已关闭的活动**未发现**删除路径（`deleteActivity` 对非 0 状态一律 409），只能关闭后长期保留 |
| 工单评价 | 可创建、可查询；`score` 限 1—5，一单一评 | `repair_evaluation` **未发现**修改或删除评价的接口；`GetEvaluation` 在无评价时返回 404 |
| 学生导入 | 支持 `.xls/.xlsx`、单次上限与字段校验、返回逐行结果 | 具体的行数上限（页面文案称 1000 行）位于 `StudentExcelUtil`，本次未逐行读取该文件，**具体上限值未确认**；导入的教师账号分支**未确认** |
| `ServletInitializer` | 存在 `SpringBootServletInitializer` 子类，具备打 WAR 的入口 | `pom.xml` 中 `spring-boot-starter-tomcat` 的 `provided` 依赖被**注释掉**（第 51—55 行），`packaging` 仍为 `jar`，因此 WAR 部署路径在当前配置下不可直接使用 |

### 10.3 未实现

| 功能 | 依据 |
| --- | --- |
| 数据库建表脚本 / 版本化迁移（Flyway、Liquibase 等） | 仓库内**没有** `.sql` 文件，也没有 `db/migration` 目录；表结构完全依赖 JPA `ddl-auto: update` 自动生成 |
| 登出时失效长期 API 访问码 | `ApiAccessInterceptor` 明确不检查 `revoke_time`；`SysLoginServiceImpl#logout` 只写 `revoke_time`，不改 `api_access_code`。也就是说退出登录**不会**让学生已分发的访问码失效 |
| 访问码轮换 / 重新生成接口 | 源码中生成访问码的唯一位置是 `SysLoginServiceImpl` 第 84—86 行（仅在为空时生成）；**未发现**任何强制重置 `api_access_code` 的接口 |
| JWT / OAuth / 第三方登录 | 全部鉴权基于随机不透明 Token + 数据库查表，**未发现**任何 JWT、OAuth 或 SSO 依赖与代码 |
| 邮件发送 | `pom.xml` 两次引入 `spring-boot-starter-mail`，但源码中**未发现** `JavaMailSender` 或任何发信逻辑 |
| 教师侧的业务数据管理界面 | 业务接口只有学生访问码这一条入口（`ApiAccessInterceptor` 拒绝非 `STUDENT`）；教师无法从平台网页直接操作 `ticket_*` / `repair_*` 数据 |
| 工单图片的教师或管理员统一清理入口 | 图片删除接口 `DELETE /images/{imageId}` 位于 `RepairController`，只能通过学生访问码调用，且用于删除**临时未关联**的图片 |
| 操作审计日志 | **未发现**独立的审计/操作日志表与记录逻辑；最接近的是 `repair_process_record`（仅限报修工单流程） |
| 密码修改 / 找回 | 源码中**未发现**修改密码或找回密码的接口；新建账号密码固定为 `123456` |

### 10.4 明确标注为「未确认」的条目

| 条目 | 说明 |
| --- | --- |
| Redis 依赖的实际用途 | 已引入依赖但无配置、无调用代码；是否为课程预留或后续扩展，源码无说明 |
| MyBatis-Plus 分页插件的注册情况 | 引入了 `mybatis-plus-jsqlparser` 且注释称「分页插件需要」，但未发现 `MybatisPlusInterceptor` 配置类；是否在其他位置注册未能确认 |
| 后端测试类的实际断言内容 | 仅确认存在 `VuePractiseBackendStudyApplicationTests.java` 一个测试类，未读取其方法体 |
| Excel 导入的行数上限与教师账号分支 | 页面文案称 1000 行，`StudentExcelUtil` 未逐行读取，具体校验阈值未确认 |
| `TicketMockDataUtil` / `RepairMockDataUtil` 生成的具体数据规模 | 已确认其调用点与生成的实体类型，未逐字段统计初始数据条数与内容 |
| 前端 `docs-isolation.mjs`、`browser.mjs` 等 Playwright 脚本的具体断言 | 仅确认文件存在与规模，未逐行读取断言内容 |
| `RepairController` 图片上传的具体格式白名单 | 已确认 5MB 限制、`multipart/form-data` 与 6 张上限，未确认允许的 MIME 类型白名单 |
| 参考项目是否包含部署文档或运行手册 | 仓库根目录只有 `.gitignore`，无 README、无部署脚本、无 Dockerfile |

## 11. 补充核对（复现阶段对第 10.4 节「未确认」项的复核）

撰写本文档时未确认的条目，在复现阶段由复现实现逐行读取参考源码后得到确认。
下表给出复核结论与依据行，供教师核对。

| 编号 | 原「未确认」项 | 复核结论 | 依据 |
| --- | --- | --- | --- |
| 1 | Redis 依赖的实际用途 | **确认无用途**。参考项目 `pom.xml` 声明了 `spring-boot-starter-data-redis`，但全部 Java 源码与配置文件中检索不到任何 Redis 使用，属未清理的依赖。复现实现因此移除了该依赖（见 `docs/03-reuse-and-reimplementation.md` 第 2 节）。 | 全仓库 `grep -i redis` 仅命中 `pom.xml` |
| 2 | MyBatis-Plus 分页插件 | **确认未注册**。`mybatis-plus-jsqlparser` 只是被引入，没有 `MybatisPlusInterceptor` 配置类；所有列表接口都是全量 `selectList`，前端在已加载数据内分页。复现实现与后端无关地保留同版本依赖，同样不做服务端分页。 | 无 `MybatisPlusInterceptor`；各 Service 使用 `selectList` |
| 3 | 后端测试类断言内容 | **确认仅有一条诊断用例**。`VuePractiseBackendStudyApplicationTests#diagnoseMybatisPlus` 只打印数据源与自动配置条件，没有业务断言。复现实现未保留该诊断用例（其价值在于排查 MyBatis-Plus 自动配置，属一次性排查代码）。 | 参考项目该测试类共 79 行，无 `assert` |
| 4 | Excel 导入行数上限 | **确认为 1000 行**。`StudentExcelUtil` 中 `MAX_STUDENTS = 1000`，超限抛 `ExcelImportException("每次最多处理1000行学生数据")`；文件大小上限 5 MB。导入的学生一律按 `STUDENT` 角色创建，没有教师分支。 | `StudentExcelUtil` 常量与校验分支 |
| 5 | 基准数据规模 | **抢票**：5 个模拟用户（N0001–N0005，其中 N0004 停用、N0005 为管理员）、4 个活动（1 个正在报名、1 个未开始报名、1 个草稿、1 个已关闭），初始票券为 0。<br>**报修**：8 个模拟用户（R0001–R0004 报修人，M0001–M0002 师傅，A0001–A0002 管理员）、7 台设备（D0001–D0007，D0007 停用）、6 张工单（BX0001–BX0006，全部为待分派）、每单一 条 CREATE 处理记录，初始无图片与评价。 | `TicketMockDataUtil`、`RepairMockDataUtil` |
| 6 | 前端 Playwright 脚本断言 | 参考项目的 5 个脚本分别覆盖：主流程与真实交互（`browser.mjs`）、并发与跨标签页（`concurrency-browser.mjs`）、边界与二进制响应（`edge-browser.mjs`）、文档项目隔离（`docs-isolation.mjs`）、初始视觉（`visual-initial.mjs`）。复现实现按相同验收目标重写了其中两项（见下一行）。 | 逐个脚本读取 |
| 7 | 图片上传格式白名单 | **确认**：只接受可以通过 `ImageIO` 真实解码的 JPEG / PNG / WebP；像素总数上限 1600 万；单张 5 MB；每次最多关联 6 张。判定依据是文件内容而不是扩展名或 Content-Type。 | `RepairFileUtil#validateImage`、`RepairOrderServiceImpl#requireAttachments` |
| 8 | 参考项目的部署文档 | **确认没有**。参考仓库根目录只有 `.gitignore`，没有 README、部署脚本或 Dockerfile。复现实现补充了 `README.md` 与 `scripts/` 下的启动停止脚本。 | 参考仓库根目录列表 |

**复现实现的测试对应关系**

参考项目的 5 个浏览器脚本中，复现实现重写了两个：

| 参考脚本 | 复现实现 | 说明 |
| --- | --- | --- |
| `browser.mjs` | `frontend/tests/browser.mjs` | 按相同验收目标重新编写，56 项断言 |
| `contracts.test.mjs` | `frontend/tests/contracts.test.mjs` | 保留相同的契约校验目标（冷却隔离、Schema 校验、项目隔离、分组完备、示例合法性、路径后缀），7 个用例 |
| `concurrency-browser.mjs` | 未重写 | 跨标签页冷却与慢响应期间禁止重复提交；本轮只做了代码级一致性确认 |
| `edge-browser.mjs` | 未重写 | 边界与二进制响应；部分断言已并入 `scripts/api-verify.sh`（404 冷却、图片预览返回真实 PNG） |
| `docs-isolation.mjs` | 未重写 | 文档项目隔离；已由 `contracts.test.mjs` 与浏览器验证的分组计数覆盖 |

未重写的三个脚本已记录在 `docs/04-development-log.md` 第 7.4 节的「未验证」项中。
