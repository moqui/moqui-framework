Architecture Design for Community E-commerce Storage & Collaboration (v1)
1. Architecture Overview
   Goal
   Design a scalable, secure system for a community e-commerce platform integrating MinIO (S3-compatible storage), OnlyOffice (document collaboration), and Moqui (ERP backend) with Vue/HBuilder frontends. The system supports file storage, hierarchical permissions (read/write/delete), auditing, quota management, and document collaboration, enhancing merchant collaboration and stickiness.
   Scope

Components: Moqui Backend (Party/Security), MinIO (storage), OnlyOffice (collaboration), Vue (web), HBuilder (mini-program).
Key Features: Account creation (main/sub), hierarchical permissions, file move with conflict handling, quota limits, audit logs, OnlyOffice view/edit/review, version control, sharing with optional settings.
Constraints:
Performance: Permission validation <50ms, audit logging <100ms, quota check <50ms.
Security: JWT authentication, Moqui SecurityGroup+MinIO policy isolation, encrypted audit logs.
Compatibility: Moqui Party/Security + MinIO + OnlyOffice seamless integration.
Development: iFlow (Moqui Service), Gemini CLI (Vue/HBuilder).



2. Architecture Diagram
   Markdown Table



Component
Role
Inputs
Outputs
Dependencies



Moqui Backend
Party/Security, MinIO trigger
User registration, API calls
UserAccount, MinIO user/bucket, SecurityGroup
mantle.usl, custom Service


MinIO
Storage, permissions, quota, audit
Moqui Service, S3 API
Buckets, files, audit logs
mc admin JAR, webhook


OnlyOffice
View/edit/review, versioning
MinIO S3 file, JWT
Edited files, versions
S3 plugin, JWT


Vue/HBuilder
UI (permissions, file ops, audit)
Moqui API, MinIO URL
User actions, audit view
Vuetify, UniApp


Mermaid Diagram
graph TD
A[User] -->|Register/Login| B(Moqui Backend)
B -->|Create UserAccount| C[Moqui Party/Security]
C -->|Trigger| D[MinIO]
D -->|S3 API| E[OnlyOffice]
B -->|REST API| F[Vue/HBuilder UI]
F -->|Pre-signed URL| D
D -->|Webhook| C[AuditLog Entity]
E -->|Save Version| D

Data Flow:

User registers via Vue/HBuilder → Moqui creates Party/UserAccount → triggers MinIO user/bucket creation.
Moqui SecurityGroup maps to MinIO policy (read/write/delete).
User accesses files via Vue/HBuilder → MinIO S3 API → OnlyOffice for collaboration.
MinIO webhook pushes audit logs to Moqui AuditLog Entity.
Vue/HBuilder displays audit, versions, and quota alerts.

3. Component Details
   3.1 Moqui Backend

Role: Manages accounts (main/sub), permissions, and MinIO triggers.
Entities:
Party: ORGANIZATION (main, e.g., merchant_001), PERSON (sub, e.g., sub_user_001).
PartyRelationship: Links main/sub (type=EMPLOYEE).
PartyAttribute: Tags main/sub (account_type=main/sub).
UserAccount: Stores login credentials (userLoginId, password).
SecurityGroup: Defines groups (SMALL_MERCHANT, MERCHANT_TEAM).
SecurityPermission: Defines permissions (SECPTY_READ/WRITE/DELETE).
UserGroupMember: Binds users to groups.
AuditLog (new): Stores audit (userId, action, file, timestamp).


Services:
mantle.usl.UserAccountServices.create#UserAccount: Creates UserAccount/Party.
custom.minio.CreateUserAndBucket: Triggers MinIO user/bucket creation.


REST API: /mantle/user/UserAccount for account management, /mantle/security/Permission for permissions.

3.2 MinIO

Role: File storage, permissions, quota, audit.
Configuration:
Buckets: merchant-{partyId} (e.g., merchant_001).
Permissions: Policy JSON maps Moqui SecurityGroup (e.g., SECPTY_READ → s3:GetObject).
Quota: mc admin JAR (mc admin bucket quota).
Audit: Webhook to Moqui AuditLog Entity.


APIs:
S3 API: GetObject, PutObject, DeleteObject.
Admin API: mc admin user add, mc admin policy attach.



3.3 OnlyOffice

Role: Document view/edit/review, versioning.
Configuration:
Docker: Single instance, S3 plugin for MinIO.
JWT: Validates user access.
Features: Track Changes, Comments, auto-save (30s), version control.


APIs: Callback for file save (POST /save, stores to MinIO).

3.4 Vue/HBuilder UI

Role: User interaction (permissions, file ops, audit, versions).
Framework:
Vue: Vuetify (tables, dialogs, forms).
HBuilder: UniApp (lists, navigation).


Components:
Permissions: <v-select> for group/permission selection.
File Move: <v-autocomplete> for directory, <v-dialog> for conflict.
Audit: <v-data-table> for logs.
Versions: <v-timeline> for version history.
Sharing: <v-form> with checkboxes (validity, password, review).



4. Data Flow and API Specification
   4.1 Data Flow

User Registration:
Vue/HBuilder → Moqui REST /mantle/user/UserAccount → Party/UserAccount creation.
Moqui Service custom.minio.CreateUserAndBucket → MinIO user/bucket.


Permission Management:
Moqui SecurityGroup (e.g., MERCHANT_TEAM) → MinIO policy JSON.


File Operations:
Vue/HBuilder → MinIO S3 API (Get/Put/Delete) → OnlyOffice for edit/review.
Conflict: UI dialog (overwrite/cancel) → MinIO Put or abort.


Audit Logging:
MinIO webhook → Moqui AuditLog Entity → Vue <v-data-table>.


Quota Check:
MinIO Quota API → UI alert if exceeded.


Versioning:
OnlyOffice save → MinIO version control → Vue <v-timeline>.



4.2 API Specification

Moqui REST API:
POST /mantle/user/UserAccount:
Parameters: { userLoginId, password, partyId, account_type }
Response: { success: true, userId }


GET /mantle/security/Permission:
Parameters: { groupId }
Response: { permissions: [{ permissionId, permTypeEnumId }] }




MinIO S3 API:
GET /merchant-{partyId}/{path}: Retrieve file.
PUT /merchant-{partyId}/{path}: Upload/edit file.
DELETE /merchant-{partyId}/{path}: Delete file.


OnlyOffice Callback:
POST /save:
Parameters: { fileId, content, version }
Response: { success: true, versionId }





5. Pseudo Code
   5.1 Moqui Service: Create User and Bucket
   // custom.minio.CreateUserAndBucket
   def createUserAndBucket = { Map parameters ->
   def partyId = parameters.partyId
   def userLoginId = parameters.userLoginId
   // Create Moqui UserAccount and Party
   runService('mantle.usl.UserAccountServices.create#UserAccount', [
   userLoginId: userLoginId,
   partyId: partyId,
   account_type: parameters.account_type // main/sub
   ])
   // Create MinIO user
   execCommand("mc admin user add myminio ${userLoginId} ${randomPassword}")
   // Create bucket
   execCommand("mc mb myminio/merchant-${partyId}")
   // Assign policy
   execCommand("mc admin policy attach myminio ${parameters.account_type == 'main' ? 'readwrite' : 'readonly'} --user ${userLoginId}")
   // Set quota (default 5GB)
   execCommand("mc admin bucket quota myminio/merchant-${partyId} --hard 5GB")
   return [successMessage: "User and bucket created"]
   }

5.2 MinIO Policy JSON
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

5.3 Vue Component: Audit Table
<template>
<v-data-table :items="auditLogs" :headers="headers">
<template v-slot:item.action="{ item }">
<v-chip :color="item.action === 'share' ? 'blue' : 'green'">{{ item.action }}</v-chip>
</template>
</v-data-table>
</template>
<script>
export default {
  data() {
    return {
      headers: [
        { text: 'User', value: 'userId' },
        { text: 'Action', value: 'action' },
        { text: 'File', value: 'file' },
        { text: 'Time', value: 'timestamp' }
      ],
      auditLogs: [] // Fetched from Moqui /mantle/audit/log
    }
  },
  async created() {
    this.auditLogs = await fetch('/mantle/audit/log').then(res => res.json())
  }
}
</script>

6. Non-Functional Requirements

Performance:
Permission validation: <50ms (Moqui SecurityGroup+MinIO policy).
Audit logging: <100ms (webhook to AuditLog Entity).
Quota check: <50ms (mc admin API).


Security:
JWT: Moqui/OnlyOffice authentication.
Isolation: Moqui SecurityGroup maps to MinIO policy; sub-users restricted to paths.
Audit encryption: AES for AuditLog Entity.


Compatibility:
Moqui Party/Security integrates with MinIO via custom Service.
OnlyOffice S3 plugin compatible with MinIO.


Scalability:
MinIO supports multi-tenant buckets (merchant-{partyId}).
Moqui scales for 1000+ merchants via Entity caching.



7. Risk Mitigation

Risk: Permission conflicts (e.g., sub-user bypasses restrictions).
Mitigation: Moqui SecurityGroup enforces hierarchy; MinIO policy validates ARN.


Risk: OnlyOffice version merge conflicts.
Mitigation: UI prompts conflict resolution; prioritizes latest edit.


Risk: Audit log overflow.
Mitigation: Auto-clean logs >30 days; support export to CSV.


Risk: Quota enforcement delays.
Mitigation: Cache quota checks in Moqui; async updates.



8. Development Task Breakdown



Task
Module
AI Tool
Priority
Est. Iterations
Test Coverage



Moqui User/Party Creation
Moqui Backend
iFlow
1
2
95%


MinIO User/Bucket/Policy Setup
MinIO
iFlow
1
2
95%


Permission Mapping (SecurityGroup→Policy)
Moqui+MinIO
iFlow
1
3
95%


Quota Management
MinIO
iFlow
2
2
95%


Audit Logging (Webhook→AuditLog)
MinIO+Moqui
iFlow
2
2
95%


OnlyOffice Integration (S3+JWT)
OnlyOffice
iFlow
3
3
95%


Vue UI (Permissions, File Ops, Audit)
Vue
Gemini CLI
4
3
95%


HBuilder UI (Mini-program)
HBuilder
Gemini CLI
4
2
95%


Total Iterations: ~19 (split across modules, <5/module).Directory Structure:

.ai/code/moqui/services/: Moqui Service (custom.minio.CreateUserAndBucket).
.ai/code/minio/policies/: MinIO policy JSON.
.ai/code/vue/: Vue components.
.ai/code/hbuilder/: UniApp components.

9. Clarification Points

Moqui Service Parameters: Confirm input fields for custom.minio.CreateUserAndBucket (e.g., quota default=5GB)?
Vue UI Style: Specific Vuetify theme (e.g., colors, dark mode)?
Audit Log Format: Additional fields for AuditLog Entity (e.g., IP address)?
Quota Adjustment UI: Vue form for main/ops to update quota?

10. Next Steps
    Architecture design complete, stored in .ai/docs/architecture_v1.md. Enter /code to summon programmer Agent for implementation (iFlow for Moqui/MinIO, Gemini CLI for Vue/HBuilder). Provide feedback on clarification points to refine design.