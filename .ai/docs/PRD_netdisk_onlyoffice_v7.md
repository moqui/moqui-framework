PRD_netdisk_onlyoffice_v7.md
1. 产品概述
   （不变，略。新增：Moqui Party/Security 模块深度整合，明确权限/审计实现。）
2. 目标用户和市场分析
   （不变，略。）
3. 功能需求详细描述
   功能按层级分解，优先 MinIO（权限/配额/审计），再 OnlyOffice。Moqui Party/Security 驱动账号/权限。
   底层模块：网盘存储系统（MinIO Docker）

文件上传/下载API：Moqui Service 调用。
存储管理：Bucket（merchant-{partyId}），路径隔离。
隔离机制：小商户（单桶+主）；大商户（单桶+主/子+路径）。
账号创建与管理：
运维：全局（mc admin）。
商户主：Moqui Party（ORGANIZATION）注册触发 UserAccount（mantle.usl.UserAccountServices），自动创建 MinIO 用户（mc admin user add）。
子账户：主创建（PERSON，PartyAttribute tag="sub"），绑定 PartyRelationship（e.g., EMPLOYEE）。
批量导入：Moqui CSV/REST（/mantle/user/UserAccount），字段：partyId, userLoginId, groupId, accountType (main/sub).


权限配置：
层次包含：读 (SECPTY_READ, GetObject)；写 (SECPTY_WRITE, Get+Put)；删 (SECPTY_DELETE, Get+Put+Delete)。
继承/覆盖：桶级继承；文件变更覆盖（MinIO 政策 ARN）。
移动文件/目录：需写权限；冲突处理：目标同名，UI 弹窗（覆盖/取消）。
模式化：Moqui SecurityGroup 强制（e.g., SMALL_MERCHANT 禁子；MERCHANT_TEAM 允主/子）。
Moqui 实现：SecurityPermission（SECPTY_READ/WRITE/DELETE），SecurityGroup（MERCHANT_GROUP）。


配额管理：mc admin JAR 设桶配额。超限：禁新上传，保留数据，UI 提示“桶满，扩容至 X GB”。运维/主修改配额。
审计功能：MinIO webhook 推送 Moqui Entity（AuditLog：userId, action, file, timestamp）。Vue/HBuilder 显示分页日志。

中层模块：OnlyOffice集成（Docker）

容器部署：单实例 MinIO + OnlyOffice。
API回调：文件导入/保存，权限验证。
分享集成：预签名 URL，默认 1 天；可选（UI：有效期 1h-7d、密码、审阅）。
查看/编辑/审阅：修订（Track Changes+Comments）。
多版本文档：MinIO 版本控制；权限过滤（读列表，写回滚）；UI 时间线。
文档更新保存：自动（30s）+手动；冲突合并。

上层模块：前端/app交互

前端：Vue（Node.js），UI：权限（级联）、移动（下拉+冲突弹窗）、版本时间线、分享 checkbox、审计分页。
小程序：HBuilder（UniApp），同前端。
集成点：Moqui Party（Party/PartyGroup/PartyRelationship/PartyAttribute）+Security（SecurityGroup/Permission）。

整体集成约束

权限优先：Moqui SecurityGroup 映射 MinIO 政策。
依赖：Moqui Service（mantle.usl）触发 MinIO。

4. 用户故事和使用场景

用户故事1（Moqui 权限）：作为商户，我注册后希望 Moqui 自动创建 MinIO 用户/桶，权限映射读/写/删。验收：SecurityGroup 绑定生效。
用户故事2（冲突）：移动文件同名，UI 弹窗选择覆盖/取消。验收：Put/中止正确。
用户故事3（审计）：查看谁登录/分享合同。验收：Vue 审计分页显示。
用户故事4（配额）：超限后禁上传，UI 提示扩容。验收：提示+保留数据。
用户故事5（子账户）：主创建子（PartyRelationship+tag="sub"），权限限制。验收：子无桶权。
使用场景：
场景A：注册触发 Moqui Service，创建 MinIO 用户/桶，SecurityGroup 映射写权限。
场景B：移动图片，UI 弹窗覆盖，MinIO Put 执行。
场景C：审计显示“X 分享合同 Y，Z 查看”。
场景D：桶超限，Vue 提示“扩容至 10GB”。
场景E：主账户用 Moqui API 导入子账户，PartyAttribute 标注 sub。



5. 非功能性需求

性能：权限映射<50ms；审计记录<100ms；配额检查<50ms。
安全与隔离：Moqui SecurityGroup+MinIO 政策；审计加密。
兼容性：Moqui Party/Security + MinIO 无冲突。
可用性：UI 简化冲突/配额/审计；Vue/HBuilder 一致。
可扩展性：支持配额调整；审计导出。
开发约束：任务切分（MinIO 权限/配额→OnlyOffice）；测试覆盖>95%.

6. 产品路线图

短期：MinIO 权限/配额/审计+Moqui Party/Security。
中期：OnlyOffice 集成+版本/分享。
长期：电商扩展。

7. 成功指标和KPI

功能成功：权限映射准确100%；审计覆盖100%；配额提示100%.
粘性：审计查看率>30%；版本交互率>50%.
效率：Moqui 导入转化95%；冲突处理<2s.
