AI 辅助开发规范（社区电商网盘项目 v1.1）
本规范为 iFlow 提供 Moqui 框架下 netdisk 模块的开发指南，基于社区电商网盘项目（E:\moqui-framework），整合团队协作流程和 AI 辅助开发原则。iFlow 应使用 .ai 目录资源（.ai/docs/、.ai/code/、.ai/assets/、.ai/prompts/）快速了解任务，确保模块化、可维护性和系统稳定性。iFlow 操作受限，避免不可控改动导致系统故障。
1. 项目概述

项目：社区电商网盘（netdisk 模块）
技术栈：
框架：Moqui（H2 开发，PostgreSQL 16 生产）
存储：MinIO（端口 9000，5GB 配额）
编辑：OnlyOffice（端口 80，文件预览/编辑）
UI：Moqui Screen（Webroot 风格，表格/按钮）
认证：JWT


功能：
存储桶管理：列表、创建、删除（5GB 配额）
文件操作：上传、下载、分享（7 天有效期，待澄清密码选项）
用户管理：增删、权限（SECPTY_ADMIN、SECPTY_READ）
馆点管理：待定（需澄清字段，如 ID、名称）
审计日志：记录操作（含 ipAddress）


目录结构：
runtime/component/netdisk/：模块目录（基于 example）
component.xml：模块定义
service/netdisk/NetdiskServices.xml：服务（如 create#UserAndBucket）
screen/BusinessResourceManagement.xml：桶列表 UI
entity/AuditLog.xml：审计日志
minio/merchant_policy.json：MinIO 策略


.ai/docs/：
PRD_netdisk_onlyoffice_v9.md：需求
architecture_v3.md：架构
code_implementation_v1.md：代码实现
test_cases_v1.md：测试用例


.ai/code/：Moqui Service、Screen XML、MinIO 策略模板
.ai/assets/：上图网盘使用手册.docx（UI 参考，需截图澄清）
.ai/prompts/：
Gemini_Code_Assist_Guide_v1.md：Gemini CLI 指引
Step1.PM_Agent.md：产品经理提示词
Step3.Programmer_Agent.md：程序员提示词
Step4.Tester_Agent.md：测试提示词





2. iFlow 团队协作流程
   2.1 开发流程

需求分析：
读取 .ai/docs/PRD_netdisk_onlyoffice_v9.md 和 .ai/docs/architecture_v3.md。
输出：更新 PRD（.ai/docs/PRD_netdisk_onlyoffice_v10.md）。
指令：/产品（读取 .ai/prompts/Step1.PM_Agent.md）。


UI/UX 设计：
基于 .ai/assets/上图网盘使用手册.docx，设计 Webroot 风格 UI（表格、按钮）。
输出：DESIGN_SPEC.md（存储于 .ai/docs/）。
指令：/设计（读取 .ai/prompts/Design_Agent.md，待补充）。


开发：
后端：生成 Moqui Service（NetdiskServices.xml）、Entity（AuditLog.xml）、MinIO 策略（merchant_policy.json）。
前端：生成 Moqui Screen（BusinessResourceManagement.xml、BucketDetail.xml）。
输出：代码存储于 runtime/component/netdisk/。
指令：/后端（.ai/prompts/Step3.Programmer_Agent.md）、/前端（待补充）。


测试：
运行 JUnit、Selenium、JMeter（.ai/tests/）。
输出：test_report_v1.md（存储于 .ai/docs/）。
指令：/测试（.ai/prompts/Step4.Tester_Agent.md）。


上线：
部署到 PostgreSQL 16、MinIO、OnlyOffice。
监控：响应时间、系统可用性。



2.2 沟通协作规范

日会：每日同步进度，记录于 .ai/docs/daily_notes/。
周报：总结进展，存储于 .ai/docs/weekly_reports/。
文档协作：使用 Markdown（.md）格式，存储于 .ai/docs/。
版本管理：Git 提交规范（feat: add bucket list），分支（feature/netdisk）。

2.3 质量保证

代码审查：Service、Screen XML 需审查，提交前运行 ./gradlew build。
测试覆盖：功能测试覆盖率 >90%（.ai/tests/）。
性能标准：页面加载 <2 秒（Webroot 风格）。
用户体验：支持无障碍设计（待确认 UI 截图）。

3. 代码开发规范
   3.1 基础原则

模块化：
使用 Moqui 的 Service、Entity、Screen 模块化结构。
避免全局变量，优先使用 Moqui 的 ECI（Entity Context Interface）。


最小入侵：
新代码存储于 runtime/component/netdisk/，不修改 mantle、example 或其他组件。
确保集成不破坏现有 Moqui 架构（H2/PostgreSQL 16、MinIO 9000、OnlyOffice 80）。


不随意重构：
禁止重构 .ai/code/ 或 runtime/component/example/，除非用户明确要求并提供理由。
若重构，提供迁移说明（如 Entity 数据迁移脚本）和兼容性测试点。



3.2 代码内容规范

最小改动修复：
修复 bug 时仅修改必要代码，添加注释（如 // 修复：桶列表排序错误）。
确保兼容性（H2/PostgreSQL 16、MinIO 9000、OnlyOffice 80）。
提供测试点（如多用户并发上传、分享链接有效期）。


代码风格：
遵循 Moqui 规范：XML 缩进 2 空格，Groovy 使用 camelCase。
Service 文件：verb#noun 命名（如 create#UserAndBucket）。
Screen 文件：Webroot 风格，优先 <table> 展示列表。


错误处理与日志：
使用 <check-errors> 或 try-catch 处理 Service 错误。
日志：仅用于调试（logger.info），禁止输出敏感信息（如 JWT token、MinIO 密钥）。
用户提示：友好提示（如 “桶创建失败，请检查权限”）。


性能与安全：
性能：避免深层循环（如遍历 MinIO 桶列表），使用缓存（如 eci.cache）。
安全：防御 XSS（Screen 输入过滤）、Token 伪造（JWT 验证）。
监控：记录内存使用（IDEA 配置 -Xmx4096m），避免阻塞操作。



3.3 附加约束

iFlow 操作限制：
禁止修改：核心组件（mantle、example、moqui-fop 等）、build.gradle、数据库配置。
禁止生成：非 Moqui 代码（如 Vue、React、uni-app 组件）。
禁止直接修改：用户代码，仅提供参考（如 XML、JSON 文件）。
代码存储：新代码必须存储于 runtime/component/netdisk/ 或 .ai/docs/。
超出范围回复：
根据项目规范，此操作超出指导范围，请提供更多上下文。




可维护性：
单一职责：Service 专注业务逻辑，Screen 专注展示。
注释：复杂逻辑（如 MinIO 策略、JWT 验证）需说明意图。


可持续开发：
扩展性：支持新功能（如馆点管理）模块化添加。
绿色编码：减少 MinIO API 调用，优化 OnlyOffice 加载。



4. iFlow 使用指南
   4.1 启动方式

初始化：运行 /产品 读取 .ai/prompts/Step1.PM_Agent.md，分析 PRD。
后续指令：
/设计：生成 UI 设计（.ai/docs/DESIGN_SPEC.md）。
/后端：生成 Service、Entity、MinIO 策略。
/前端：生成 Screen XML。
/测试：运行测试，生成报告。


直接描述：若无指令，直接描述需求，iFlow 将召唤 PM Agent。

4.2 使用 .ai 资源

.ai/docs/：
PRD_netdisk_onlyoffice_v9.md：核心需求（存储桶、文件、用户、馆点）。
architecture_v3.md：架构（Moqui+MinIO+OnlyOffice）。
code_implementation_v1.md：现有代码参考。
test_cases_v1.md：测试用例（JUnit、Selenium、JMeter）。


.ai/code/：模板（Service、Screen XML、MinIO 策略）。
.ai/assets/：上图网盘使用手册.docx（UI 参考，需截图澄清）。
.ai/prompts/：
Gemini_Code_Assist_Guide_v1.md：Gemini CLI 命令。
Step1.PM_Agent.md：产品经理提示词。
Step3.Programmer_Agent.md：程序员提示词。
Step4.Tester_Agent.md：测试提示词。



4.3 示例任务

生成 Service：gemini generate --template runtime/component/example/service/example/ExampleServices.xml \
--docs .ai/docs/PRD_netdisk_onlyoffice_v9.md \
--output runtime/component/netdisk/service/netdisk/NetdiskServices.xml


生成 Screen：gemini generate --template runtime/component/example/screen/ExampleApp/Example.xml \
--docs .ai/docs/architecture_v3.md \
--output runtime/component/netdisk/screen/BusinessResourceManagement.xml


生成 MinIO 策略：gemini generate --template .ai/code/minio/policies/merchant_policy.json \
--docs .ai/docs/architecture_v3.md \
--output runtime/component/netdisk/minio/merchant_policy.json


测试：gemini test --type all --file .ai/tests/ --code runtime/component/netdisk/ \
--env moqui_h2,minio_9000,onlyoffice_80 --output .ai/docs/test_report_v1.md



5. 成功指标

产品指标：
用户增长：月活跃用户（MAU）>1000，留存率 >70%。
交易：文件上传/分享成功率 >95%。
运营：桶列表加载时间 <2 秒。


技术指标：
性能：API 响应时间 <500ms，系统可用性 >99.9%.
质量：缺陷密度 <0.1/千行，测试覆盖率 >90%.
效率：迭代周期 <2 周.



6. 初始化 ASCII 艺术
```aiexclude
   SSSSSS  TTTTTTT EEEEEEE PPPPPP  UU   UU PPPPPP
   SS        TTT   EE      PP   PP UU   UU PP   PP
   SSSSSS    TTT   EEEE    PPPPPP  UU   UU PPPPPP
       SS    TTT   EE      PP      UU   UU PP
   SSSSSS    TTT   EEEEEEE PP       UUUUU  PP
```

7. iFlow 操作限制

禁止修改：
核心组件（mantle、example、moqui-fop 等）。
build.gradle、数据库配置（default-conf/moqui-conf.xml）。
系统文件（runtime/lib/、.ai/docs/ 核心文档）。


禁止生成：
非 Moqui 代码（如 Vue、React、uni-app）。
与项目无关的代码（如 H5 小程序组件）。


代码存储：
新代码必须存储于 runtime/component/netdisk/ 或 .ai/docs/。
禁止覆盖现有文件（如 NetdiskServices.xml），需明确用户确认。


安全检查：
每生成代码，检查 XSS、SQL 注入、JWT 泄露风险。
运行 ./gradlew build 和 gemini validate 验证兼容性。


测试要求：
每改动需运行 gemini test --type all。
测试覆盖率 >90%，报告存储于 .ai/docs/test_report_v1.md。


超出范围：
若请求超出 Moqui 或 netdisk 范围，回复：
根据项目规范，此操作超出指导范围，请提供更多上下文。




