社区电商存储与协作系统架构设计（v2）
1. 架构概述
   目标
   设计一个可扩展、安全的社区电商平台，集成 MinIO（S3 兼容存储）、OnlyOffice（文档协作）和 Moqui（ERP 后端），搭配 Vue（Web）和 HBuilder（小程序）前端。系统支持文件存储、分层权限（读/写/删）、审计、配额管理、文档协作，提升商户协作和粘性。
   范围

组件：Moqui 后端（Party/Security）、MinIO（存储）、OnlyOffice（协作）、Vue（Web）、HBuilder（小程序）。
核心功能：主/子账户创建、分层权限、文件移动冲突处理、配额限制、审计日志、OnlyOffice 查看/编辑/审阅、版本控制、分享（可选设置）。
约束：
性能：权限验证 <50ms，审计日志 <100ms，配额检查 <50ms。
安全：Moqui JWT 认证，Moqui SecurityGroup+MinIO 策略隔离，审计日志加密。
兼容性：Moqui Party/Security 与 MinIO/OnlyOffice 无缝集成。
开发：iFlow（Moqui Service）、Gemini CLI（Vue/HBuilder）。
新约束：
前后台交互基于 Moqui JWT（已实现）。
默认 MinIO 桶配额 5GB。
开发环境：Moqui 嵌入式数据库（H2）；生产环境：PostgreSQL。
开发环境：本地直接启动；生产环境：Docker 容器。
UI 风格：参考苹果设计（简洁、圆角、浅色主题）。
审计日志：包含 IP、时间等字段。
UI 交互需进一步明确。





2. 架构图
   Markdown 表格



组件
角色
输入
输出
依赖



Moqui 后端
Party/Security、MinIO 触发
用户注册、API 调用（JWT）
UserAccount、MinIO 用户/桶、SecurityGroup
mantle.usl、自定义 Service、JWT


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


Vue/HBuilder
UI（权限、文件操作、审计）
Moqui API（JWT）、MinIO URL
用户操作、审计视图
Vuetify、UniApp、苹果风格


Mermaid 图
graph TD
A[用户] -->|注册/登录（JWT）| B(Moqui 后端)
B -->|创建 UserAccount/Party| C[Moqui Party/Security]
C -->|触发| D[MinIO]
D -->|S3 API| E[OnlyOffice]
B -->|REST API（JWT）| F[Vue/HBuilder UI]
F -->|预签名 URL| D
D -->|Webhook| C[AuditLog Entity]
E -->|保存版本| D

数据流：

用户通过 Vue/HBuilder（JWT 认证）注册 → Moqui 创建 Party/UserAccount → 触发 MinIO 用户/桶。
Moqui SecurityGroup 映射 MinIO 策略（读/写/删）。
用户通过 Vue/HBuilder 访问文件 → MinIO S3 API → OnlyOffice 协作。
MinIO webhook 推送审计日志（含 IP/时间）到 Moqui AuditLog Entity。
Vue/HBuilder 显示审计、版本、配额提示。

3. 组件详情
   3.1 Moqui 后端

角色：管理账户（主/子）、权限、MinIO 触发。
环境：
开发：Moqui 嵌入式 H2 数据库，验证基础功能。
生产：PostgreSQL（需选型后迁移，Entity 兼容）。


部署：
开发：本地直接启动（gradlew run）。
生产：Docker 容器（moqui/moqui-framework 镜像）。


实体：
Party：ORGANIZATION（主账户，如 merchant_001），PERSON（子账户，如 sub_user_001）。
PartyRelationship：主/子关联（type=EMPLOYEE）。
PartyAttribute：主/子标签（account_type=main/sub）。
UserAccount：登录凭据（userLoginId、password）。
SecurityGroup：组（SMALL_MERCHANT、MERCHANT_TEAM）。
SecurityPermission：权限（SECPTY_READ/WRITE/DELETE）。
UserGroupMember：用户组绑定。
AuditLog（新）：审计日志（userId、action、file、timestamp、ipAddress）。


服务：
mantle.usl.UserAccountServices.create#UserAccount：创建 UserAccount/Party。
custom.minio.CreateUserAndBucket：触发 MinIO 用户/桶（配额 5GB）。


REST API（JWT 保护）：
/mantle/user/UserAccount：账户管理。
/mantle/security/Permission：权限查询。
/mantle/audit/log：审计日志查询。



3.2 MinIO

角色：文件存储、权限、配额、审计。
配置：
桶：merchant-{partyId}（如 merchant_001）。
权限：策略 JSON 映射 Moqui SecurityGroup（SECPTY_READ → s3:GetObject）。
配额：默认 5GB（mc admin bucket quota）。
审计：Webhook 推送 Moqui AuditLog（含 IP/时间）。


API：
S3：GetObject、PutObject、DeleteObject。
管理：mc admin user add、mc admin policy attach。


部署：
开发：本地 MinIO（minio server）。
生产：Docker（minio/minio 镜像）。



3.3 OnlyOffice

角色：文档查看/编辑/审阅、版本控制。
配置：
Docker：单实例，S3 插件连接 MinIO。
JWT：Moqui 提供 token，验证用户访问。
功能：Track Changes、Comments、自动保存（30s）、版本控制。


API：回调保存（POST /save，存 MinIO）。
部署：
开发：本地 OnlyOffice（documentserver）。
生产：Docker（onlyoffice/documentserver 镜像）。



3.4 Vue/HBuilder UI

角色：用户交互（权限、文件操作、审计、版本）。
框架：
Vue：Vuetify，苹果风格（简洁、圆角、浅色）。
HBuilder：UniApp，苹果风格（一致导航、动画）。


UI 风格：
参考苹果：浅色主题、圆角按钮、平滑过渡、简洁布局。
示例：权限选择用 <v-select>（圆角下拉）、审计用 <v-data-table>（简洁表格）。


组件（待交互明确）：
权限：<v-select> 设置组/权限。
文件移动：<v-autocomplete> 选择目录，<v-dialog> 冲突弹窗。
审计：<v-data-table> 显示 userId/action/file/timestamp/ipAddress。
版本：<v-timeline> 版本历史。
分享：<v-form> 配置有效期/密码/审阅。


部署：
开发：本地 Node.js（Vue），HBuilder IDE（UniApp）。
生产：Docker（Vue 静态文件，UniApp 编译）。



4. 数据流与 API 规范
   4.1 数据流

用户注册：
Vue/HBuilder（JWT） → Moqui REST /mantle/user/UserAccount → Party/UserAccount。
Moqui Service custom.minio.CreateUserAndBucket → MinIO 用户/桶（5GB）。


权限管理：
Moqui SecurityGroup → MinIO 策略 JSON。


文件操作：
Vue/HBuilder → MinIO S3 API → OnlyOffice 协作。
冲突：UI 弹窗（覆盖/取消） → MinIO Put/中止。


审计日志：
MinIO webhook（含 IP/时间） → Moqui AuditLog → Vue <v-data-table>。


配额检查：
MinIO Quota API → UI 提示（超限：扩容）。


版本控制：
OnlyOffice 保存 → MinIO 版本 → Vue <v-timeline>。



4.2 API 规范

Moqui REST API（JWT 保护）：
POST /mantle/user/UserAccount:
参数: { userLoginId, password, partyId, account_type }
响应: { success: true, userId }


GET /mantle/security/Permission:
参数: { groupId }
响应: { permissions: [{ permissionId, permTypeEnumId }] }


GET /mantle/audit/log:
参数: { userId, startTime, endTime }
响应: { logs: [{ userId, action, file, timestamp, ipAddress }] }




MinIO S3 API：
GET /merchant-{partyId}/{path}：获取文件。
PUT /merchant-{partyId}/{path}：上传/编辑文件。
DELETE /merchant-{partyId}/{path}：删除文件。


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
   def accountType = parameters.account_type // main/sub
   // 创建 Moqui UserAccount 和 Party
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
   // 创建桶（默认 5GB）
   execCommand("mc mb myminio/merchant-${partyId}")
   execCommand("mc admin bucket quota myminio/merchant-${partyId} --hard 5GB")
   // 分配策略
   def policy = accountType == 'main' ? 'readwrite' : 'readonly'
   execCommand("mc admin policy attach myminio ${policy} --user ${userLoginId}")
   return [successMessage: "用户和桶创建成功"]
   }

5.2 MinIO 策略 JSON
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

5.3 Vue 组件：审计表格
<template>
<v-data-table :items="auditLogs" :headers="headers" class="apple-style">
<template v-slot:item.action="{ item }">
<v-chip :color="item.action === 'share' ? 'blue' : 'green'" rounded>{{ item.action }}</v-chip>
</template>
</v-data-table>
</template>
<script>
export default {
  data() {
    return {
      headers: [
        { text: '用户', value: 'userId' },
        { text: '操作', value: 'action' },
        { text: '文件', value: 'file' },
        { text: '时间', value: 'timestamp' },
        { text: 'IP', value: 'ipAddress' }
      ],
      auditLogs: []
    }
  },
  async created() {
    const res = await fetch('/mantle/audit/log', {
      headers: { Authorization: `Bearer ${localStorage.getItem('jwt')}` }
    })
    this.auditLogs = await res.json()
  }
}
</script>
<style>
.apple-style {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  border-radius: 8px;
  background: #f5f5f7;
}
</style>

6. 非功能需求

性能：
权限验证：<50ms（Moqui SecurityGroup+MinIO 策略）。
审计日志：<100ms（webhook 至 AuditLog）。
配额检查：<50ms（mc admin API）。


安全：
JWT：Moqui 提供 token（已实现），保护 REST API 和 OnlyOffice。
隔离：Moqui SecurityGroup 映射 MinIO 策略；子账户限制路径。
审计加密：AuditLog 使用 AES。


兼容性：
Moqui Party/Security 与 MinIO/OnlyOffice 兼容。
开发（H2）/生产（Psql）Entity 无缝迁移。


可扩展性：
MinIO 支持多租户桶（merchant-{partyId}）。
Moqui Entity 缓存支持 1000+ 商户。


部署：
开发：本地启动（Moqui H2、MinIO、OnlyOffice）。
生产：Docker 容器（moqui/moqui-framework、minio/minio、onlyoffice/documentserver）。



7. 风险缓解

风险：权限冲突（子账户绕过限制）。
缓解：Moqui SecurityGroup 强制层级；MinIO 策略验证 ARN。


风险：OnlyOffice 版本合并冲突。
缓解：UI 提示冲突；优先最新编辑。


风险：审计日志溢出。
缓解：30 天自动清理；支持 CSV 导出。


风险：开发/生产环境数据库差异。
缓解：Moqui Entity 兼容 H2/Psql；生产前迁移测试。



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


Vue UI（权限、文件、审计）
Vue
Gemini CLI
4
3
95%


HBuilder UI（小程序）
HBuilder
Gemini CLI
4
2
95%


总迭代：约 19 次（模块分割，<5 次/模块）。目录结构：

.ai/code/moqui/services/：Moqui Service（custom.minio.CreateUserAndBucket）。
.ai/code/minio/policies/：MinIO 策略 JSON。
.ai/code/vue/：Vue 组件。
.ai/code/hbuilder/：UniApp 组件。

9. 澄清点

UI 交互细节：需进一步明确 Vue/HBuilder 页面布局（如权限勾选样式、冲突弹窗动画）。
Psql 选型细节：生产环境 Psql 版本（e.g., PostgreSQL 15）或配置（如连接池）？
Docker 部署配置：生产环境 Docker Compose 参数（e.g., MinIO 端口、OnlyOffice 内存）？
苹果风格细化：具体 UI 元素（如按钮颜色、字体大小）？

10. 下一步
    架构设计 v2 完成，存储于 .ai/docs/architecture_v2.md。请输入 /code 召唤程序员 Agent 实现代码（iFlow：Moqui Service/MinIO；Gemini CLI：Vue/HBuilder）。请提供 UI 交互细节或其他澄清点以完善设计。