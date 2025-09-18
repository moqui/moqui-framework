Minio Data Preparation Summary and Configuration Guide
Introduction
This document outlines the data preparation work for the Minio module in the Moqui framework, developed for the community e-commerce minio project. The work establishes a robust project management system using the HiveMind module, integrating storage bucket management, file operations, document collaboration (via OnlyOffice), and multi-language support. The configuration ensures tasks align with the "file cabinet," "community," and "e-commerce" metaphorical patterns, with single-direction task dependencies for incremental progress.
The process involved iterative discussions to define four XML configuration files, resolve import errors, and ensure compatibility with Moqui entities (moqui.security.ArtifactAuthz, mantle.work.effort.WorkEffort, moqui.resource.wiki.WikiSpace, moqui.basic.LocalizedMessage). All files are stored in ./runtime/component/HiveMind/data/ and can be loaded using ./gradlew load -PdataFile=... or Moqui's backend tool (http://localhost:8080/apps/tools/Entity/DataImport/load).
This document serves as a guide for Gemini CLI and iFlow to understand the setup and execute tasks based on user roles and milestones defined in MinioWorkData.xml.
Overview of Work Done
The data preparation includes:

Roles and Permissions: Configured user groups, artifact authorizations, work types, and employment position classes.
Document Links and Notifications: Defined data document links, email templates, and notification topics for Wiki, project, task, and request screens.
Work Data: Set up the MINIO project, milestones, tasks, requests, invoices, comments, and Wiki pages.
Multi-Language Support: Provided Chinese (zh) localization for key terms.
Task Allocation: Assigned tasks to users (USER001 to USER004) with roles (MINIO_ADMIN, MINIO_DEVELOPER, MINIO_TESTER, MINIO_ANALYST), ensuring alignment with metaphorical patterns.

All XML files are designed for seed or demo data import (type="seed-initial" or type="demo"), with foreign key dependencies resolved (e.g., WikiSpace before WikiPage, Party before WorkEffortParty).
XML Files
The following XML files are located in ./runtime/component/HiveMind/data/ and can be loaded into Moqui using:
./gradlew load -PdataFile=./runtime/component/HiveMind/data/[filename].xml

1. MinioSetupData.xml

Purpose: Defines user groups, permissions, work types, and employment position classes for the Minio module.
Key Entities:
moqui.security.UserGroup: User groups for role-based access.
moqui.security.ArtifactAuthz: Artifact authorizations for MINIO screens.
moqui.basic.Enumeration: Work types and purposes.
mantle.humanres.position.EmplPositionClass: Role-specific position classes.


Example Content:<moqui.security.UserGroup userGroupId="MINIO_ADMIN" description="Minio 项目管理员权限"/>
<moqui.security.UserGroup userGroupId="MINIO_DEVELOPER" description="Minio 开发者权限"/>
<moqui.security.UserGroup userGroupId="MINIO_TESTER" description="Minio 测试者权限"/>
<moqui.security.UserGroup userGroupId="MINIO_ANALYST" description="Minio 业务分析师权限"/>
<moqui.security.ArtifactAuthz artifactAuthzId="MINIO_ADMIN_AUTHZ" userGroupId="MINIO_ADMIN" artifactGroupId="MINIO" authzTypeEnumId="AUTHZT_ALWAYS" authzActionEnumId="AUTHZA_ALL"/>
<moqui.basic.Enumeration description="需求分析" enumId="WktpRequirements" enumTypeId="WorkType"/>
<moqui.basic.Enumeration description="新功能" enumId="WepNewFeature" enumTypeId="WorkEffortPurpose"/>
<mantle.humanres.position.EmplPositionClass title="业务分析师" emplPositionClassId="MinioAnalyst"/>
<mantle.humanres.position.EmplPositionClass title="程序员" emplPositionClassId="MinioProgrammer"/>



2. MinioDocumentData.xml

Purpose: Configures data document links, email templates, and notification topics for Wiki, project, task, and request screens.
Key Entities:
moqui.entity.document.DataDocumentLink: Links for screen navigation.
moqui.basic.email.EmailTemplate: Templates for notifications.
moqui.security.user.NotificationTopic: Notification configurations.


Example Content:<moqui.entity.document.DataDocumentLink dataDocumentId="MantleWikiPage" linkSeqId="21" linkSet="minio" label="Wiki Page" urlType="screen" linkUrl="/apps/minio/wiki/${wikiSpaceId}/${pagePath?:''}"/>
<moqui.basic.email.EmailTemplate emailTemplateId="MINIO_WIKI_PAGE_UPDATE" description="Minio 维基页面更新通知" emailServerId="SYSTEM" bodyScreenLocation="component://moqui-minio/screen/WikiPageUpdateNotification.xml" subject="Wiki Page Updated: ${document.wikiSpaceId}/${document.pagePath}"/>
<moqui.security.user.NotificationTopic topic="MantleWikiPage" linkTemplate="/vapps/minio/wiki/${wikiSpaceId}/${pagePath?:''}"/>
<moqui.basic.email.EmailTemplate emailTemplateId="MINIO_TASK_UPDATE" description="Minio 任务更新通知" emailServerId="SYSTEM" bodyScreenLocation="component://moqui-minio/screen/TaskUpdateNotification.xml" subject="Task Updated: ${workEffort.workEffortName}"/>



3. MinioWorkData.xml

Purpose: Defines the MINIO project, milestones, tasks, requests, invoices, comments, and Wiki pages.
Key Entities:
mantle.party.Party, mantle.party.Person: User records.
moqui.security.UserAccount: User credentials.
moqui.resource.wiki.WikiSpace, moqui.resource.wiki.WikiPage: Wiki setup.
mantle.work.effort.WorkEffort, mantle.work.effort.WorkEffortParty: Project, milestones, tasks, and assignments.
mantle.request.Request: User requests.
mantle.account.invoice.Invoice: Expense records.
mantle.party.communication.CommunicationEvent: Task comments.


Example Content:<mantle.party.Party partyId="USER001" partyTypeEnumId="PtyPerson"/>
<mantle.party.Person partyId="USER001" firstName="Admin" lastName="User"/>
<moqui.security.UserAccount userId="USER001" username="admin" password="admin123" userFullName="Admin User"/>
<moqui.resource.wiki.WikiSpace wikiSpaceId="MINIO_WIKI" description="Minio Project Wiki Space" restrictView="N" rootPageLocation="dbresource://WikiSpace/MINIO_WIKI.md"/>
<moqui.resource.wiki.WikiPage wikiPageId="MINIO_INTRO" wikiSpaceId="MINIO_WIKI" pagePath="Introduction" createdByUserId="USER001"/>
<moqui.resource.DbResource resourceId="MINIO_INTRO_CWIKI" parentResourceId="WIKI_SPACE_ROOT" filename="MINIO_INTRO.md" isFile="Y"/>
<moqui.resource.DbResourceFile resourceId="MINIO_INTRO_CWIKI" mimeType="text/plain" fileData="# Minio Project Introduction\n## Overview\nThe Minio project aims to provide cloud storage for community e-commerce.\n## Features\n- 5GB storage quota\n- Bucket management\n- File operations\n- Document collaboration"/>
<mantle.work.effort.WorkEffort workEffortId="MINIO" workEffortName="社区电商网盘" workEffortTypeEnumId="WetProject" statusId="WeInProgress" statusFlowId="HmTaskSimple" totalClientCostAllowed="350000" costUomId="CNY"/>
<mantle.work.effort.WorkEffort workEffortId="MINIO-MS-001" workEffortName="存储桶管理阶段" rootWorkEffortId="MINIO" workEffortTypeEnumId="WetMilestone" statusId="WeInProgress" estimatedStartDate="2025-09-14 00:00:00" estimatedCompletionDate="2025-09-28 00:00:00"/>
<mantle.work.effort.WorkEffort workEffortId="MINIO-TASK-001" workEffortName="开发存储桶管理服务" priority="1" rootWorkEffortId="MINIO" workEffortTypeEnumId="WetTask" purposeEnumId="WepNewFeature" statusId="WeInProgress" statusFlowId="HmTaskSimple" estimatedWorkTime="20" timeUomId="TF_hr">
<description>开发支持创建、列出、删除存储桶的服务，包含5GB配额限制。</description>
</mantle.work.effort.WorkEffort>
<mantle.work.effort.WorkEffortParty workEffortId="MINIO-TASK-001" partyId="USER002" roleTypeId="Assignee" emplPositionClassId="MinioProgrammer" fromDate="2025-09-14 00:00:00" statusId="WeptAssigned" receiveNotifications="Y"/>
<mantle.request.Request requestId="MINIO-REQ-001" requestTypeEnumId="RqtNewFeature" statusId="ReqInProgress" requestName="添加存储桶配额功能" description="在存储桶管理中添加5GB配额限制功能。" filedByPartyId="USER004"/>
<mantle.account.invoice.Invoice invoiceId="MINIO-INV-001" invoiceTypeEnumId="InvoiceSales" fromPartyId="MINIO_MINIO" toPartyId="USER001" statusId="InvoiceReceived" invoiceDate="2025-09-14 00:00:00" currencyUomId="CNY" description="MinIO 云存储服务费用"/>
<mantle.party.communication.CommunicationEvent communicationEventId="MINIO-TASK-001-01" communicationEventTypeId="Comment" contactMechTypeEnumId="CmtWebForm" statusId="CeSent" fromPartyId="USER002" entryDate="2025-09-14 17:00:00" contentType="text/plain" subject="存储桶配额实现问题">
<body>是否需要为每个用户单独配置配额，还是统一5GB限制？</body>
</mantle.party.communication.CommunicationEvent>



4. MinioL10nData.xml

Purpose: Provides Chinese (zh) localization for project, task, Wiki, roles, invoices, and general terms.
Key Entities:
moqui.basic.LocalizedMessage: Localized strings.


Example Content:<moqui.basic.LocalizedMessage original="Project" locale="zh" localized="项目"/>
<moqui.basic.LocalizedMessage original="Milestone" locale="zh" localized="里程碑"/>
<moqui.basic.LocalizedMessage original="Task" locale="zh" localized="任务"/>
<moqui.basic.LocalizedMessage original="Wiki Space" locale="zh" localized="Wiki空间"/>
<moqui.basic.LocalizedMessage original="Introduction" locale="zh" localized="介绍"/>
<moqui.basic.LocalizedMessage original="Storage Management" locale="zh" localized="存储管理"/>
<moqui.basic.LocalizedMessage original="OnlyOffice" locale="zh" localized="OnlyOffice"/>
<moqui.basic.LocalizedMessage original="CNY" locale="zh" localized="人民币"/>
<moqui.basic.LocalizedMessage original="In Progress" locale="zh" localized="进行中"/>
<moqui.basic.LocalizedMessage original="In Planning" locale="zh" localized="计划中"/>



Usage for Gemini CLI and iFlow
The following sections provide instructions for Gemini CLI and iFlow to leverage the prepared data and execute tasks based on user roles and milestones.
Gemini CLI
Gemini CLI can be used to generate code, validate configurations, and run tests for the Minio module. The XML files provide the data foundation, and Gemini CLI can operate on them as follows:

Generate Code:
Use templates in .ai/code/ to generate service or screen files.
Example:gemini generate --template .ai/code/HiveMindServices.xml --docs .ai/docs/PRD_minio_onlyoffice_v10.md --output runtime/component/minio/service/minio/MinioServices.xml


Purpose: Generate service logic for tasks like MINIO-TASK-004 (配额功能) or MINIO-TASK-012 (OnlyOffice 集成).


Validate Configurations:
Validate XML files and code against PRD.
Example:gemini validate --code runtime/component/HiveMind/ --docs .ai/docs/PRD_minio_onlyoffice_v10.md


Purpose: Ensure MinioWorkData.xml aligns with project requirements.


Run Tests:
Execute tests for storage bucket management, file operations, and OnlyOffice integration.
Example:gemini test --type all --file .ai/tests/ --code runtime/component/HiveMind/ --env moqui_h2,minio_9000,onlyoffice_80 --output .ai/docs/test_report_v1.md


Purpose: Validate tasks like MINIO-TASK-006 (测试存储桶) and MINIO-TASK-013 (测试 OnlyOffice).



iFlow
iFlow can act as an agent for different roles (admin, developer, tester, analyst) to execute tasks defined in MinioWorkData.xml. Instructions are based on .ai/prompts/Step1.PM_Agent.md, Step3.Programmer_Agent.md, and Step4.Tester_Agent.md.
Task Allocation by Role
Tasks are assigned to users based on their roles (MINIO_ADMIN, MINIO_DEVELOPER, MINIO_TESTER, MINIO_ANALYST) and linked to milestones (MINIO-MS-001 to MINIO-MS-003). The metaphorical pattern ensures single-direction dependencies: storage bucket management → file operations → document collaboration.
Milestone 1: Storage Bucket Management (MINIO-MS-001, 2025-09-14 to 2025-09-28)

Task 1.1: Perfect Storage Bucket Quota Function (MINIO-TASK-004, Priority 1)
Role: Developer (USER002, MINIO_DEVELOPER)
iFlow Prompt: Step3.Programmer_Agent.md
Instruction: Implement 5GB quota check in MinIO, update MinioServices.xml for bucket creation/listing/deletion with quota validation. Use Moqui service at component://moqui-minio/service/minio/MinioServices.xml. Commit code to runtime/component/minio/.
Dependencies: MINIO-TASK-001 (Develop Storage Bucket Service)
Metaphor: Ensure "file cabinet drawers" (buckets) have a 5GB capacity limit.
Estimated Time: 10 hours


Task 1.2: Optimize Storage Bucket Interface (MINIO-TASK-005, Priority 2)
Role: Developer (USER002, MINIO_DEVELOPER)
iFlow Prompt: Step3.Programmer_Agent.md
Instruction: Enhance Moqui screen at component://moqui-minio/screen/minio/BucketManagement.xml for intuitive bucket creation/listing/deletion. Ensure alignment with MinioL10nData.xml (e.g., "存储管理"). Commit to runtime/component/minio/.
Dependencies: MINIO-TASK-001
Metaphor: Make "file cabinet labels" (interface) clear and user-friendly.
Estimated Time: 8 hours


Task 1.3: Extend Storage Bucket Tests (MINIO-TASK-006, Priority 2)
Role: Tester (USER003, MINIO_TESTER)
iFlow Prompt: Step4.Tester_Agent.md
Instruction: Write JUnit/Selenium tests for quota limits and error scenarios. Store tests in .ai/tests/StorageBucketTests.java. Validate against MINIO-REQ-001. Generate report in .ai/docs/test_report_v1.md.
Dependencies: MINIO-TASK-004
Metaphor: Verify "file cabinet drawers" function reliably.
Estimated Time: 6 hours


Task 1.4: Update PRD Document (MINIO-TASK-007, Priority 3)
Role: Analyst (USER004, MINIO_ANALYST)
iFlow Prompt: Step1.PM_Agent.md
Instruction: Update MINIO_INTRO Wiki page with quota management details. Edit at http://localhost:8080/apps/minio/wiki/MINIO_WIKI/Introduction. Save Markdown in runtime/component/HiveMind/data/MINIO_INTRO.md.
Dependencies: MINIO-TASK-003
Metaphor: Enhance "community knowledge base" with detailed requirements.
Estimated Time: 5 hours



Milestone 2: File Operations (MINIO-MS-002, 2025-09-29 to 2025-10-13)

Task 2.1: Develop File Upload Service (MINIO-TASK-008, Priority 1)
Role: Developer (USER002, MINIO_DEVELOPER)
iFlow Prompt: Step3.Programmer_Agent.md
Instruction: Implement file upload with progress display, fix MinIO configuration (per MINIO-REQ-002). Update MinioServices.xml. Test at http://localhost:9000 (MinIO console). Commit to runtime/component/minio/.
Dependencies: MINIO-REQ-002
Metaphor: Enable "placing files into drawers" with smooth operation.
Estimated Time: 15 hours


Task 2.2: Develop File Download and Sharing (MINIO-TASK-009, Priority 2)
Role: Developer (USER002, MINIO_DEVELOPER)
iFlow Prompt: Step3.Programmer_Agent.md
Instruction: Implement file download and 7-day temporary sharing links. Update MinioServices.xml. Commit to runtime/component/minio/.
Dependencies: MINIO-TASK-008
Metaphor: Allow "taking files out of drawers" and sharing copies.
Estimated Time: 12 hours


Task 2.3: Test File Operations (MINIO-TASK-010, Priority 2)
Role: Tester (USER003, MINIO_TESTER)
iFlow Prompt: Step4.Tester_Agent.md
Instruction: Write tests for upload/download/sharing. Store in .ai/tests/FileOperationTests.java. Generate report in .ai/docs/test_report_v1.md.
Dependencies: MINIO-TASK-008, MINIO-TASK-009
Metaphor: Ensure "file access" is reliable and secure.
Estimated Time: 8 hours


Task 2.4: Update Wiki Storage Guide (MINIO-TASK-011, Priority 3)
Role: Analyst (USER004, MINIO_ANALYST)
iFlow Prompt: Step1.PM_Agent.md
Instruction: Update MINIO_STORAGE Wiki page with file operation guide. Edit at http://localhost:8080/apps/minio/wiki/MINIO_WIKI/StorageManagement.
Dependencies: None
Metaphor: Expand "community knowledge base" with file operation instructions.
Estimated Time: 5 hours



Milestone 3: Document Collaboration (MINIO-MS-003, 2025-10-14 to 2025-10-28)

Task 3.1: Integrate OnlyOffice Editor (MINIO-TASK-012, Priority 1)
Role: Developer (USER002, MINIO_DEVELOPER)
iFlow Prompt: Step3.Programmer_Agent.md
Instruction: Integrate OnlyOffice Document Server (at http://localhost:9001) with Moqui. Save documents to MinIO. Update MinioServices.xml. Commit to runtime/component/minio/.
Dependencies: MINIO-TASK-008
Metaphor: Add "on-site editing" to the file cabinet.
Estimated Time: 20 hours


Task 3.2: Test OnlyOffice Integration (MINIO-TASK-013, Priority 2)
Role: Tester (USER003, MINIO_TESTER)
iFlow Prompt: Step4.Tester_Agent.md
Instruction: Test document editing/saving/collaboration. Store tests in .ai/tests/OnlyOfficeTests.java. Generate report in .ai/docs/test_report_v1.md.
Dependencies: MINIO-TASK-012
Metaphor: Verify "editing tools" work seamlessly.
Estimated Time: 10 hours


Task 3.3: Create OnlyOffice Wiki Page (MINIO-TASK-014, Priority 3)
Role: Analyst (USER004, MINIO_ANALYST)
iFlow Prompt: Step1.PM_Agent.md
Instruction: Create MINIO_ONLYOFFICE Wiki page at http://localhost:8080/apps/minio/wiki/MINIO_WIKI/Introduction/OnlyOffice. Save Markdown in MINIO_ONLYOFFICE.md.
Dependencies: None
Metaphor: Add "collaboration guide" to the community knowledge base.
Estimated Time: 5 hours


Task 3.4: Configure Task Update Notifications (MINIO-TASK-015, Priority 3)
Role: Admin (USER001, MINIO_ADMIN)
iFlow Prompt: Step1.PM_Agent.md
Instruction: Configure notifications for task/Wiki updates in component://moqui-minio/screen/TaskUpdateNotification.xml. Test at http://localhost:8080/apps/minio/Dashboard.
Dependencies: None
Metaphor: Set up "community announcement board" for updates.
Estimated Time: 6 hours



Validation Steps

Import XML Files:
Run:./gradlew load -PdataFile=./runtime/component/HiveMind/data/MinioSetupData.xml
./gradlew load -PdataFile=./runtime/component/HiveMind/data/MinioDocumentData.xml
./gradlew load -PdataFile=./runtime/component/HiveMind/data/MinioWorkData.xml
./gradlew load -PdataFile=./runtime/component/HiveMind/data/MinioL10nData.xml


Or use Moqui backend: http://localhost:8080/apps/tools/Entity/DataImport/load.


Database Verification:SELECT * FROM USER_GROUP WHERE USER_GROUP_ID LIKE 'MINIO_%';
SELECT * FROM WIKI_PAGE WHERE WIKI_SPACE_ID = 'MINIO_WIKI';
SELECT * FROM WORK_EFFORT WHERE ROOT_WORK_EFFORT_ID = 'MINIO';
SELECT * FROM LOCALIZED_MESSAGE WHERE LOCALE = 'zh' AND ORIGINAL IN ('Project', 'Task', 'OnlyOffice', 'CNY');


Interface Verification:
Login as USER001 (admin/admin123):
Project: http://localhost:8080/apps/minio/Project/ProjectSummary?workEffortId=MINIO
Task: http://localhost:8080/apps/minio/Task/TaskSummary?workEffortId=MINIO-TASK-001
Wiki: http://localhost:8080/apps/minio/wiki/MINIO_WIKI/Introduction
Invoice: http://localhost:8080/apps/minio/Accounting/InvoiceSummary?invoiceId=MINIO-INV-001


Confirm Chinese localization (e.g., "项目", "人民币").



Next Steps

Gemini CLI:
Generate service code for MINIO-TASK-004, MINIO-TASK-008, MINIO-TASK-012.
Validate and test configurations.


iFlow:
Assign tasks to roles as outlined above.
Use /product to update .ai/docs/PRD_minio_onlyoffice_v10.md if needed.


Monitor Progress:
Use HiveMind dashboard: http://localhost:8080/apps/minio/Dashboard.
Track budget: Current 26530 CNY (7000 CNY invoice + 19530 CNY labor), remaining 323470 CNY.


