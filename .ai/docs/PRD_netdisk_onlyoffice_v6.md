PRD_netdisk_onlyoffice_v6.md
1. 产品概述
   产品名称
   社区电商基础模块：网盘与OnlyOffice集成（CommunityEcom Storage & Collab）
   产品描述
   使用 Docker 版 MinIO（S3 网盘）+ OnlyOffice（文档协作），支持社区小商户存储商品图片/合同、实时协作。模块集成 Moqui 后台（Party/权限模块）、Vue 前端、HBuilder 小程序。功能模块化，优先 MinIO 权限/配额，再 OnlyOffice，提升用户粘性。
   产品目标

解决文件存储/协作痛点，权限安全，容量可控。
提升粘性：协作/审计功能鼓励商户频繁使用。
模块化开发：Moqui 风格后台，Vue/HBuilder 前端，使用 Gemini CLI/iFlow。

版本信息

迭代版本：v1.2（新增冲突/审计/配额/Moqui 整合）
目标发布：短期 MinIO 集成，次期 OnlyOffice。

2. 目标用户和市场分析
   （不变，略。新增：审计/配额提升企业商户信任。）
3. 功能需求详细描述
   功能按层级分解，优先 MinIO（权限/配额），再 OnlyOffice。集成 Moqui Party/Security。
   底层模块：网盘存储系统（MinIO Docker）

文件上传/下载API：多格式，Moqui 集成。
存储管理：商户级 Bucket（merchant-{id}），路径隔离。
隔离机制：小商户（单桶+主账户）；大商户（单桶+主/子账户+路径）。
账号创建与管理：
运维：全局（mc admin）。
商户主：Moqui Party（ORGANIZATION）注册自动创建（UserAccount+Access Key）。
子账户：主创建（PERSON，tag="sub"），无桶权，继承部分权限。
批量导入：Moqui API/CSV（addUserAccountToParty），自动绑定桶/组/标签。


权限配置：
层次包含：读 (GetObject)；写 (Get+Put)；删 (Get+Put+Delete)。
继承/覆盖：桶级继承目录/文件；文件变更覆盖（政策附加ARN）。
移动文件/目录：需源/目标写权限；冲突处理（新）：目标同名文件，UI 提示选择（覆盖/取消，Put/中止）。
模式化：小商户（单主）；大商户（主/子+路径）。Moqui SecurityGroup 强制（e.g., 小商户组禁子）。


配额管理（新）：mc admin JAR 设桶配额（Quota API）。超限：保留数据，禁新上传，UI 提示“桶满，需扩容”。运维/主可修改配额。
审计功能（新）：MinIO 审计 webhook（推 Moqui 存储），记录登录/分享/访问（谁、时间、操作）。

中层模块：OnlyOffice集成（Docker）

容器部署：单实例 MinIO + OnlyOffice。
API回调：文件导入/保存，权限验证。
分享集成：预签名 URL，默认 1 天无密码/审阅；可选（UI checkbox：1h-7d、密码、审阅）。
查看/编辑/审阅：预览；修改；修订（Track Changes+Comments）。
多版本文档：MinIO 版本控制；权限过滤（读版本列表，写回滚）；UI 时间线列表。
文档更新保存：自动（30s）+手动；冲突合并。

上层模块：前端/app交互

前端：Vue（Node.js），UI 含权限（级联勾选）、移动（备选目录下拉+冲突提示）、版本时间线、分享 checkbox、审计视图（登录/分享日志）。
小程序：HBuilder（UniApp），同前端功能。
集成点：Moqui Party/Security 同步主/子/权限/标签；批量导入后台。

整体集成约束

权限优先：继承覆盖+Moqui SecurityGroup 验证。
依赖：Moqui Party（Party/PartyGroup/UserAccount）+Security（Permission/Group）。

4. 用户故事和使用场景

用户故事1（冲突处理）：作为用户，我移动文件时，若目标有同名，需选择覆盖/取消。验收：UI 提示，操作正确。
用户故事2（审计）：作为商户，我希望查看谁访问了分享链接/登录。验收：审计视图显示日志。
用户故事3（配额）：作为商户，我超限后希望保留数据，收到扩容提示。验收：禁新上传，UI 提示。
用户故事4（Moqui 组）：作为主，我用 Moqui 组管理子账户，权限+标签区分。验收：组绑定正确。
用户故事5（版本交互）：作为用户，我查看版本列表，权限过滤后回滚。验收：时间线 UI，OnlyOffice 打开。
使用场景：
场景A：移动图片，目标同名，UI 弹窗选择覆盖，Put 执行。
场景B：审计显示用户 X 分享合同 Y，时间 Z，查看者列表。
场景C：桶超限，上传失败，UI 提示“扩容至 10GB”。
场景D：Moqui 导入 100 商户，PartyGroup 自动设主/子标签+权限。
场景E：分享合同，可选密码+审阅，打开 OnlyOffice 修订模式。



5. 非功能性需求

性能：冲突处理<1s；审计记录<100ms；配额检查<50ms。
安全与隔离：Moqui SecurityGroup 强制；审计加密；配额严格。
兼容性：MinIO Quota API + Moqui Party/Security 无冲突。
可用性：UI 简化冲突/配额提示；审计视图分页。
可扩展性：支持配额动态调整；审计导出 CSV。
开发约束：任务切分（MinIO 权限/配额→OnlyOffice）；测试覆盖冲突/审计/配额>95%。

6. 产品路线图

短期：MinIO 权限/配额/审计+Moqui 组。
中期：OnlyOffice 集成+版本/分享。
长期：电商扩展（版本链接订单）。

7. 成功指标和KPI

功能成功：冲突处理准确100%；审计覆盖率100%；配额超限提示率100%.
粘性：审计查看率>30%；版本交互率>50%.
效率：Moqui 导入转化95%；冲突选择<2s.
