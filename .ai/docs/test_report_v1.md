社区电商存储与协作系统测试用例（v1）
1. 测试概述
   基于 PRD v9、架构设计 v3、代码实现 v1 和《数字云展使用手册》，验证社区电商网盘+OnlyOffice 集成系统的功能、性能和安全性。测试覆盖存储桶管理（列表、文件操作、权限）、用户管理（新增、权限、筛选）、馆点管理（信息列表），确保功能正确性（100% 符合 PRD）、性能（权限<50ms、审计<100ms）、安全（JWT、权限隔离），覆盖率>95%。
   测试环境

开发：Moqui（H2 数据库）、MinIO（本地，端口 9000）、OnlyOffice（本地，端口 80）、本地启动。
生产：Moqui（PostgreSQL 16）、MinIO（Docker，端口 9000）、OnlyOffice（Docker，端口 80）、Docker Compose。
UI：Moqui Screen（Webroot 默认风格，简洁表格/表单）。
工具：JUnit（Moqui Service）、Selenium（Screen UI）、JMeter（性能）。

2. 测试用例
   2.1 单元测试（Moqui Service）
   TC-001: 用户和存储桶创建

目标：验证 custom.minio.CreateUserAndBucket 创建用户和桶（merchant-{partyId}，5GB）。
前置条件：Moqui H2 数据库，MinIO 运行（端口 9000）。
步骤：
调用 custom.minio.CreateUserAndBucket（partyId="acme_001", userLoginId="user1", account_type="main"）。
检查 Moqui Entity（Party、UserAccount、PartyAttribute）。
检查 MinIO 桶（merchant-acme_001，配额 5GB）。
检查 MinIO 用户（user1，readwrite 策略）。


预期结果：
Party 创建（partyId="acme_001", partyTypeEnumId="PtyOrganization"）。
UserAccount 创建（userLoginId="user1"）。
PartyAttribute 创建（account_type="main"）。
MinIO 桶创建（merchant-acme_001，配额 5GB）。
MinIO 用户创建（user1，readwrite 策略）。


测试代码（.ai/tests/unit/CustomMinioServicesTest.java）：@Test
public void testCreateUserAndBucket() {
ExecutionContextImpl eci = Moqui.getExecutionContext();
Map result = eci.service.sync().name("custom.minio.CreateUserAndBucket")
.parameters([partyId: "acme_001", userLoginId: "user1", account_type: "main"]).call();
assertEquals("用户和桶创建成功", result.successMessage);
def party = eci.entity.find("mantle.party.Party").condition([partyId: "acme_001"]).one();
assertNotNull(party);
assertEquals("PtyOrganization", party.partyTypeEnumId);
String bucket = eci.executeCommand("mc ls myminio/merchant-acme_001");
assertNotNull(bucket);
}



TC-002: 存储桶列表

目标：验证 custom.bucket.ListBuckets 返回桶概要（名称、用户、容量）。
前置条件：存在桶（merchant-acme_001），关联用户（user1）。
步骤：
调用 custom.bucket.ListBuckets。
检查返回数据（bucketName、users、capacity、used）。


预期结果：
返回 buckets 列表，包含 merchant-acme_001，users=[user1]，capacity="5GB"。


测试代码（.ai/tests/unit/CustomBucketServicesTest.java）：@Test
public void testListBuckets() {
ExecutionContextImpl eci = Moqui.getExecutionContext();
Map result = eci.service.sync().name("custom.bucket.ListBuckets").call();
List buckets = result.buckets;
assertFalse(buckets.isEmpty());
def bucket = buckets.find { it.bucketName == "merchant-acme_001" };
assertNotNull(bucket);
assertEquals("5GB", bucket.capacity);
}



2.2 集成测试（Moqui+MinIO+OnlyOffice）
TC-003: 用户注册触发桶创建

目标：验证用户注册（Moqui）触发 MinIO 桶创建和权限映射。
前置条件：Moqui H2，MinIO（端口 9000），JWT 认证。
步骤：
通过 REST API（/mantle/user/UserAccount）注册用户（userLoginId="user2", partyId="acme_002", account_type="sub"）。
检查 Moqui Entity（Party、UserAccount）。
检查 MinIO 桶（merchant-acme_002，5GB）。
检查 MinIO 用户（user2，readonly 策略）。


预期结果：
Party 创建（partyId="acme_002"）。
UserAccount 创建（user2）。
MinIO 桶创建（merchant-acme_002，5GB）。
用户 user2 具有 readonly 权限。


测试代码（.ai/tests/integration/UserBucketIntegrationTest.java）：@Test
public void testUserBucketCreation() {
ExecutionContextImpl eci = Moqui.getExecutionContext();
Map result = eci.service.sync().name("mantle.usl.UserAccountServices.create#UserAccount")
.parameters([userLoginId: "user2", partyId: "acme_002", account_type: "sub"]).call();
assertTrue(result.success);
String bucket = eci.executeCommand("mc ls myminio/merchant-acme_002");
assertNotNull(bucket);
String policy = eci.executeCommand("mc admin policy list myminio --user user2");
assertTrue(policy.contains("readonly"));
}



TC-004: OnlyOffice 文件编辑与版本保存

目标：验证 OnlyOffice 编辑文件并保存至 MinIO（新版本）。
前置条件：MinIO 桶（merchant-acme_001），文件（test.docx），OnlyOffice（端口 80）。
步骤：
通过 Moqui Screen（BucketDetail.xml）打开 test.docx（OnlyOffice）。
编辑并保存（触发 POST /save）。
检查 MinIO 版本（merchant-acme_001/test.docx）。


预期结果：
OnlyOffice 打开 test.docx。
保存触发 MinIO 新版本。
版本列表包含新版本。


测试代码（.ai/tests/integration/OnlyOfficeIntegrationTest.java）：@Test
public void testOnlyOfficeVersioning() {
ExecutionContextImpl eci = Moqui.getExecutionContext();
eci.executeCommand("mc cp test.docx myminio/merchant-acme_001/test.docx");
// 模拟 OnlyOffice 保存
Map result = eci.service.sync().name("custom.bucket.SaveFile")
.parameters([bucketName: "merchant-acme_001", file: "test.docx", content: "updated"]).call();
assertTrue(result.success);
String versions = eci.executeCommand("mc versioning list myminio/merchant-acme_001/test.docx");
assertTrue(versions.contains("version"));
}



2.3 界面测试（Moqui Screen）
TC-005: 存储桶列表显示

目标：验证管理员在 BusinessResourceManagement.xml 查看桶列表（按名称/时间排序）。
前置条件：登录（admin/admin123），权限 SECPTY_ADMIN，桶（merchant-acme_001）。
步骤：
登录 Moqui（JWT）。
访问 BusinessResourceManagement。
检查桶列表（bucketName、users、capacity、used、配置按钮）。
按名称/时间排序。


预期结果：
显示 merchant-acme_001，users=1，capacity=5GB。
配置按钮可见（SECPTY_ADMIN）。
排序正确。


测试代码（.ai/tests/ui/BucketListUITest.java）：@Test
public void testBucketListUI() {
WebDriver driver = new ChromeDriver();
driver.get("http://localhost:8080/BusinessResourceManagement");
driver.findElement(By.id("username")).sendKeys("admin");
driver.findElement(By.id("password")).sendKeys("admin123");
driver.findElement(By.id("login")).click();
WebElement table = driver.findElement(By.tagName("table"));
assertTrue(table.getText().contains("merchant-acme_001"));
assertTrue(table.getText().contains("5GB"));
driver.findElement(By.id("sort-bucketName")).click();
assertEquals("merchant-acme_001", table.findElements(By.tagName("tr")).get(1).getText());
driver.quit();
}



TC-006: 文件操作（上传/分享/移动）

目标：验证 BucketDetail.xml 文件操作（上传、分享、移动）。
前置条件：登录（user1），桶（merchant-acme_001）。
步骤：
访问 BucketDetail?bucketName=merchant-acme_001。
上传文件（test.pdf）。
生成分享链接（7 天有效期）。
移动文件至 /folder1（弹窗选择）。


预期结果：
test.pdf 上传至 merchant-acme_001。
分享链接生成（有效期 7 天）。
文件移动至 /folder1。


测试代码（.ai/tests/ui/BucketDetailUITest.java）：@Test
public void testFileOperations() {
WebDriver driver = new ChromeDriver();
driver.get("http://localhost:8080/BucketDetail?bucketName=merchant-acme_001");
driver.findElement(By.id("uploadForm_file")).sendKeys("/path/to/test.pdf");
driver.findElement(By.id("uploadForm_submit")).click();
assertTrue(driver.getPageSource().contains("test.pdf"));
driver.findElement(By.id("share_test.pdf")).click();
WebElement shareDialog = driver.findElement(By.id("shareDialog"));
assertEquals("7 days", shareDialog.findElement(By.id("validity")).getText());
driver.findElement(By.id("move_test.pdf")).click();
WebElement moveDialog = driver.findElement(By.id("moveDialog"));
moveDialog.findElement(By.id("targetFolder")).sendKeys("/folder1");
moveDialog.findElement(By.id("moveSubmit")).click();
assertTrue(driver.getPageSource().contains("/folder1/test.pdf"));
driver.quit();
}



2.4 性能测试
TC-007: 权限验证性能

目标：验证权限验证<50ms。
前置条件：Moqui H2，MinIO，SecurityGroup（SECPTY_READ）。
步骤：
调用 REST API /mantle/security/Permission（user1，merchant-acme_001）。
测量响应时间。


预期结果：响应时间<50ms。
测试代码（.ai/tests/performance/PermissionPerformanceTest.jmx）：<ThreadGroup>
<stringProp name="ThreadGroup.num_threads">10</stringProp>
<HTTPSamplerProxy>
<stringProp name="HTTPSampler.path">/mantle/security/Permission</stringProp>
<stringProp name="HTTPSampler.method">GET</stringProp>
<HeaderManager>
<elementProp name="Authorization" elementType="Header">
<stringProp name="Header.value">Bearer ${jwt}</stringProp>
</elementProp>
</HeaderManager>
</HTTPSamplerProxy>
<ResultCollector testname="ResponseTime"/>
</ThreadGroup>



2.5 安全测试
TC-008: JWT 认证

目标：验证无 JWT 或无效 JWT 无法访问 Screen。
前置条件：Moqui H2，Screen（BusinessResourceManagement）。
步骤：
访问 /BusinessResourceManagement（无 JWT）。
访问 /BusinessResourceManagement（无效 JWT）。


预期结果：返回 401 Unauthorized。
测试代码（.ai/tests/ui/SecurityUITest.java）：@Test
public void testJwtAuthentication() {
WebDriver driver = new ChromeDriver();
driver.get("http://localhost:8080/BusinessResourceManagement");
assertEquals("401 Unauthorized", driver.getTitle());
driver.quit();
}



3. 测试报告

测试范围：
功能：存储桶列表、文件操作（上传/分享/移动）、用户管理（增删/权限）、OnlyOffice 版本。
性能：权限验证<50ms，审计日志<100ms。
安全：JWT、权限隔离。


覆盖率：目标>95%（Codecov 验证）。
结果：待执行，预计通过率>90%。
缺陷：待测试，关注权限冲突、UI 响应。

4. 澄清点

UI 细节：.ai/asserts/ 截图未提供，需确认表格/按钮样式（如字体、颜色）。
馆点管理：VenueManagement.xml 字段（ID、名称等）？
分享链接：7 天有效期已设置，是否需密码选项？
Docker 配置：默认端口（MinIO 9000，OnlyOffice 80），内存默认，是否需调整？

5. 下一步
   测试用例生成完成，存储于 .ai/tests/。请输入 /execute 运行测试并生成报告（.ai/docs/test_report_v1.md），或提供：

.ai/asserts/ 截图（UI 样式）。
馆点管理字段。
其他 UI 优先级或样式要求。
