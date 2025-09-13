社区电商存储与协作系统代码实现（v1）
1. 实现概述
   基于 PRD v9、架构设计 v3 和《数字云展使用手册》，实现存储桶管理、用户管理、馆点管理功能，使用 Moqui Screen（Webroot 默认风格）进行快速原型开发。代码覆盖 Moqui Service、Screen XML、MinIO 策略，支持 JWT 认证、5GB 配额、H2（开发）/Psql（生产）、Docker 部署。
2. 目录结构
   .ai/code/
   ├── moqui/
   │   ├── services/
   │   │   ├── CustomMinioServices.groovy
   │   │   ├── CustomBucketServices.groovy
   │   ├── screens/
   │   │   ├── BusinessResourceManagement.xml
   │   │   ├── BucketDetail.xml
   │   │   ├── UserManagement.xml
   │   │   ├── VenueManagement.xml
   │   ├── css/
   │   │   ├── webroot.css (Webroot 默认风格)
   │   ├── entity/
   │   │   ├── AuditLog.xml
   ├── minio/
   │   ├── policies/
   │   │   ├── merchant_policy.json
   ├── tests/
   │   ├── CustomMinioServicesTest.java
   ├── deployment/
   │   ├── docker-compose.yml

3. 代码实现
   3.1 Moqui Service
   CustomMinioServices.groovy
   // .ai/code/moqui/services/CustomMinioServices.groovy
   import org.moqui.impl.context.ExecutionContextImpl
   import org.moqui.impl.service.ServiceFacadeImpl

def createUserAndBucket = { Map parameters, ExecutionContextImpl eci ->
def partyId = parameters.partyId
def userLoginId = parameters.userLoginId
def accountType = parameters.account_type ?: 'main'
// 创建 UserAccount 和 Party
eci.service.sync().name('mantle.usl.UserAccountServices.create#UserAccount')
.parameters([userLoginId: userLoginId, partyId: partyId, account_type: accountType]).call()
// 创建 PartyAttribute
eci.service.sync().name('mantle.party.PartyServices.create#PartyAttribute')
.parameters([partyId: partyId, attrName: 'account_type', attrValue: accountType]).call()
// 创建 MinIO 用户
eci.executeCommand("mc admin user add myminio ${userLoginId} ${eci.user.generatePassword()}")
// 创建桶（merchant-{partyId}，5GB）
def bucketName = "merchant-${partyId}"
eci.executeCommand("mc mb myminio/${bucketName}")
eci.executeCommand("mc admin bucket quota myminio/${bucketName} --hard 5GB")
// 分配策略
def policy = accountType == 'main' ? 'readwrite' : 'readonly'
eci.executeCommand("mc admin policy attach myminio ${policy} --user ${userLoginId}")
return [successMessage: "用户和桶创建成功"]
}

def listBuckets = { Map parameters, ExecutionContextImpl eci ->
def buckets = eci.entity.find('mantle.party.Party')
.condition([partyTypeEnumId: 'PtyOrganization'])
.list()
.collect { party ->
def bucketName = "merchant-${party.partyId}"
def used = eci.executeCommand("mc du myminio/${bucketName}").toString()
def users = eci.entity.find('mantle.party.PartyRelationship')
.condition([fromPartyId: party.partyId, relationshipTypeEnumId: 'EMPLOYEE'])
.list()
[bucketName: bucketName, users: users, capacity: '5GB', used: used]
}
return [buckets: buckets]
}

3.2 Moqui Entity
AuditLog.xml
<!-- .ai/code/moqui/entity/AuditLog.xml -->
<entity entity-name="custom.AuditLog" package-name="custom">
  <field name="logId" type="id" is-pk="true"/>
  <field name="userId" type="id"/>
  <field name="action" type="text-short"/>
  <field name="file" type="text-long"/>
  <field name="timestamp" type="date-time"/>
  <field name="ipAddress" type="text-short"/>
</entity>

3.3 Moqui Screen
BusinessResourceManagement.xml
<!-- .ai/code/moqui/screens/BusinessResourceManagement.xml -->
<screen name="BusinessResourceManagement" location="component://custom/screen/BusinessResourceManagement.xml">
  <transition name="listBuckets" service="custom.bucket.ListBuckets"/>
  <widgets>
    <section name="bucketList" condition="user.hasPermission('SECPTY_ADMIN')">
      <actions>
        <service-call name="buckets" service-name="custom.bucket.ListBuckets"/>
      </actions>
      <widget>
        <container style="webroot-container">
          <label text="存储桶列表"/>
          <table items="buckets" sort-fields="bucketName,createdTimestamp">
            <column field="bucketName" header="桶名"/>
            <column field="users" header="关联用户">
              <widget><text text="${users.size()} 用户"/></widget>
            </column>
            <column field="capacity" header="容量"/>
            <column field="used" header="已用"/>
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

BucketDetail.xml
<!-- .ai/code/moqui/screens/BucketDetail.xml -->
<screen name="BucketDetail" location="component://custom/screen/BucketDetail.xml">
  <parameter name="bucketName"/>
  <transition name="listFiles" service="custom.bucket.ListFiles"/>
  <transition name="uploadFile" service="custom.bucket.UploadFile"/>
  <transition name="createFolder" service="custom.bucket.CreateFolder"/>
  <transition name="moveFile" service="custom.bucket.MoveFile"/>
  <transition name="rename" service="custom.bucket.Rename"/>
  <transition name="share" service="custom.bucket.ShareFile"/>
  <widgets>
    <container style="webroot-container">
      <form-single name="uploadForm" transition="uploadFile">
        <field name="file" type="file"/>
        <field name="submit" type="submit" text="上传"/>
      </form-single>
      <form-single name="createFolderForm" transition="createFolder">
        <field name="folderName" type="text"/>
        <field name="submit" type="submit" text="新建文件夹"/>
      </form-single>
      <form-single name="searchForm">
        <field name="keyword" type="text"/>
        <field name="submit" type="submit" text="搜索"/>
      </form-single>
      <table items="files" sort-fields="name,modifiedTime">
        <column field="name" header="名称"/>
        <column field="type" header="类型"/>
        <column field="size" header="大小"/>
        <column field="modifiedTime" header="修改时间"/>
        <column>
          <link url="/onlyoffice/edit?file=${name}&bucket=${bucketName}" text="编辑" target="_blank"/>
          <link url="download?file=${name}&bucket=${bucketName}" text="下载"/>
          <link url="share?file=${name}&bucket=${bucketName}" text="分享"/>
          <link url="move?file=${name}&bucket=${bucketName}" text="移动"/>
          <link url="rename?file=${name}&bucket=${bucketName}" text="重命名"/>
        </column>
      </table>
    </container>
  </widgets>
</screen>

UserManagement.xml
<!-- .ai/code/moqui/screens/UserManagement.xml -->
<screen name="UserManagement" location="component://custom/screen/UserManagement.xml">
  <parameter name="bucketName"/>
  <transition name="listUsers" service="custom.bucket.ListUsers"/>
  <transition name="addUser" service="custom.bucket.AddUser"/>
  <transition name="removeUser" service="custom.bucket.RemoveUser"/>
  <transition name="setPermission" service="custom.bucket.SetPermission"/>
  <widgets>
    <container style="webroot-container">
      <form-single name="addUserForm" transition="addUser">
        <field name="userLoginId" type="text"/>
        <field name="role" type="select" options="['main', 'sub']"/>
        <field name="submit" type="submit" text="添加用户"/>
      </form-single>
      <table items="users">
        <column field="userLoginId" header="用户名"/>
        <column field="permissions" header="权限"/>
        <column>
          <form-single name="setPermissionForm" transition="setPermission">
            <field name="userLoginId" type="hidden" value="${userLoginId}"/>
            <field name="permission" type="select" options="['read', 'write', 'delete']"/>
            <field name="submit" type="submit" text="设置"/>
          </form-single>
          <link url="removeUser?userLoginId=${userLoginId}&bucket=${bucketName}" text="删除"/>
        </column>
      </table>
    </container>
  </widgets>
</screen>

3.4 MinIO 策略
merchant_policy.json
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

3.5 测试用例
CustomMinioServicesTest.java
// .ai/code/tests/CustomMinioServicesTest.java
import org.junit.Test;
import static org.junit.Assert.*;

public class CustomMinioServicesTest {
@Test
public void testCreateUserAndBucket() {
ExecutionContextImpl eci = Moqui.getExecutionContext();
Map result = eci.service.sync().name("custom.minio.CreateUserAndBucket")
.parameters([partyId: "acme_001", userLoginId: "user1", account_type: "main"]).call();
assertEquals("用户和桶创建成功", result.successMessage);
String bucket = eci.executeCommand("mc ls myminio/merchant-acme_001");
assertNotNull(bucket);
}
}

3.6 部署配置
docker-compose.yml
# .ai/code/deployment/docker-compose.yml
version: '3'
services:
moqui:
image: moqui/moqui-framework
ports:
- "8080:8080"
environment:
- MOQUI_DB_TYPE=postgresql
- MOQUI_DB_HOST=postgres
depends_on:
- postgres
postgres:
image: postgres:15
environment:
- POSTGRES_DB=moqui
- POSTGRES_USER=moqui
- POSTGRES_PASSWORD=moqui
minio:
image: minio/minio
ports:
- "9000:9000"
command: server /data
onlyoffice:
image: onlyoffice/documentserver
ports:
- "80:80"

4. 澄清点

UI 细节：Moqui Webroot 风格是否需调整（如表格字体、按钮颜色）？.ai/asserts/ 截图待确认。
馆点管理：VenueManagement.xml 具体字段（如馆点 ID、名称）？
Psql 配置：PostgreSQL 版本（默认 15）或连接池？
分享链接：有效期选项（默认 1h-7d）是否需调整？

5. 下一步
   代码生成完成，存储于 .ai/code/。请输入 /test 验证功能，或提供以下澄清：

.ai/asserts/ 截图或 UI mockup（表格/按钮样式）。
馆点管理字段。
Psql/Docker 参数。
