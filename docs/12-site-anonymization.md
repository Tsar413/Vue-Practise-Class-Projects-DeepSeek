# 全站展示与 mock 数据脱敏验收

日期：2026-10-09。开发基线：`main` / `7524f2fb4fb8325068e9490488f2545c3da46d5e`。
本轮仅在现有 Linux 虚拟机和成果仓库操作，未修改原参考仓库；正常数据不做改名、迁移、重置或重新导入。

## 实际改动

- 前端统一 `utils/privacy.js`：姓名、用户名、学生/教师/操作人等结构化字段显示角色与编号；班级显示编号。列表、详情、模拟身份选择器、页面顶部、教学任务与成果、AI 历史及来源、错误提示统一使用展示函数。
- 已知 mock 人名、旧校区、院系、楼宇及场所改为中性文字；旧记录只在显示时转换。新初始化的 `TicketMockDataUtil`、`RepairMockDataUtil` 直接生成中性数据，本轮没有重置正常空间。
- 前端与后端 AI 上下文使用的 11 份结构化文档保持逐字节一致。修改的枚举示例使用校区A/B；对应单元测试仍检查确切枚举内容，没有删去断言。
- `CampusAliasUtil` 允许中性值和历史值。新旧筛选值归一后同时查询两者，保留 workspace 条件；未知校区拒绝。活动、设备、工单的相关校验与筛选共用此兼容层。
- 教师编辑已有用户/班级时显示只读代称，保存仍使用原记录；任务与成果草稿预填内容只在显示时转换，未编辑字段提交保留原值。
- 文档页的 JSON 响应按字段递归生成匿名展示副本，复制响应使用同一副本；编号与协议字段不改变。页面标明“实际响应（展示已脱敏）”。
- Markdown 继续经过 DOMPurify；没有使用 DOM 遮盖、全局观察器或修改后端实体序列化的方式掩盖页面。

## 开发来源与发现的问题

DeepSeek Harness 完成首轮页面接入、mock 生成器、校区兼容、结构化文档及说明草稿。Codex 独立设计测试、检查差异、执行隔离环境与浏览器验证，并直接完成以下收尾修改：

1. 简化并修正 `privacy.js` 的字段上下文传递、稳定编号、递归数组、原对象保留及普通教学文字保护；删除未接入的后端文本工具草稿。
2. 修正 `ui.js`、`Endpoint.vue` 的嵌套 JSON/错误展示边界；不把展示副本回写业务字段。
3. 修正任务与学生成果表单的无效 `v-model` 函数调用，使用显示值和用户输入事件分离的绑定；补齐标题、章节、附件显示名称。
4. 浏览器实测发现 `Home.vue` 新增 `computed` 未导入导致首页异常，Codex补齐导入并重新加载验证。
5. 编写本报告、新增前端 6 项契约测试、真实隔离 MySQL 3 项用例及浏览器数据准备/保留校验脚本。

Harness 过程中曾误将接口文档组件覆盖成项目数据页面。Codex差异审查发现后，Harness恢复原组件并只保留相关改动；正式构建和浏览器接口请求在恢复后通过。初版还出现已知班级名忽略编号、普通“请联系老师”被正则误改、JSON未携带父行编号等失败，均保留测试并在修正后通过。

浏览器夹具初次准备因报修用户不存在校区字段而失败，属于测试夹具问题；按实际表结构修正后通过。未放宽业务断言。

## 实际验证

| 范围 | 实测结果 |
| --- | --- |
| 前端已有7项 + 新增6项 | 13/13通过：稳定角色编号、班级别名、原对象保留、普通文字不误改、11份文档同步、嵌套JSON |
| 前端正式构建 | Vite构建通过 |
| 后端单元测试 | 34/34通过，无跳过 |
| P01 新mock | 两项目用户及活动/设备不含目标旧名称，模拟用户名称可区分，通过 |
| P02 历史校区兼容 | 活动/设备/工单的新旧筛选结果相同、旧记录仍可查、workspace不扩大、未知校区400、查询后存储值未改，通过 |
| P03 中性值写入 | 活动和设备使用文档中的校区B创建成功，仅写隔离库，通过 |
| 既有C05、D02回归 | 项目重置保留教学/AI/图片；AI输入及实际上下文引用，2/2通过，仅本地协议桩 |
| 教师浏览器 | 用户/班级列表与编辑、任务列表/详情/编辑显示匿名值，通过 |
| 学生浏览器 | 任务详情与草稿预填、AI历史会话/正文、两项目mock用户及身份选择器、抢票用户详情显示匿名值，通过 |
| 文档浏览器请求 | GET抢票用户列表HTTP200，216ms；响应框姓名为角色编号、校区A/B、院系A，字段和ID保留，通过 |
| 保存保护 | 浏览器分别保存用户、班级、任务、草稿后，SQL断言原姓名/用户名/班级名/任务原文/草稿原文全部保留，通过 |
| 正常数据 | 29张表共131行，27张表CHECKSUM TABLE一致；另两表仅一个账号的last_login_time与登录记录的create_time/expire_time/token_hash变化，符合重新登录更新，其余字段逐列比较不变；12个图片文件路径和SHA-256一致 |

复制响应按钮的剪贴板端到端检查因浏览器连接中断未完成；代码使用与显示相同的匿名文本，纯函数递归用例通过。手机宽度、本轮未涉及的完整教学闭环未重复验收。浏览器首次发现的首页脚本错误已修；修复后完成上述页面流程，未宣称对每个浏览器页面做了零错误全量审计。

本轮没有调用真实辅导模型。AI历史页面的验收数据明确为合成夹具，C05/D02使用本地协议桩，不能视为新的真实模型验收。

## 可重复执行的命令

在项目根目录执行。以下隔离环境复用现有工具和测试库；护栏未通过即停止。内存有限，先暂停正常前后端；MySQL保持运行。

```bash
bash .runtime/run-local.sh stop
npm --prefix frontend test
npm --prefix frontend run build
JAVA_HOME="$HOME/tools/opt/jdk-21.0.12.1+1" \
  "$HOME/tools/opt/apache-maven-3.9.16/bin/mvn" -f backend/pom.xml -o test
python3 tests/integration/environment.py stub-start
python3 tests/integration/environment.py start --mode protocol
python3 -m unittest discover -s tests/integration -p test_anonymization.py -v
PYTHONPATH=tests/integration python3 -m unittest \
  test_boundaries.TeachingBoundaries.test_C05_reset_preserves_all_teaching_and_ai_rows_and_files \
  test_boundaries.AiBoundaries.test_D02_input_boundaries_and_actual_context_references -v
# 可选浏览器验收：仅创建新的合成记录，不覆盖旧测试或正常数据。
python3 tests/integration/anonymization_browser.py prepare
# 在15173登录输出的合成学生或隔离教师，按上述流程检查并保存。
python3 tests/integration/anonymization_browser.py verify
python3 tests/integration/environment.py stop
python3 tests/integration/environment.py stub-stop
bash .runtime/run-local.sh start
```

`.runtime/run-local.sh` 属于现有本地忽略配置，不提交。日常停止/启动分别使用该脚本的 `stop` / `start`；无需重新初始化数据库。

## 范围与限制

本轮是**页面展示脱敏**。原始数据库与授权接口仍保留真实原字段，开发者工具网络响应及数据库不是匿名数据集；这是保留接口契约和编辑原值的实现选择，不能将其当成访问控制或对外匿名API。历史兼容映射及负向测试会保留目标旧字符串，不代表它们还作为页面mock示例使用。

结构化姓名字段无论中文/英文均按角色编号展示。自由文本只替换已登记演示名称和有限学校名模式，不能可靠识别任意新输入的人名；图片像素不做OCR或打码。外部链接目标与下载的原始图片也不被改写。分享截图或新输入内容仍需避免放入可识别信息。

既有权限、登录凭据、访问码、workspace关系、评价历史和重置规则保持不变；未读取其他应用密钥，未上传本地配置、数据库、图片或日志。
