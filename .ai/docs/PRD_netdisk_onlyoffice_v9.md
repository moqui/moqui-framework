PRD_minio_onlyoffice_v9.md
1. 产品概述
   （不变，略。新增：系统地图增强开发可执行性。）
2. 目标用户和市场分析
   （不变，略。）
3. 系统地图（新）



组件
功能
输入
输出
依赖



Moqui Backend
Party/Security 管理，触发 MinIO
UserAccount/Party creation
MinIO user/bucket, SecurityGroup
mantle.usl, custom Service


MinIO
桶/权限/配额/审计
Moqui Service, S3 API
Bucket, audit logs
mc admin JAR, webhook


OnlyOffice
查看/编辑/审阅/版本
MinIO S3 file, JWT
Edited file, versions
S3 API, JWT


Vue/HBuilder
UI（权限/移动/版本/审计）
Moqui API, MinIO URL
User actions, audit view
Vuetify, UniApp


数据流：Moqui 注册→Service 触发 MinIO 用户/桶→S3 API 存取文件→OnlyOffice 协作→Vue/HBuilder 显示。
4. 功能需求详细描述
   底层模块：网盘存储系统（MinIO Docker）

文件上传/下载API：Moqui Service 调用。
存储管理：Bucket（merchant-{partyId}），路径隔离。
隔离机制：小商户（单桶+主）；大商户（单桶+主/子+路径）。
账号创建与管理：
运维：全局（mc admin）。
商户主：Moqui Party（ORGANIZATION）注册触发 UserAccount（mantle.usl.UserAccountServices.create#UserAccount），调用 custom.minio.CreateUserAndBucket。
子账户：主创建（PERSON, PartyAttribute: account_type=sub, PartyRelationship: EMPLOYEE）。
批量导入：Moqui CSV/REST（/mantle/user/UserAccount），字段：partyId, userLoginId, groupId, account_type.


权限配置：
层次包含：读 (SECPTY_READ, GetObject)；写 (SECPTY_WRITE, Get+Put)；删 (SECPTY_DELETE, Get+Put+Delete).
继承/覆盖：桶级继承；文件变更覆盖。
移动文件/目录：需写权限；冲突处理：目标同名，UI 弹窗（覆盖/取消）。
模式化：SecurityGroup（SMALL_MERCHANT 禁子；MERCHANT_TEAM 允主/子）。
示例政策 JSON（新）：{
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
"Resource": ["arn:aws:s3:::merchant-{partyId}/contracts/*"]
}
]
}




配额管理：mc admin JAR 设配额。超限：禁新上传，保留数据，UI 提示“桶满，扩容至 X GB”。权限（新）：运维/主可调整。
审计功能：MinIO webhook 推 Moqui Entity（AuditLog：userId, action, file, timestamp）。清理（新）：保留 30 天，自动清理。

中层模块：OnlyOffice集成（Docker）

容器部署：单实例 MinIO + OnlyOffice.
API回调：文件导入/保存，权限验证.
分享集成：预签名 URL，默认 1 天；可选（UI：1h-7d、密码、审阅）.
查看/编辑/审阅：修订（Track Changes+Comments）.
多版本文档：MinIO 版本控制；权限过滤；UI 时间线.
冲突合并（新）：多人审阅，OnlyOffice 合并变更，优先最新编辑，UI 提示冲突.
文档更新保存：自动（30s）+手动.

上层模块：前端/app交互

前端：Vue（Vuetify），UI：权限（级联勾选）、移动（下拉+冲突弹窗）、版本时间线、分享 checkbox、审计表格（分页）.
小程序：HBuilder（UniApp），同前端.
UI 示例（新）：
Vuetify：<v-data-table :items="auditLogs" :headers="['user', 'action', 'time']"></v-data-table>
UniApp：<uni-list :data="versions" @click="openVersion"></uni-list>


集成点：Moqui Party/Security.

整体集成约束

权限优先：Moqui SecurityGroup 映射 MinIO 政策.
依赖：Moqui Service（mantle.usl + custom.minio.CreateUserAndBucket）.
Service 示例（新）：// custom.minio.CreateUserAndBucket
def createUserAndBucket = { Map parameters ->
def partyId = parameters.partyId
def userLoginId = parameters.userLoginId
// Moqui: create UserAccount
runService('mantle.usl.UserAccountServices.create#UserAccount', [userLoginId: userLoginId, partyId: partyId])
// MinIO: create user/bucket
execCommand("mc admin user add myminio ${userLoginId} ${randomPassword}")
execCommand("mc mb myminio/merchant-${partyId}")
// Assign policy
execCommand("mc admin policy attach myminio readwrite --user ${userLoginId}")
return [successMessage: "User and bucket created"]
}



5. 用户故事和使用场景

用户故事1（Moqui 权限）：注册触发 Service，创建 MinIO 用户/桶，SecurityGroup 映射读/写。验收：绑定准确.
用户故事2（子账户）：主创建子（account_type=sub, EMPLOYEE），权限限制。验收：子无桶权.
用户故事3（冲突）：移动文件同名，UI 弹窗选择覆盖/取消。验收：Put/中止正确.
用户故事4（审计）：查看登录/分享日志。验收：Vue 表格显示，30 天清理.
用户故事5（配额）：超限禁上传，UI 提示扩容，运维/主调整。验收：数据保留.
使用场景：
场景A：注册触发 minio.CreateUserAndBucket，SecurityGroup 映射 SECPTY_WRITE.
场景B：子账户只读 /images/*.
场景C：移动图片，UI 弹窗覆盖，MinIO Put.
场景D：审计显示“X 分享合同 Y，Z 查看”，30 天后清理.
场景E：桶超限，Vue 提示“扩容至 10GB”，主调整.



6. 非功能性需求

性能：权限映射<50ms；审计记录<100ms；配额检查<50ms.
安全与隔离：Moqui SecurityGroup+MinIO 政策；审计加密.
兼容性：Moqui Party/Security+MinIO 无冲突.
可用性：UI（Vuetify/UniApp）简化冲突/配额/审计.
可扩展性：支持配额调整；审计导出.
开发约束：任务切分（MinIO→OnlyOffice）；测试覆盖>95%.

7. 产品路线图

短期：MinIO 权限/配额/audit+Moqui Party/Security.
中期：OnlyOffice 集成+版本/分享.
长期：电商扩展.

8. 成功指标和KPI

功能成功：权限映射准确100%；审计覆盖100%；配额提示100%.
粘性：审计查看率>30%；版本交互率>50%.
效率：Moqui 导入转化95%；冲突处理<2s.
