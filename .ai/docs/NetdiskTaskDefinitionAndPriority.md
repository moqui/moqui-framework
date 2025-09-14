Netdisk Task Definition and Priority Plan
Overview
This document defines a complete set of tasks for the Netdisk project based on the existing NetdiskWorkData.xml (which includes initial tasks like NETDISK-TASK-001 to NETDISK-TASK-015) and the project's requirements (storage bucket management, file operations, document collaboration, user management, audit logs). Tasks are extended to be more comprehensive and granular, aligned with the metaphorical patterns ("file cabinet" for storage, "community" for collaboration, "e-commerce" for transactions).
Tasks are first listed in full, then prioritized with role assignments. Finally, an importable XML file (NetdiskTaskUpdate.xml) is provided to update the HiveMind NETDISK project.
Complete Task List
The tasks are divided by milestones, with dependencies noted. New tasks are added to cover gaps (e.g., audit logs, user feedback, OnlyOffice integration, security checks).
Milestone 1: Storage Bucket Management (NETDISK-MS-001)

NETDISK-TASK-001: Develop Storage Bucket Management Service

Description: Develop service for creating, listing, and deleting storage buckets with 5GB quota limit.
Dependencies: None (base task).
Estimated Time: 20 hours.


NETDISK-TASK-004: Perfect Storage Bucket Quota Function

Description: Implement quota check to prevent exceeding 5GB limit.
Dependencies: NETDISK-TASK-001.
Estimated Time: 10 hours.


NETDISK-TASK-005: Optimize Storage Bucket Interface

Description: Enhance Moqui screen for bucket operations, ensuring intuitive UI.
Dependencies: NETDISK-TASK-001.
Estimated Time: 8 hours.


NETDISK-TASK-006: Extend Storage Bucket Tests

Description: Expand JUnit/Selenium tests for quota and error scenarios.
Dependencies: NETDISK-TASK-004.
Estimated Time: 6 hours.


NETDISK-TASK-007: Update PRD Document

Description: Update Wiki with quota management details.
Dependencies: NETDISK-TASK-003.
Estimated Time: 5 hours.


NETDISK-TASK-016: Implement Bucket Security Checks (New)

Description: Add JWT authentication and permission checks for bucket operations.
Dependencies: NETDISK-TASK-001.
Estimated Time: 5 hours.



Milestone 2: File Operations (NETDISK-MS-002)

NETDISK-TASK-008: Develop File Upload Service

Description: Implement file upload with progress display, fix MinIO config issues.
Dependencies: NETDISK-MS-001 (storage bucket setup).
Estimated Time: 15 hours.


NETDISK-TASK-009: Develop File Download and Sharing

Description: Implement download and 7-day sharing links.
Dependencies: NETDISK-TASK-008.
Estimated Time: 12 hours.


NETDISK-TASK-010: Test File Operations

Description: Test upload, download, and sharing functionality.
Dependencies: NETDISK-TASK-009.
Estimated Time: 8 hours.


NETDISK-TASK-011: Update Wiki Storage Guide

Description: Supplement Wiki with file operation instructions.
Dependencies: NETDISK-TASK-010.
Estimated Time: 5 hours.


NETDISK-TASK-017: Implement File Audit Logging (New)

Description: Log file operations with ipAddress in AuditLog.xml.
Dependencies: NETDISK-TASK-009.
Estimated Time: 6 hours.



Milestone 3: Document Collaboration (NETDISK-MS-003)

NETDISK-TASK-012: Integrate OnlyOffice Editor

Description: Integrate OnlyOffice for online document editing, save to MinIO.
Dependencies: NETDISK-MS-002 (file operations).
Estimated Time: 20 hours.


NETDISK-TASK-013: Test OnlyOffice Integration

Description: Test editing, saving, and collaboration.
Dependencies: NETDISK-TASK-012.
Estimated Time: 10 hours.


NETDISK-TASK-014: Create OnlyOffice Wiki Page

Description: Add Wiki page with OnlyOffice usage guide.
Dependencies: NETDISK-TASK-013.
Estimated Time: 5 hours.


NETDISK-TASK-015: Configure Task Update Notifications

Description: Set up notifications for task and Wiki updates.
Dependencies: NETDISK-TASK-014.
Estimated Time: 6 hours.


NETDISK-TASK-018: Implement User Management Interface (New)

Description: Develop screen for adding/deleting users and assigning permissions.
Dependencies: NETDISK-MS-001 (roles defined).
Estimated Time: 8 hours.


NETDISK-TASK-019: Implement Audit Log Viewer (New)

Description: Create screen to view audit logs with ipAddress and filters.
Dependencies: NETDISK-TASK-017.
Estimated Time: 7 hours.


NETDISK-TASK-020: User Feedback Survey (New)

Description: Add survey for netdisk usage feedback.
Dependencies: NETDISK-MS-003 (collaboration complete).
Estimated Time: 5 hours.



Task Priority and Role Assignment
Tasks are prioritized (1 = Highest, 3 = Lowest) based on dependencies and critical path. Roles are assigned per metaphorical pattern (e.g., developer for "building the cabinet," tester for "checking reliability").
Milestone 1: Storage Bucket Management (Priority Focus: High)

NETDISK-TASK-001: Priority 1, Role: Developer (USER002)
NETDISK-TASK-004: Priority 1, Role: Developer (USER002)
NETDISK-TASK-005: Priority 2, Role: Developer (USER002)
NETDISK-TASK-006: Priority 2, Role: Tester (USER003)
NETDISK-TASK-007: Priority 3, Role: Analyst (USER004)
NETDISK-TASK-016: Priority 2, Role: Developer (USER002)

Milestone 2: File Operations (Priority Focus: Medium-High)

NETDISK-TASK-008: Priority 1, Role: Developer (USER002)
NETDISK-TASK-009: Priority 2, Role: Developer (USER002)
NETDISK-TASK-010: Priority 2, Role: Tester (USER003)
NETDISK-TASK-011: Priority 3, Role: Analyst (USER004)
NETDISK-TASK-017: Priority 2, Role: Developer (USER002)

Milestone 3: Document Collaboration (Priority Focus: Medium)

NETDISK-TASK-012: Priority 1, Role: Developer (USER002)
NETDISK-TASK-013: Priority 2, Role: Tester (USER003)
NETDISK-TASK-014: Priority 3, Role: Analyst (USER004)
NETDISK-TASK-015: Priority 3, Role: Admin (USER001)
NETDISK-TASK-018: Priority 2, Role: Developer (USER002)
NETDISK-TASK-019: Priority 2, Role: Developer (USER002)
NETDISK-TASK-020: Priority 3, Role: Analyst (USER004)

NetdiskTaskUpdate.xml
This XML updates the HiveMind NETDISK project with task priorities, roles, and dependencies. Load it via ./gradlew load -PdataFile=./runtime/component/HiveMind/data/NetdiskTaskUpdate.xml.
<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml type="demo">
    <!-- Update Existing Tasks with Priority -->
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-001" priority="1"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-004" priority="1"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-005" priority="2"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-006" priority="2"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-007" priority="3"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-008" priority="1"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-009" priority="2"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-010" priority="2"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-011" priority="3"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-012" priority="1"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-013" priority="2"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-014" priority="3"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-015" priority="3"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-016" priority="2"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-017" priority="2"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-018" priority="2"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-019" priority="2"/>
    <mantle.work.effort.WorkEffort workEffortId="NETDISK-TASK-020" priority="3"/>

    <!-- New Dependencies for Incremental Progress -->
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-001" toWorkEffortId="NETDISK-TASK-004" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-004" toWorkEffortId="NETDISK-TASK-005" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-005" toWorkEffortId="NETDISK-TASK-006" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-003" toWorkEffortId="NETDISK-TASK-007" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-001" toWorkEffortId="NETDISK-TASK-016" workEffortAssocTypeEnumId="WeatDependsOn"/>

    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-MS-001" toWorkEffortId="NETDISK-TASK-008" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-008" toWorkEffortId="NETDISK-TASK-009" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-009" toWorkEffortId="NETDISK-TASK-010" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-010" toWorkEffortId="NETDISK-TASK-011" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-009" toWorkEffortId="NETDISK-TASK-017" workEffortAssocTypeEnumId="WeatDependsOn"/>

    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-MS-002" toWorkEffortId="NETDISK-TASK-012" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-012" toWorkEffortId="NETDISK-TASK-013" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-013" toWorkEffortId="NETDISK-TASK-014" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-014" toWorkEffortId="NETDISK-TASK-015" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-MS-001" toWorkEffortId="NETDISK-TASK-018" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-TASK-017" toWorkEffortId="NETDISK-TASK-019" workEffortAssocTypeEnumId="WeatDependsOn"/>
    <mantle.work.effort.WorkEffortAssoc workEffortId="NETDISK-MS-003" toWorkEffortId="NETDISK-TASK-020" workEffortAssocTypeEnumId="WeatDependsOn"/>
</entity-facade-xml>
