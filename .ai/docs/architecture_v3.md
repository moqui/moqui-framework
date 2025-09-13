社区电商存储与协作系统架构设计（v3）
1. 架构概述
   目标
   设计一个可扩展、安全的社区电商平台，集成 MinIO（S3 存储）、OnlyOffice（文档协作）和 Moqui（ERP 后端），使用 Moqui 原生 Screen 页面进行快速原型开发。系统支持主/子账户、分层权限（读/写/删）、文件操作、审计、配额管理、文档协作，提升商户协作粘性。
   范围

组件：Moqui 后端（Party/Security/Screen）、MinIO（存储）、OnlyOffice（协作）。
前端：Moqui Screen（快速原型，苹果风格），暂缓 miniapp 和 frontend 开发。
核心功能：
管理员：查看所有存储桶列表（用户信息、容量、配置入口）。
用户：查看/操作桶内文件/文件夹（上传、分享、下载、编辑）。
OnlyOffice：查看/编辑/审阅，保存版本。
权限管理：编辑桶用户（增删、读/写/删权限）。
分享：短链接（有效期、密码）。


约束：
性能：权限验证 <50ms，审计日志 <100ms，配额检查 <50ms。
安全：Moqui JWT 认证，SecurityGroup+MinIO 策略隔离，审计日志加密。
兼容性：Moqui Party/Security 与 MinIO/OnlyOffice 无缝集成。
开发：iFlow（Moqui Service/Screen），Gemini CLI（Screen XML）。
数据库：开发用 H2，生产用 PostgreSQL。
部署：开发本地启动，生产用 Docker（moqui/minio/onlyoffice）。
UI：苹果风格（浅色、圆角、简洁）。
审计日志：含 userId、action、file、timestamp、ipAddress，30 天清理。
桶名：merchant-{partyId}（关联商户名称，如 merchant_acme_001）。



2. 架构图
   Markdown 表格



组件
角色
输入
输出
依赖



Moqui 后端
Party/Security、MinIO 触发、Screen UI
用户注册、API 调用（JWT）、Screen 请求
UserAccount、MinIO 用户/桶、Screen 页面
mantle.usl、自定义 Service、Screen XML、JWT


MinIO
存储、权限、配额、审计
Moqui Service、S3 API
桶、文件、审计日志
mc admin JAR、webhook


OnlyOffice
查看/编辑/审阅、版本
MinIO S3 文件、JWT
编辑文件、版本
S3 插件、JWT


Mermaid 图
graph TD
A[用户] -->|登录（JWT）| B(Moqui 后端)
B -->|创建 UserAccount/Party| C[Moqui Party/Security]
B -->|渲染| D[Moqui Screen UI]
C -->|触发| E[MinIO]
E -->|S3 API| F[OnlyOffice]
D -->|REST API（JWT）| C
D -->|预签名 URL| E
E -->|Webhook| C[AuditLog Entity]
F -->|保存版本| E

数据流：

用户登录（JWT） → Moqui Screen（业务资源管理模块） → 显示存储桶列表。
管理员点击桶 → Moqui Screen 显示文件/文件夹 → 操作（上传、分享、下载、编辑）。
编辑打开 OnlyOffice 页面 → 保存至 MinIO（新版本）。
Moqui SecurityGroup 映射 MinIO 策略（读/写/删）。
MinIO webhook 推送审计日志（含 IP/时间）至 Moqui AuditLog。
配额超限触发 Screen 提示。

3. 组件详情
   3.1 Moqui 后端

角色：账户管理（主/子）、权限、MinIO 触发、Screen UI 渲染。
环境：
开发：H2 数据库，验证功能。
生产：PostgreSQL（待选型，如 PostgreSQL 15）。


部署：
开发：本地启动（gradlew run）。
生产：Docker（moqui/moqui-framework）。


实体：
Party：ORGANIZATION（主，如 merchant_acme_001），PERSON（子，如 sub_user_001）。
PartyRelationship：主/子关联（type=EMPLOYEE）。
PartyAttribute：标签（account_type=main/sub）。
UserAccount：登录凭据（userLoginId、password）。
SecurityGroup：组（SMALL_MERCHANT、MERCHANT_TEAM）。
SecurityPermission：权限（SECPTY_READ/WRITE/DELETE）。
UserGroupMember：用户组绑定。
AuditLog：审计（userId、action、file、timestamp、ipAddress）。


服务：
mantle.usl.UserAccountServices.create#UserAccount：创建账户。
custom.minio.CreateUserAndBucket：触发 MinIO 用户/桶（5GB）。
custom.bucket.ListBuckets：列出桶及概要（用户、容量、配置入口）。


Screen：
BusinessResourceManagement：存储桶列表（管理员）。
BucketDetail：桶内文件/文件夹列表、操作（上传/分享/下载/编辑）。
UserManagement：桶用户编辑（增删、权限配置）。


REST API（JWT）：
/mantle/user/UserAccount：账户管理。
/mantle/security/Permission：权限查询。
/mantle/audit/log：审计日志。
/mantle/bucket/list：桶列表（含用户、容量）。



3.2 MinIO

角色：存储、权限、配额、审计。
配置：
桶名：merchant-{partyId}（如 merchant_acme_001，关联商户名称）。
权限：策略 JSON 映射 SecurityGroup。
配额：默认 5GB（mc admin bucket quota）。
审计：Webhook 推送 AuditLog（含 IP/时间）。


API：
S3：GetObject、PutObject、DeleteObject。
管理：mc admin user add、mc admin policy attach。


部署：
开发：本地（minio server）。
生产：Docker（minio/minio）。



3.3 OnlyOffice

角色：文档查看/编辑/审阅、版本控制。
配置：
Docker：单实例，S3 插件连接 MinIO。
JWT：Moqui 提供 token。
功能：Track Changes、Comments、自动保存（30s）、版本控制。


API：回调保存（POST /save）。
部署：
开发：本地（documentserver）。
生产：Docker（onlyoffice/documentserver）。



3.4 Moqui Screen UI

角色：快速原型前端，苹果风格（浅色、圆角、简洁）。
Screen XML：
BusinessResourceManagement.xml：管理员存储桶列表（桶名、用户、容量、配置按钮）。
BucketDetail.xml：桶内文件/文件夹列表（上传/分享/下载/编辑）。
UserManagement.xml：桶用户编辑（增删、权限配置）。


UI 交互：
登录后：进入 BusinessResourceManagement：
管理员：显示桶列表（merchant-{partyId}，如 merchant_acme_001）。
概要：关联用户（UserAccount 列表）、容量（5GB/已用）、配置按钮（需 SECPTY_ADMIN）。


点击桶：跳转 BucketDetail：
显示文件/文件夹列表（名称、大小、修改时间）。
操作：上传（<form>）、分享（短链接弹窗）、下载（S3 URL）、编辑（新页面打开 OnlyOffice）。


OnlyOffice：编辑/审阅，保存至 MinIO（新版本）。
文件移动：弹窗显示目录列表（根目录为 /merchant-{partyId}/），选择目标后提交。
分享：弹窗配置短链接（有效期 1h-7d、密码可选）。
用户管理（需权限）：UserManagement 显示桶用户列表，支持增删、权限配置（read/write/delete）。


风格：苹果风格（浅色背景、圆角按钮、SF Pro 字体）。

4. 数据流与 API 规范
   4.1 数据流

登录：用户登录（JWT） → Moqui Screen BusinessResourceManagement → 桶列表。
桶管理：
管理员：REST /mantle/bucket/list 返回桶概要（用户、容量）。
配置：跳转 UserManagement（需 SECPTY_ADMIN）。


文件操作：
跳转 BucketDetail → S3 API（Get/Put/Delete） → OnlyOffice 协作。
移动：弹窗选择目录 → MinIO Put。
分享：生成预签名 URL（有效期/密码） → 显示短链接。


审计：MinIO webhook（含 IP/时间） → AuditLog → Screen 显示。
配额：MinIO Quota API → Screen 提示（超限：扩容）。
版本：OnlyOffice 保存 → MinIO 版本 → Screen 显示时间线。

4.2 API 规范

Moqui REST API（JWT）：
POST /mantle/user/UserAccount:
参数: { userLoginId, password, partyId, account_type }
响应: { success: true, userId }


GET /mantle/bucket/list:
参数: { userId }
响应: { buckets: [{ bucketName, users, capacity, used }] }


GET /mantle/audit/log:
参数: { userId, startTime, endTime }
响应: { logs: [{ userId, action, file, timestamp, ipAddress }] }




MinIO S3 API：
GET /merchant-{partyId}/{path}：获取文件。
PUT /merchant-{partyId}/{path}：上传/编辑。
DELETE /merchant-{partyId}/{path}：删除。


OnlyOffice 回调：
POST /save:
参数: { fileId, content, version }
响应: { success: true, versionId }





5. 伪代码
   5.1 Moqui Service: 创建用户和桶
   // custom.minio.CreateUserAndBucket
   def createUserAndBucket = { Map parameters ->
   def partyId = parameters.partyId
   def userLoginId = parameters.userLoginId
   def accountType = parameters.account_type
   // 创建 UserAccount 和 Party
   runService('mantle.usl.UserAccountServices.create#UserAccount', [
   userLoginId: userLoginId,
   partyId: partyId,
   account_type: accountType
   ])
   // 创建 PartyAttribute
   runService('mantle.party.PartyServices.create#PartyAttribute', [
   partyId: partyId,
   attrName: 'account_type',
   attrValue: accountType
   ])
   // 创建 MinIO 用户
   execCommand("mc admin user add myminio ${userLoginId} ${randomPassword}")
   // 创建桶（merchant-{partyId}，5GB）
   def bucketName = "merchant-${partyId}"
   execCommand("mc mb myminio/${bucketName}")
   execCommand("mc admin bucket quota myminio/${bucketName} --hard 5GB")
   // 分配策略
   def policy = accountType == 'main' ? 'readwrite' : 'readonly'
   execCommand("mc admin policy attach myminio ${policy} --user ${userLoginId}")
   return [successMessage: "用户和桶创建成功"]
   }

5.2 Moqui Screen: 存储桶列表
<screen name="BusinessResourceManagement" location="component://custom/screen/BusinessResourceManagement.xml">
<transition name="listBuckets" service="custom.bucket.ListBuckets"/>
<widgets>
<section name="bucketList" condition="user.hasPermission('SECPTY_ADMIN')">
<actions>
<service-call name="buckets" service-name="custom.bucket.ListBuckets"/>
</actions>
<widget>
<container style="apple-style">
<label text="存储桶列表" style="title"/>
<table items="buckets">
<column field="bucketName" header="桶名"/>
<column field="users" header="关联用户"/>
<column field="capacity" header="容量 (GB)"/>
<column field="used" header="已用 (GB)"/>
<column>
<link url="BucketDetail?bucketName=${bucketName}" text="查看"/>
<link url="UserManagement?bucketName=${bucketName}" text="配置" condition="user.hasPermission('SECPTY_ADMIN')"/>
</column>
</table>
</container>
</widget>
</section>
</widgets>
</screen>

5.3 MinIO 策略 JSON
{
"Version": "2012-10-17",
"Statement": [
{
"Effect": "Allow",
"Action": ["s3:GetObject"],
"Resource": ["arn:aws:s3:::merchant-{partyId}/*"]
},
{
"Effect": "Allow",
"Action": ["s3:PutObject"],
"Resource": ["arn:aws:s3:::merchant-{partyId}/contracts/*"],
"Condition": {"StringEquals": {"s3:prefix": "/contracts"}}
},
{
"Effect": "Allow",
"Action": ["s3:DeleteObject"],
"Resource": ["arn:aws:s3:::merchant-{partyId}/contracts/*"],
"Condition": {"StringEquals": {"aws:username": "{mainUserId}"}}
}
]
}

6. 非功能需求

性能：
权限验证：<50ms（Moqui SecurityGroup+MinIO 策略）。
审计日志：<100ms（webhook 至 AuditLog）。
配额检查：<50ms（mc admin API）。


安全：
JWT：Moqui 提供 token，保护 REST API 和 OnlyOffice。
隔离：SecurityGroup 映射 MinIO 策略；子账户限制路径。
审计：AES 加密（AuditLog）。


兼容性：
Moqui Party/Security 与 MinIO/OnlyOffice 兼容。
H2（开发）/Psql（生产）Entity 无缝迁移。


可扩展性：
MinIO 支持多租户桶（merchant-{partyId}）。
Moqui Entity 缓存支持 1000+ 商户。


部署：
开发：本地启动（Moqui H2、MinIO、OnlyOffice）。
生产：Docker（moqui/minio/onlyoffice）。



7. 风险缓解

权限冲突：SecurityGroup 强制层级；MinIO 策略验证 ARN。
版本合并冲突：OnlyOffice 优先最新编辑，Screen 弹窗提示。
审计溢出：30 天清理；支持 CSV 导出。
数据库差异：H2/Psql Entity 兼容；生产前迁移测试。

8. 开发任务分解



任务
模块
AI 工具
优先级
预计迭代
测试覆盖



Moqui 用户/Party 创建
Moqui 后端
iFlow
1
2
95%


MinIO 用户/桶/策略设置
MinIO
iFlow
1
2
95%


权限映射（SecurityGroup→策略）
Moqui+MinIO
iFlow
1
3
95%


配额管理（5GB 默认）
MinIO
iFlow
2
2
95%


审计日志（Webhook→AuditLog，含 IP）
MinIO+Moqui
iFlow
2
2
95%


OnlyOffice 集成（S3+JWT）
OnlyOffice
iFlow
3
3
95%


Moqui Screen（桶列表、文件操作、权限）
Moqui Screen
Gemini CLI
4
3
95%


总迭代：约 17 次（模块分割，<5 次/模块）。目录结构：

.ai/code/moqui/services/：Moqui Service（custom.minio.CreateUserAndBucket）。
.ai/code/moqui/screens/：Screen XML（BusinessResourceManagement.xml）。
.ai/code/minio/policies/：MinIO 策略 JSON。

9. 澄清点

UI 细节：Screen 页面布局是否需 mockup（如 Figma）？优先级（桶列表 vs. 文件操作）？
Psql 选型：生产环境 PostgreSQL 版本（如 15）或连接池配置？
Docker 配置：生产环境 Docker Compose 参数（如 MinIO 端口、OnlyOffice 内存）？
苹果风格：具体 UI 要求（如按钮颜色、字体大小）？

10. 下一步
    架构设计 v3 完成，存储于 .ai/docs/architecture_v3.md。请输入 /code 召唤程序员 Agent 实现代码（iFlow：Moqui Service/MinIO；Gemini CLI：Screen XML）。请提供 UI mockup 或优先级以完善设计。