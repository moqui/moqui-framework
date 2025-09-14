Gemini Code Assist 使用指引（v1）
1. 概述
   本指引说明如何使用 Gemini Code Assist（通过 Gemini CLI）为社区电商网盘项目生成和验证 netdisk 模块代码，参考 E:\moqui-framework\runtime\component\example\ 模块，加载到 myaddons.xml。项目基于 Moqui（H2 开发/PostgreSQL 16 生产）、MinIO（端口 9000）、OnlyOffice（端口 80）、Moqui Screen（Webroot 风格）、JWT 认证、5GB 配额，功能包括存储桶管理、文件操作、用户管理、馆点管理。
2. 项目上下文

文档：E:\moqui-framework\.ai\docs\
PRD_netdisk_onlyoffice_v9.md：需求（存储桶、文件、用户、馆点）。
architecture_v3.md：架构（Moqui+MinIO+OnlyOffice）。
code_implementation_v1.md：现有代码（Service、Screen、MinIO 策略）。
test_cases_v1.md：测试用例（JUnit、Selenium、JMeter）。


代码：E:\moqui-framework\.ai\code\
Moqui Service、Screen XML、MinIO 策略。


资产：E:\moqui-framework\.ai\assets\
上图网盘使用手册.docx：功能描述（桶列表、文件操作、权限）。


参考模块：E:\moqui-framework\runtime\component\example\
结构：component.xml、service/、screen/、entity/、data/。


新模块：E:\moqui-framework\runtime\component\netdisk\
加载：E:\moqui-framework\myaddons.xml。



3. Gemini CLI 配置

安装：参考 https://x.ai/api，安装 Gemini CLI。
初始化：gemini init --project-path E:\moqui-framework\.ai\


环境：
开发：Moqui（H2，gradlew run）、MinIO（本地，9000）、OnlyOffice（本地，80）。
生产：Moqui（PostgreSQL 16）、MinIO（Docker，9000）、OnlyOffice（Docker，80）。
工具：JUnit 5、Selenium WebDriver、JMeter、Codecov。



4. 生成代码
   4.1 创建模块结构
   gemini generate --template runtime\component\example\component.xml \
   --output runtime\component\netdisk\component.xml \
   --replace "example=netdisk"

4.2 生成 Moqui Service
gemini generate --template .ai\code\moqui\services\CustomMinioServices.groovy \
--docs .ai\docs\architecture_v3.md \
--output runtime\component\netdisk\service\netdisk\NetdiskServices.xml

4.3 生成 Moqui Screen
gemini generate --template .ai\code\moqui\screens\BusinessResourceManagement.xml \
--docs .ai\docs\architecture_v3.md \
--output runtime\component\netdisk\screen\BusinessResourceManagement.xml

4.4 生成 MinIO 策略
gemini generate --template .ai\code\minio\policies\merchant_policy.json \
--docs .ai\docs\architecture_v3.md \
--output runtime\component\netdisk\minio\merchant_policy.json

4.5 生成初始数据
gemini generate --template runtime\component\example\data\ExampleSecurityData.xml \
--docs .ai\docs\PRD_netdisk_onlyoffice_v9.md \
--output runtime\component\netdisk\data\NetdiskSecurityData.xml

4.6 更新 myaddons.xml

手动编辑 E:\moqui-framework\myaddons.xml：<moqui.basic.RootMoquiConf>
<component-list>
<component name="netdisk" location="runtime/component/netdisk"/>
</component-list>
</moqui.basic.RootMoquiConf>



5. 验证代码
   gemini validate --code runtime\component\netdisk\ --docs .ai\docs\
   --output .ai\docs\validation_report_v1.md

6. 执行测试
   gemini test --type all --file .ai\tests\ --code runtime\component\netdisk\ \
   --env moqui_h2,minio_9000,onlyoffice_80 \
   --output .ai\docs\test_report_v1.md

7. 澄清点

UI 细节：.ai\assets\ 截图未提供，需确认 Webroot 风格（表格/按钮样式）。
馆点管理：VenueManagement.xml 字段（ID、名称等）未明确。
分享链接：7 天有效期已设置，是否需密码选项？
优先级：建议优先验证存储桶列表和文件操作。

8. 下一步

运行 gemini validate 检查 netdisk 模块。
运行 gemini test 执行 .ai\tests\（JUnit、Selenium、JMeter）。
提供澄清：
.ai\assets\ 截图（UI 样式）。
馆点管理字段。
分享链接密码选项。


存储：
代码：runtime\component\netdisk\.
报告：.ai\docs\.


