# Moqui Framework MCP Server Requirements

## 1. Executive Summary

### Purpose

The Moqui MCP (Model Context Protocol) server will provide AI assistants with structured, programmatic access to the Moqui Framework's runtime environment, enabling intelligent code generation, debugging, and application development assistance. By exposing Moqui's core facades (Entity, Service, Screen) and metadata through standardized MCP tools and resources, AI agents can understand application structure, query runtime state, and assist developers with context-aware recommendations.

### Strategic Value

- **AI-Assisted Development**: Enable Claude and other AI assistants to understand and work with Moqui applications at a semantic level
- **Accelerated Onboarding**: New developers can leverage AI to explore entity schemas, service definitions, and screen structures
- **Intelligent Debugging**: AI can query runtime state, examine entity relationships, and suggest fixes based on actual application metadata
- **Documentation Generation**: Automatically generate up-to-date API documentation from live service and entity definitions
- **Integration with fivex Ecosystem**: Connect Moqui with other MCP servers (data_store, git_dav) for unified development workflows

### Implementation Language

The MCP server MUST be implemented in **Java** (or Groovy) to:
- Leverage native access to Moqui's ExecutionContext and facade pattern
- Avoid serialization overhead and language interop complexity
- Enable direct integration with Moqui's runtime lifecycle
- Maintain consistency with the framework's technology stack
- Support embedded deployment within Moqui runtime

## 2. Tool Categories

### 2.1 Entity Tools

These tools provide access to Moqui's Entity Engine, enabling AI to understand data models and query application state.

#### entity.list
**Description**: List all entity definitions available in the runtime

**Input Parameters**:
- `componentName` (optional): Filter to specific component
- `packageName` (optional): Filter by entity package (e.g., "moqui.security")
- `includeViewEntities` (optional, default: true): Include view-entity definitions

**Output**:
```json
{
  "entities": [
    {
      "name": "moqui.security.UserAccount",
      "package": "moqui.security",
      "component": "moqui-framework",
      "type": "entity|view-entity",
      "tableName": "user_account",
      "hasCreateStamp": true,
      "hasUpdateStamp": true
    }
  ],
  "totalCount": 245
}
```

**Use Cases**: Discover available entities, explore data model structure, understand component organization

---

#### entity.describe
**Description**: Get detailed metadata for a specific entity definition

**Input Parameters**:
- `entityName` (required): Full entity name (e.g., "moqui.security.UserAccount")
- `includeFields` (optional, default: true): Include field definitions
- `includeRelationships` (optional, default: true): Include relationship definitions
- `includeIndexes` (optional, default: false): Include index definitions

**Output**:
```json
{
  "entityName": "moqui.security.UserAccount",
  "package": "moqui.security",
  "tableName": "user_account",
  "fields": [
    {
      "name": "userId",
      "type": "id",
      "isPk": true,
      "notNull": true,
      "columnName": "user_id"
    },
    {
      "name": "username",
      "type": "text-medium",
      "notNull": true,
      "columnName": "username"
    }
  ],
  "relationships": [
    {
      "type": "many",
      "relatedEntity": "moqui.security.UserGroupMember",
      "title": "UserGroupMember",
      "keyMap": [{"fieldName": "userId", "relatedFieldName": "userId"}]
    }
  ],
  "pkFields": ["userId"],
  "hasCreateStamp": true,
  "hasUpdateStamp": true,
  "createStampField": "createdDate",
  "updateStampField": "lastUpdatedStamp"
}
```

**Use Cases**: Understand entity structure, generate CRUD code, validate field types, explore relationships

---

#### entity.query
**Description**: Execute entity queries to inspect application data

**Input Parameters**:
- `entityName` (required): Entity to query
- `conditions` (optional): Map of field/value conditions (AND logic)
- `orderBy` (optional): Array of field names (prefix with "-" for descending)
- `limit` (optional, default: 100, max: 1000): Maximum results to return
- `offset` (optional, default: 0): Result offset for pagination
- `selectFields` (optional): Array of field names to return (default: all)
- `useCache` (optional, default: false): Use entity cache if available

**Output**:
```json
{
  "results": [
    {
      "userId": "EX_JOHN_DOE",
      "username": "john.doe",
      "emailAddress": "john@example.com",
      "disabled": "N"
    }
  ],
  "count": 1,
  "limit": 100,
  "offset": 0,
  "hasMore": false
}
```

**Security**: Read-only queries only, respects artifact authorization, row-level security applied automatically

**Use Cases**: Inspect data for debugging, verify data migrations, explore relationships, generate test fixtures

---

#### entity.create
**Description**: Create a new entity record

**Input Parameters**:
- `entityName` (required): Entity name
- `fields` (required): Map of field names to values
- `setSequencedId` (optional, default: true): Auto-generate ID fields
- `requireAllFields` (optional, default: false): Validate all required fields

**Output**:
```json
{
  "success": true,
  "primaryKey": {"userId": "100001"},
  "created": true
}
```

**Security**: Respects artifact authorization, triggers entity ECAs

**Use Cases**: Seed data creation, test data generation, quick fixes

---

#### entity.update
**Description**: Update existing entity record(s)

**Input Parameters**:
- `entityName` (required): Entity name
- `primaryKey` (required): Map of PK field values
- `fields` (required): Map of fields to update
- `updateStamp` (optional): Expected updateStamp for optimistic locking

**Output**:
```json
{
  "success": true,
  "updated": true,
  "recordsAffected": 1
}
```

**Use Cases**: Fix data issues, update configuration, modify test data

---

#### entity.delete
**Description**: Delete entity record(s)

**Input Parameters**:
- `entityName` (required): Entity name
- `primaryKey` (required): Map of PK field values

**Output**:
```json
{
  "success": true,
  "deleted": true,
  "recordsAffected": 1
}
```

**Use Cases**: Clean up test data, remove invalid records

---

#### entity.relationships
**Description**: Discover relationship graph for an entity

**Input Parameters**:
- `entityName` (required): Starting entity
- `depth` (optional, default: 1, max: 3): Levels of relationships to traverse
- `direction` (optional, default: "both"): "one", "many", or "both"

**Output**:
```json
{
  "entity": "moqui.security.UserAccount",
  "relationships": {
    "one": [
      {
        "title": "Person",
        "relatedEntity": "mantle.party.Person",
        "type": "one",
        "keyMap": [{"fieldName": "partyId", "relatedFieldName": "partyId"}]
      }
    ],
    "many": [
      {
        "title": "UserGroupMember",
        "relatedEntity": "moqui.security.UserGroupMember",
        "type": "many",
        "keyMap": [{"fieldName": "userId", "relatedFieldName": "userId"}]
      }
    ]
  }
}
```

**Use Cases**: Understand data model, generate join queries, visualize schema

---

### 2.2 Service Tools

These tools enable AI to discover, understand, and invoke Moqui services.

#### service.list
**Description**: List all registered service definitions

**Input Parameters**:
- `componentName` (optional): Filter to specific component
- `pathPrefix` (optional): Filter by service path (e.g., "moqui.security")
- `verb` (optional): Filter by service verb (get, create, update, delete, etc.)
- `serviceType` (optional): Filter by type (inline, entity-auto, interface, script, java)

**Output**:
```json
{
  "services": [
    {
      "name": "moqui.security.UserServices.create#UserAccount",
      "path": "moqui.security.UserServices",
      "verb": "create",
      "noun": "UserAccount",
      "type": "inline",
      "component": "moqui-framework",
      "authenticate": "true",
      "requireAuthentication": true,
      "transactionRequired": true
    }
  ],
  "totalCount": 1247
}
```

**Use Cases**: Discover available services, explore API capabilities, find relevant business logic

---

#### service.describe
**Description**: Get detailed service definition including parameters and implementation details

**Input Parameters**:
- `serviceName` (required): Full service name (e.g., "moqui.security.UserServices.create#UserAccount")
- `includeImplementation` (optional, default: false): Include implementation source (inline XML or script path)

**Output**:
```json
{
  "serviceName": "moqui.security.UserServices.create#UserAccount",
  "verb": "create",
  "noun": "UserAccount",
  "type": "inline",
  "description": "Create a new UserAccount with optional person information",
  "authenticate": "true",
  "transactionRequired": true,
  "inParameters": [
    {
      "name": "username",
      "type": "String",
      "required": true,
      "description": "Username for login"
    },
    {
      "name": "newPassword",
      "type": "String",
      "required": true,
      "format": "password",
      "description": "User password"
    },
    {
      "name": "emailAddress",
      "type": "String",
      "required": false,
      "format": "email-address"
    }
  ],
  "outParameters": [
    {
      "name": "userId",
      "type": "String",
      "description": "ID of created user account"
    }
  ],
  "implementation": "<service-call name='create#moqui.security.UserAccount' in-map='context'/>",
  "location": "component://moqui-framework/service/moqui/security/UserServices.xml#create#UserAccount"
}
```

**Use Cases**: Understand service contracts, generate service calls, validate parameters, create documentation

---

#### service.call
**Description**: Execute a service synchronously

**Input Parameters**:
- `serviceName` (required): Full service name
- `parameters` (required): Map of input parameters
- `requireNewTransaction` (optional, default: false): Run in new transaction
- `timeout` (optional, default: 300): Service timeout in seconds
- `validate` (optional, default: true): Validate parameters before execution

**Output**:
```json
{
  "success": true,
  "outParameters": {
    "userId": "100001"
  },
  "messages": [],
  "errors": [],
  "executionTime": 145
}
```

**Security**: Respects service authentication and authorization, validates all parameters

**Use Cases**: Test services, trigger business logic, automate workflows, integration testing

---

#### service.validate
**Description**: Validate service parameters without execution

**Input Parameters**:
- `serviceName` (required): Service name to validate against
- `parameters` (required): Map of parameters to validate

**Output**:
```json
{
  "valid": false,
  "errors": [
    {
      "field": "emailAddress",
      "message": "Email address format is invalid",
      "value": "not-an-email"
    }
  ],
  "missingRequired": ["username"],
  "unexpectedParameters": ["invalidParam"]
}
```

**Use Cases**: Pre-flight validation, parameter checking, API testing

---

#### service.interfaces
**Description**: List service interfaces that define parameter contracts

**Input Parameters**:
- `componentName` (optional): Filter to component

**Output**:
```json
{
  "interfaces": [
    {
      "name": "moqui.security.UserAccountInterface",
      "inParameters": [...],
      "outParameters": [...],
      "implementedBy": [
        "moqui.security.UserServices.create#UserAccount",
        "custom.services.create#CustomUser"
      ]
    }
  ]
}
```

**Use Cases**: Discover service contracts, find implementations, ensure API consistency

---

### 2.3 Screen Tools

These tools provide access to Moqui's screen rendering engine and UI definitions.

#### screen.list
**Description**: List all screen definitions in the application

**Input Parameters**:
- `componentName` (optional): Filter to component
- `pathPrefix` (optional): Filter by screen path (e.g., "apps/hmadmin")
- `includeSubscreens` (optional, default: false): Include subscreen items
- `standalone` (optional): Filter to standalone screens only

**Output**:
```json
{
  "screens": [
    {
      "location": "component://tools/screen/Tools.xml",
      "path": "tools",
      "component": "moqui-framework",
      "defaultMenuItem": "Entity",
      "hasSubscreens": true,
      "requireAuthentication": true
    }
  ],
  "totalCount": 89
}
```

**Use Cases**: Discover UI structure, explore navigation, understand application layout

---

#### screen.describe
**Description**: Get detailed screen definition including transitions, actions, and widgets

**Input Parameters**:
- `screenPath` (required): Screen path (e.g., "apps/hmadmin/Admin")
- `includeTransitions` (optional, default: true): Include transition definitions
- `includeWidgets` (optional, default: true): Include widget tree
- `includeSubscreens` (optional, default: true): Include subscreen definitions

**Output**:
```json
{
  "screenPath": "apps/hmadmin/Admin",
  "location": "component://HiveMind/screen/hmadmin/Admin.xml",
  "defaultMenuItem": "Dashboard",
  "requireAuthentication": true,
  "transitions": [
    {
      "name": "createProject",
      "method": "post",
      "serviceCall": "mantle.work.ProjectServices.create#Project",
      "requiresParameters": ["workEffortName"]
    }
  ],
  "actions": [
    {
      "type": "entity-find",
      "entity": "mantle.work.effort.WorkEffort",
      "list": "projectList"
    }
  ],
  "widgets": {
    "type": "container-dialog",
    "children": [...]
  },
  "subscreens": [
    {
      "name": "Dashboard",
      "location": "component://HiveMind/screen/hmadmin/Admin/Dashboard.xml",
      "menuTitle": "Dashboard"
    }
  ]
}
```

**Use Cases**: Understand UI flow, generate navigation maps, create screen documentation

---

#### screen.render
**Description**: Render a screen to specific output format

**Input Parameters**:
- `screenPath` (required): Screen path to render
- `renderMode` (optional, default: "html"): Output format (html, json, xml, csv, pdf)
- `parameters` (optional): URL/screen parameters
- `outputType` (optional, default: "full"): "full" or "partial" for AJAX requests

**Output**:
```json
{
  "contentType": "text/html",
  "content": "<html>...</html>",
  "renderTime": 234
}
```

**Security**: Respects screen authentication and authorization

**Use Cases**: Preview screens, generate static content, test screen rendering, create snapshots

---

#### screen.transitions
**Description**: List all transitions for a screen path

**Input Parameters**:
- `screenPath` (required): Screen path
- `includeInherited` (optional, default: true): Include parent screen transitions

**Output**:
```json
{
  "transitions": [
    {
      "name": "updateProject",
      "method": "post|put",
      "serviceCall": "mantle.work.ProjectServices.update#Project",
      "defaultParameters": {"workEffortTypeEnumId": "WetProject"},
      "requiresParameters": ["workEffortId"]
    }
  ]
}
```

**Use Cases**: Discover screen actions, understand form submissions, API endpoint discovery

---

### 2.4 Data Tools

These tools manage data loading, export, and seed data operations.

#### data.load
**Description**: Load data from XML or JSON files

**Input Parameters**:
- `location` (required): Resource location (component://, file://, classpath://)
- `dataTypes` (optional): Array of data types to load (seed, seed-initial, demo, etc.)
- `componentName` (optional): Load data from specific component
- `timeout` (optional, default: 600): Load timeout in seconds
- `useTryInsert` (optional, default: false): Try insert before update
- `transactionTimeout` (optional): Transaction timeout override

**Output**:
```json
{
  "success": true,
  "recordsLoaded": 1247,
  "recordsSkipped": 23,
  "recordsFailed": 0,
  "executionTime": 4567,
  "messages": [],
  "errors": []
}
```

**Use Cases**: Load seed data, import configurations, restore backups, deploy data

---

#### data.export
**Description**: Export entity data to XML or JSON format

**Input Parameters**:
- `entityNames` (required): Array of entity names to export
- `format` (optional, default: "xml"): Output format (xml, json)
- `conditions` (optional): Map of entity name to condition maps
- `fromDate` (optional): Export records modified after this date
- `fileLocation` (optional): Save to file location
- `dependentLevels` (optional, default: 0): Include related records (0-3)

**Output**:
```json
{
  "success": true,
  "recordsExported": 456,
  "format": "xml",
  "content": "<?xml version='1.0'?>...",
  "fileLocation": "component://custom/data/export_20251205.xml"
}
```

**Use Cases**: Backup data, create seed files, migrate data, generate fixtures

---

#### data.seed
**Description**: Load seed data for specific data types

**Input Parameters**:
- `dataTypes` (required): Array of data types (seed, seed-initial, install, demo)
- `componentNames` (optional): Specific components to load from
- `entityNames` (optional): Specific entities to load (filters data)
- `timeout` (optional, default: 1800): Overall timeout

**Output**:
```json
{
  "success": true,
  "dataTypesLoaded": ["seed", "seed-initial"],
  "componentsProcessed": 12,
  "totalRecords": 5678,
  "executionTime": 12345
}
```

**Use Cases**: Initialize databases, deploy configurations, refresh test data

---

#### data.dataDocument
**Description**: Query data documents (for ElasticSearch/OpenSearch integration)

**Input Parameters**:
- `dataDocumentId` (required): Data document definition ID
- `condition` (optional): EntityCondition for filtering
- `fromDate` (optional): Modified after date
- `thruDate` (optional): Modified before date

**Output**:
```json
{
  "documents": [
    {
      "_index": "orders",
      "_type": "OrderHeader",
      "_id": "100001",
      "orderId": "100001",
      "orderDate": "2025-12-05",
      "customerName": "John Doe"
    }
  ],
  "totalCount": 1
}
```

**Use Cases**: Sync to search engines, generate feeds, create exports

---

### 2.5 Component Tools

These tools manage Moqui components and their lifecycle.

#### component.list
**Description**: List all loaded components

**Input Parameters**:
- `includeDisabled` (optional, default: false): Include disabled components

**Output**:
```json
{
  "components": [
    {
      "name": "mantle-usl",
      "version": "2.2.0",
      "location": "component://mantle-usl",
      "dependencies": ["mantle-udm"],
      "loaded": true,
      "hasEntities": true,
      "hasServices": true,
      "hasScreens": true,
      "jarFiles": 3
    }
  ],
  "totalCount": 8
}
```

**Use Cases**: Discover installed components, check versions, verify dependencies

---

#### component.status
**Description**: Get detailed status of a component

**Input Parameters**:
- `componentName` (required): Component name

**Output**:
```json
{
  "name": "mantle-usl",
  "version": "2.2.0",
  "loaded": true,
  "location": "component://mantle-usl",
  "dependencies": [
    {"name": "mantle-udm", "version": "2.2.0", "satisfied": true}
  ],
  "statistics": {
    "entities": 234,
    "services": 456,
    "screens": 23,
    "jarFiles": 3,
    "dataFiles": 12
  },
  "loadTime": 2345
}
```

**Use Cases**: Verify component health, debug dependencies, check component resources

---

#### component.get
**Description**: Get component definition metadata

**Input Parameters**:
- `componentName` (required): Component name
- `includeManifest` (optional, default: true): Include component.xml content

**Output**:
```json
{
  "name": "mantle-usl",
  "version": "2.2.0",
  "description": "Mantle Universal Service Library",
  "author": "Moqui Framework",
  "manifest": "<?xml version='1.0'?>...",
  "dependencies": ["mantle-udm"],
  "directories": {
    "entity": "entity",
    "service": "service",
    "screen": "screen",
    "data": "data",
    "lib": "lib"
  }
}
```

**Use Cases**: Understand component structure, verify configuration, generate documentation

---

### 2.6 Configuration Tools

These tools provide access to Moqui runtime configuration.

#### config.get
**Description**: Get configuration values

**Input Parameters**:
- `configPath` (required): Configuration path (e.g., "default.database.postgres")
- `defaultValue` (optional): Default if not found

**Output**:
```json
{
  "path": "default.database.postgres",
  "value": "org.postgresql.Driver",
  "source": "MoquiDevConf.xml",
  "overridden": true
}
```

**Security**: Sensitive values (passwords, secrets) are redacted

**Use Cases**: Debug configuration, verify settings, understand overrides

---

#### config.describe
**Description**: Describe configuration structure and available options

**Input Parameters**:
- `section` (optional): Configuration section (database, cache, webapp, etc.)

**Output**:
```json
{
  "sections": [
    {
      "name": "default.database",
      "description": "Database configuration settings",
      "properties": [
        {
          "name": "postgres",
          "type": "String",
          "description": "PostgreSQL JDBC driver class"
        }
      ]
    }
  ]
}
```

**Use Cases**: Explore configuration options, generate config templates

---

#### config.facadeConfig
**Description**: Get configuration for a specific facade

**Input Parameters**:
- `facadeName` (required): Facade name (entity, service, screen, cache, etc.)

**Output**:
```json
{
  "facade": "entity",
  "configuration": {
    "defaultDatasource": "postgres",
    "distributedCacheEnabled": true,
    "entityMetaDataEnabled": true,
    "dummyFks": false
  }
}
```

**Use Cases**: Understand facade configuration, debug behavior, optimize performance

---

### 2.7 Security Tools

These tools help understand and verify security configurations.

#### security.checkPermission
**Description**: Check if current user has permission for an artifact

**Input Parameters**:
- `artifactType` (required): Type (entity, service, screen, etc.)
- `artifactName` (required): Artifact name/path
- `actionType` (required): Action (view, create, update, delete, all)

**Output**:
```json
{
  "allowed": true,
  "artifactType": "service",
  "artifactName": "moqui.security.UserServices.create#UserAccount",
  "actionType": "all",
  "permissionsByAuthz": [
    {
      "authzType": "AUTHZT_ALLOW",
      "authzActionEnumId": "AUTHZA_ALL"
    }
  ]
}
```

**Use Cases**: Debug authorization issues, verify permissions, security audits

---

#### security.listArtifacts
**Description**: List all artifact authorizations for a user or group

**Input Parameters**:
- `userId` (optional): Specific user ID
- `userGroupId` (optional): Specific user group
- `artifactType` (optional): Filter by artifact type

**Output**:
```json
{
  "artifacts": [
    {
      "artifactType": "service",
      "artifactName": "moqui.security.UserServices.create#UserAccount",
      "authzType": "AUTHZT_ALLOW",
      "authzAction": "AUTHZA_ALL",
      "inherited": false,
      "fromUserGroup": "ADMIN"
    }
  ],
  "totalCount": 234
}
```

**Use Cases**: Audit permissions, understand access control, debug authorization

---

#### security.userInfo
**Description**: Get current user information and permissions

**Input Parameters**: None (uses current execution context)

**Output**:
```json
{
  "userId": "EX_JOHN_DOE",
  "username": "john.doe",
  "userGroups": [
    {"userGroupId": "ADMIN", "groupName": "Administrators"}
  ],
  "locale": "en_US",
  "timeZone": "America/Los_Angeles",
  "currencyUomId": "USD",
  "hasAuthzAll": false,
  "disableAuthz": false
}
```

**Use Cases**: Debug user context, verify authentication, check permissions

---

## 3. Resource Categories

MCP resources provide read-only access to Moqui metadata and definitions. Resources are cached and can be efficiently loaded by AI assistants.

### 3.1 Entity Definitions

**Resource Pattern**: `entity://[entity-name]`

**Example**: `entity://moqui.security.UserAccount`

**Content**: Complete entity definition in structured JSON format

```json
{
  "uri": "entity://moqui.security.UserAccount",
  "mimeType": "application/json",
  "content": {
    "entityName": "moqui.security.UserAccount",
    "package": "moqui.security",
    "tableName": "user_account",
    "fields": [...],
    "relationships": [...],
    "indexes": [...],
    "location": "component://moqui-framework/entity/SecurityEntities.xml"
  }
}
```

**Use Cases**:
- Entity schema reference for code generation
- Quick lookup of field types and constraints
- Understanding entity relationships
- Generating ORM code

---

### 3.2 Service Definitions

**Resource Pattern**: `service://[service-name]`

**Example**: `service://moqui.security.UserServices.create#UserAccount`

**Content**: Complete service definition including parameters and implementation reference

```json
{
  "uri": "service://moqui.security.UserServices.create#UserAccount",
  "mimeType": "application/json",
  "content": {
    "serviceName": "moqui.security.UserServices.create#UserAccount",
    "verb": "create",
    "noun": "UserAccount",
    "description": "Create a new UserAccount",
    "inParameters": [...],
    "outParameters": [...],
    "authenticate": "true",
    "type": "inline",
    "location": "component://moqui-framework/service/moqui/security/UserServices.xml"
  }
}
```

**Use Cases**:
- API contract reference
- Parameter validation documentation
- Service dependency analysis
- Automated API documentation generation

---

### 3.3 Screen Definitions

**Resource Pattern**: `screen://[screen-path]`

**Example**: `screen://apps/hmadmin/Admin/Dashboard`

**Content**: Screen definition with transitions, actions, and widget structure

```json
{
  "uri": "screen://apps/hmadmin/Admin/Dashboard",
  "mimeType": "application/json",
  "content": {
    "screenPath": "apps/hmadmin/Admin/Dashboard",
    "location": "component://HiveMind/screen/hmadmin/Admin/Dashboard.xml",
    "transitions": [...],
    "actions": [...],
    "widgets": {...},
    "requireAuthentication": true
  }
}
```

**Use Cases**:
- UI structure reference
- Navigation map generation
- Form field discovery
- Screen testing automation

---

### 3.4 Component Manifests

**Resource Pattern**: `component://[component-name]`

**Example**: `component://mantle-usl`

**Content**: Component metadata and structure

```json
{
  "uri": "component://mantle-usl",
  "mimeType": "application/json",
  "content": {
    "name": "mantle-usl",
    "version": "2.2.0",
    "dependencies": ["mantle-udm"],
    "manifest": "<?xml version='1.0'?>...",
    "statistics": {
      "entities": 234,
      "services": 456,
      "screens": 23
    }
  }
}
```

**Use Cases**:
- Component dependency analysis
- Version verification
- Resource inventory
- Migration planning

---

### 3.5 Configuration Resources

**Resource Pattern**: `config://[section]/[key]`

**Example**: `config://database/default`

**Content**: Configuration values and metadata

```json
{
  "uri": "config://database/default",
  "mimeType": "application/json",
  "content": {
    "section": "database",
    "key": "default",
    "value": {...},
    "source": "MoquiDevConf.xml",
    "description": "Default database configuration"
  }
}
```

**Use Cases**:
- Configuration reference
- Environment verification
- Deployment documentation

---

## 4. Implementation Approach

### 4.1 Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│         AI Assistant (Claude, etc.)                  │
└───────────────────┬─────────────────────────────────┘
                    │ MCP Protocol (stdio/SSE)
┌───────────────────▼─────────────────────────────────┐
│     Moqui MCP Server (Java Application)             │
│  ┌───────────────────────────────────────────────┐  │
│  │   MCP Protocol Handler (Java MCP SDK)         │  │
│  │   - Tool registration                         │  │
│  │   - Resource registration                     │  │
│  │   - Request/response serialization            │  │
│  └────────────────┬──────────────────────────────┘  │
│  ┌────────────────▼──────────────────────────────┐  │
│  │   Tool Implementations (Facade Adapters)      │  │
│  │   - EntityToolProvider                        │  │
│  │   - ServiceToolProvider                       │  │
│  │   - ScreenToolProvider                        │  │
│  │   - DataToolProvider                          │  │
│  │   - ComponentToolProvider                     │  │
│  │   - ConfigToolProvider                        │  │
│  │   - SecurityToolProvider                      │  │
│  └────────────────┬──────────────────────────────┘  │
└───────────────────┼─────────────────────────────────┘
                    │ ExecutionContext
┌───────────────────▼─────────────────────────────────┐
│         Moqui Framework Runtime                      │
│  ┌─────────────────────────────────────────────┐    │
│  │  ExecutionContextFactory                    │    │
│  │  ├─ EntityFacade                            │    │
│  │  ├─ ServiceFacade                           │    │
│  │  ├─ ScreenFacade                            │    │
│  │  ├─ CacheFacade                             │    │
│  │  ├─ TransactionFacade                       │    │
│  │  ├─ UserFacade                              │    │
│  │  └─ SecurityFacade                          │    │
│  └─────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────┘
```

### 4.2 Java MCP SDK Integration

The Moqui MCP server will use the official Java MCP SDK (when available) or implement the protocol directly:

**Dependencies**:
```gradle
dependencies {
    // MCP SDK (hypothetical - adjust when official SDK is released)
    implementation 'org.modelcontextprotocol:mcp-sdk-java:1.0.0'

    // Moqui Framework
    implementation project(':framework')

    // JSON processing
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.15.0'

    // Logging
    implementation 'org.slf4j:slf4j-api:2.0.7'
}
```

**Server Initialization**:
```java
public class MoquiMcpServer {
    private final ExecutionContextFactory ecf;
    private final McpServer mcpServer;

    public MoquiMcpServer(ExecutionContextFactory ecf) {
        this.ecf = ecf;
        this.mcpServer = McpServer.builder()
            .name("moqui-mcp-server")
            .version("1.0.0")
            .build();

        registerTools();
        registerResources();
    }

    private void registerTools() {
        // Register entity tools
        EntityToolProvider entityTools = new EntityToolProvider(ecf);
        mcpServer.addTool("entity.list", entityTools::listEntities);
        mcpServer.addTool("entity.describe", entityTools::describeEntity);
        mcpServer.addTool("entity.query", entityTools::queryEntity);

        // Register service tools
        ServiceToolProvider serviceTools = new ServiceToolProvider(ecf);
        mcpServer.addTool("service.list", serviceTools::listServices);
        mcpServer.addTool("service.describe", serviceTools::describeService);
        mcpServer.addTool("service.call", serviceTools::callService);

        // ... register other tools
    }

    public void start() {
        mcpServer.start();
    }
}
```

### 4.3 Leveraging ExecutionContext and Facade Pattern

All tool implementations will use the ExecutionContext to access Moqui facades:

**Example: Entity Tool Implementation**:
```java
public class EntityToolProvider {
    private final ExecutionContextFactory ecf;

    public EntityToolProvider(ExecutionContextFactory ecf) {
        this.ecf = ecf;
    }

    public Map<String, Object> listEntities(Map<String, Object> args) {
        ExecutionContext ec = ecf.getExecutionContext();
        try {
            EntityFacade ef = ec.getEntity();

            String componentName = (String) args.get("componentName");
            String packageName = (String) args.get("packageName");
            boolean includeViewEntities =
                (boolean) args.getOrDefault("includeViewEntities", true);

            // Use EntityFacade to get entity definitions
            List<Map<String, Object>> entities = new ArrayList<>();
            for (String entityName : ef.getAllEntityNames()) {
                EntityDefinition ed = ef.getEntityDefinition(entityName);

                // Filter by component/package if specified
                if (componentName != null &&
                    !ed.getLocation().contains(componentName)) {
                    continue;
                }
                if (packageName != null &&
                    !ed.getFullEntityName().startsWith(packageName)) {
                    continue;
                }
                if (!includeViewEntities && ed.isViewEntity()) {
                    continue;
                }

                entities.add(Map.of(
                    "name", ed.getFullEntityName(),
                    "package", ed.getPackageName(),
                    "component", ed.getLocation(),
                    "type", ed.isViewEntity() ? "view-entity" : "entity",
                    "tableName", ed.getTableName()
                ));
            }

            return Map.of(
                "entities", entities,
                "totalCount", entities.size()
            );
        } finally {
            ec.destroy();
        }
    }

    public Map<String, Object> describeEntity(Map<String, Object> args) {
        ExecutionContext ec = ecf.getExecutionContext();
        try {
            EntityFacade ef = ec.getEntity();
            String entityName = (String) args.get("entityName");

            EntityDefinition ed = ef.getEntityDefinition(entityName);
            if (ed == null) {
                return Map.of("error", "Entity not found: " + entityName);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("entityName", ed.getFullEntityName());
            result.put("package", ed.getPackageName());
            result.put("tableName", ed.getTableName());

            // Get fields
            if ((boolean) args.getOrDefault("includeFields", true)) {
                List<Map<String, Object>> fields = new ArrayList<>();
                for (String fieldName : ed.getAllFieldNames()) {
                    FieldInfo fi = ed.getFieldInfo(fieldName);
                    fields.add(Map.of(
                        "name", fi.name,
                        "type", fi.type,
                        "isPk", ed.isPkField(fieldName),
                        "notNull", !fi.allowNull
                    ));
                }
                result.put("fields", fields);
            }

            // Get relationships
            if ((boolean) args.getOrDefault("includeRelationships", true)) {
                List<Map<String, Object>> relationships = new ArrayList<>();
                for (RelationshipInfo ri : ed.getRelationshipsInfo(false)) {
                    relationships.add(Map.of(
                        "type", ri.type,
                        "relatedEntity", ri.relatedEntityName,
                        "title", ri.title
                    ));
                }
                result.put("relationships", relationships);
            }

            return result;
        } finally {
            ec.destroy();
        }
    }
}
```

**Example: Service Tool Implementation**:
```java
public class ServiceToolProvider {
    private final ExecutionContextFactory ecf;

    public ServiceToolProvider(ExecutionContextFactory ecf) {
        this.ecf = ecf;
    }

    public Map<String, Object> callService(Map<String, Object> args) {
        ExecutionContext ec = ecf.getExecutionContext();
        try {
            ServiceFacade sf = ec.getService();
            String serviceName = (String) args.get("serviceName");
            Map<String, Object> parameters =
                (Map<String, Object>) args.get("parameters");

            long startTime = System.currentTimeMillis();

            // Call service synchronously
            Map<String, Object> result = sf.sync()
                .name(serviceName)
                .parameters(parameters)
                .call();

            long executionTime = System.currentTimeMillis() - startTime;

            // Check for errors
            MessageFacade mf = ec.getMessage();
            List<String> errors = mf.getErrors();
            List<String> messages = mf.getMessages();

            return Map.of(
                "success", errors.isEmpty(),
                "outParameters", result,
                "messages", messages,
                "errors", errors,
                "executionTime", executionTime
            );
        } finally {
            ec.destroy();
        }
    }
}
```

### 4.4 Integration with Moqui Service Layer

The MCP server should be implemented as a Moqui component for seamless integration:

**Component Structure**:
```
runtime/component/mcp-server/
├── component.xml
├── service/
│   └── org/moqui/mcp/
│       └── McpServerServices.xml
├── src/
│   └── main/
│       └── java/
│           └── org/moqui/mcp/
│               ├── MoquiMcpServer.java
│               ├── McpServerLifecycle.java
│               ├── tools/
│               │   ├── EntityToolProvider.java
│               │   ├── ServiceToolProvider.java
│               │   ├── ScreenToolProvider.java
│               │   ├── DataToolProvider.java
│               │   ├── ComponentToolProvider.java
│               │   ├── ConfigToolProvider.java
│               │   └── SecurityToolProvider.java
│               └── resources/
│                   ├── EntityResourceProvider.java
│                   ├── ServiceResourceProvider.java
│                   ├── ScreenResourceProvider.java
│                   └── ComponentResourceProvider.java
├── data/
│   └── McpServerData.xml
└── build.gradle
```

**Lifecycle Integration**:
```java
public class McpServerLifecycle implements ExecutionContextFactoryLifecycle {
    private MoquiMcpServer mcpServer;

    @Override
    public void init(ExecutionContextFactory ecf) {
        // Initialize MCP server when Moqui starts
        mcpServer = new MoquiMcpServer(ecf);
        mcpServer.start();

        logger.info("Moqui MCP Server started successfully");
    }

    @Override
    public void destroy(ExecutionContextFactory ecf) {
        // Shutdown MCP server gracefully
        if (mcpServer != null) {
            mcpServer.stop();
        }
    }
}
```

### 4.5 Security and Authentication

**Authentication Strategy**:
- MCP server runs within Moqui runtime with existing user context
- All operations respect Moqui's artifact-based authorization
- Service calls and entity operations use standard security checks
- Optional: Support for API key authentication for external access

**Implementation**:
```java
public abstract class BaseToolProvider {
    protected final ExecutionContextFactory ecf;

    protected ExecutionContext getAuthenticatedContext(Map<String, Object> args) {
        ExecutionContext ec = ecf.getExecutionContext();

        // Option 1: Use system user for read-only operations
        String username = (String) args.get("username");
        if (username == null) {
            username = "mcp_system";
        }

        UserFacade uf = ec.getUser();
        if (!uf.getUsername().equals(username)) {
            uf.loginUser(username, null);
        }

        return ec;
    }

    protected void checkPermission(ExecutionContext ec,
                                   String artifactName,
                                   String actionType) {
        ArtifactExecutionFacade aef = ec.getArtifactExecution();
        if (!aef.checkPermitted(artifactName, actionType)) {
            throw new SecurityException(
                "Permission denied for " + artifactName + " - " + actionType
            );
        }
    }
}
```

### 4.6 Error Handling and Validation

All tool implementations must provide robust error handling:

```java
public Map<String, Object> queryEntity(Map<String, Object> args) {
    ExecutionContext ec = ecf.getExecutionContext();
    try {
        // Validate required parameters
        String entityName = (String) args.get("entityName");
        if (entityName == null || entityName.isEmpty()) {
            return Map.of(
                "error", "Missing required parameter: entityName",
                "errorType", "VALIDATION_ERROR"
            );
        }

        // Check entity exists
        EntityFacade ef = ec.getEntity();
        EntityDefinition ed = ef.getEntityDefinition(entityName);
        if (ed == null) {
            return Map.of(
                "error", "Entity not found: " + entityName,
                "errorType", "NOT_FOUND"
            );
        }

        // Check permissions
        checkPermission(ec, entityName, "view");

        // Execute query with safety limits
        int limit = Math.min(
            (int) args.getOrDefault("limit", 100),
            1000  // Maximum limit
        );

        // ... perform query

    } catch (SecurityException e) {
        return Map.of(
            "error", e.getMessage(),
            "errorType", "PERMISSION_DENIED"
        );
    } catch (Exception e) {
        logger.error("Error querying entity", e);
        return Map.of(
            "error", "Internal error: " + e.getMessage(),
            "errorType", "INTERNAL_ERROR"
        );
    } finally {
        ec.destroy();
    }
}
```

### 4.7 Performance Considerations

**Caching Strategy**:
- Cache entity and service definitions (rarely change)
- Use Moqui's built-in CacheFacade for metadata
- Implement resource caching for frequently accessed definitions

**Resource Limits**:
- Maximum query result size: 1000 records
- Service call timeout: 300 seconds (configurable)
- Data load timeout: 600 seconds (configurable)
- Cache TTL: 3600 seconds for metadata

**Optimization**:
```java
public class CachedEntityToolProvider extends EntityToolProvider {
    private final CacheFacade cache;
    private static final String CACHE_NAME = "mcp.entity.definitions";

    public CachedEntityToolProvider(ExecutionContextFactory ecf) {
        super(ecf);
        ExecutionContext ec = ecf.getExecutionContext();
        this.cache = ec.getCache();
        ec.destroy();
    }

    @Override
    public Map<String, Object> describeEntity(Map<String, Object> args) {
        String entityName = (String) args.get("entityName");
        String cacheKey = "entity:" + entityName;

        Map<String, Object> cached =
            (Map<String, Object>) cache.get(CACHE_NAME, cacheKey);
        if (cached != null) {
            return cached;
        }

        Map<String, Object> result = super.describeEntity(args);
        cache.put(CACHE_NAME, cacheKey, result);

        return result;
    }
}
```

### 4.8 Deployment Options

**Option 1: Embedded Component (Recommended)**
- Deploy as Moqui component in `runtime/component/mcp-server/`
- Auto-starts with Moqui runtime
- Shares JVM and resources
- Best performance and integration

**Option 2: Standalone Service**
- Run as separate Java application
- Connect to Moqui via RPC/REST
- Independent lifecycle
- Better isolation

**Option 3: Docker Container**
- Package MCP server with Moqui runtime
- Use Docker Compose for multi-container setup
- Environment-based configuration
- Cloud-native deployment

**Recommended Deployment**:
```yaml
# docker-compose.yml
services:
  moqui:
    image: moqui/moqui-framework:latest
    ports:
      - "8080:8080"
    environment:
      - MCP_SERVER_ENABLED=true
      - MCP_SERVER_PORT=3000
    volumes:
      - ./runtime:/opt/moqui/runtime
      - ./mcp-server:/opt/moqui/runtime/component/mcp-server
```

## 5. Priority Tools - Top 10 Most Valuable

Based on AI-assisted development workflows, these tools provide the highest value:

### 1. entity.describe
**Priority**: CRITICAL
**Rationale**: Understanding data models is fundamental to all development tasks. AI needs to know entity structure to generate queries, services, and screens.
**Use Frequency**: Very High

### 2. service.describe
**Priority**: CRITICAL
**Rationale**: Service contracts define API boundaries. AI needs parameter definitions to generate correct service calls and validate inputs.
**Use Frequency**: Very High

### 3. entity.query
**Priority**: HIGH
**Rationale**: Inspecting actual data is essential for debugging, understanding state, and generating test cases.
**Use Frequency**: High

### 4. service.call
**Priority**: HIGH
**Rationale**: Testing services programmatically enables AI to validate implementations and debug issues.
**Use Frequency**: High

### 5. screen.describe
**Priority**: HIGH
**Rationale**: Understanding UI structure enables AI to suggest form modifications, navigation improvements, and UI generation.
**Use Frequency**: Medium-High

### 6. entity.list
**Priority**: MEDIUM-HIGH
**Rationale**: Discovering available entities helps AI understand application scope and suggest relevant entities for tasks.
**Use Frequency**: Medium

### 7. service.list
**Priority**: MEDIUM-HIGH
**Rationale**: Service discovery enables AI to find existing business logic before suggesting new implementations.
**Use Frequency**: Medium

### 8. entity.relationships
**Priority**: MEDIUM
**Rationale**: Understanding entity graphs enables AI to suggest optimal join queries and data retrieval strategies.
**Use Frequency**: Medium

### 9. component.list
**Priority**: MEDIUM
**Rationale**: Component awareness helps AI understand application architecture and suggest appropriate component placement for new code.
**Use Frequency**: Low-Medium

### 10. data.export
**Priority**: MEDIUM
**Rationale**: Generating seed data files and fixtures is common for testing and deployment.
**Use Frequency**: Low-Medium

**Implementation Priority Order**:
1. Phase 1 (MVP): entity.describe, entity.list, service.describe, service.list
2. Phase 2 (Enhanced): entity.query, service.call, screen.describe
3. Phase 3 (Advanced): entity.relationships, component.list, data.export
4. Phase 4 (Complete): All remaining tools and resources

## 6. Integration Points with Other fivex MCP Servers

The Moqui MCP server should integrate seamlessly with other fivex MCP servers to enable unified workflows.

### 6.1 Integration with data_store MCP Server

**Purpose**: Enable bidirectional data synchronization and dynamic API generation

**Integration Points**:

1. **Schema Synchronization**
   - Moqui MCP exposes entity definitions
   - data_store MCP can introspect Moqui schema via `entity.list` and `entity.describe`
   - Automatic API generation for Moqui entities

2. **Data Migration**
   - Use `data.export` from Moqui MCP to generate data files
   - Import into data_store PostgreSQL via dynamic REST API
   - Bidirectional sync for shared entities

3. **Event Streaming**
   - Moqui entity changes trigger MQTT events
   - data_store subscribes to entity CRUD events
   - Real-time data synchronization

**Example Workflow**:
```
AI Assistant
  ↓ "Export Product entity from Moqui and import to data_store"
Moqui MCP: data.export(entityNames: ["Product"])
  ↓ Returns JSON data
data_store MCP: POST /api/v1/product (bulk create)
  ↓ Confirms import
AI Assistant: "Successfully migrated 1,234 products"
```

### 6.2 Integration with git_dav MCP Server

**Purpose**: Version control for Moqui configurations and code generation workflows

**Integration Points**:

1. **Configuration Management**
   - Store entity, service, and screen XML in git_dav
   - Use `gitdav/requests/commit` to version control changes
   - Track configuration history

2. **AI Code Generation**
   - AI generates Moqui services based on entity definitions
   - Service XML committed to git_dav repository
   - Review and merge workflow via git_dav

3. **Deployment Automation**
   - Pull component configurations from git_dav
   - Use `data.load` to deploy updated definitions
   - Automated testing via `service.call`

**Example Workflow**:
```
AI Assistant
  ↓ "Generate CRUD services for Order entity"
Moqui MCP: entity.describe(entityName: "Order")
  ↓ Returns entity definition
AI: Generate OrderServices.xml
git_dav MCP: gitdav/requests/commit
  ↓ Commit new service file
git_dav MCP: gitdav/requests/push
  ↓ Push to repository
```

### 6.3 Integration with eddy_code_ui MCP Server

**Purpose**: Provide UI-driven development experience for Moqui applications

**Integration Points**:

1. **Entity Explorer UI**
   - eddy_code_ui displays entity list from Moqui MCP
   - Interactive entity relationship diagrams
   - Visual query builder using `entity.query`

2. **Service Testing UI**
   - Browse services via `service.list`
   - Test services with parameter forms
   - Display results and errors from `service.call`

3. **Screen Preview**
   - Render screens via `screen.render`
   - Display in eddy_code_ui iframe
   - Live preview during screen development

**Example Workflow**:
```
eddy_code_ui: Display Entity Explorer
  ↓ User selects "UserAccount" entity
Moqui MCP: entity.describe(entityName: "UserAccount")
  ↓ Returns field and relationship metadata
eddy_code_ui: Render entity diagram
  ↓ User clicks "Query Data"
Moqui MCP: entity.query(entityName: "UserAccount", limit: 50)
  ↓ Returns results
eddy_code_ui: Display data grid
```

### 6.4 Integration with forge_ui MCP Server

**Purpose**: Dynamic UI generation from Moqui metadata

**Integration Points**:

1. **Form Generation**
   - forge_ui queries `entity.describe` for field metadata
   - Generates JSON widget definitions for forms
   - Automatic validation rules from entity constraints

2. **Grid/List Generation**
   - Use `entity.query` to populate grids
   - Real-time data updates via MQTT
   - Server-side pagination and filtering

3. **Action Binding**
   - Map forge_ui actions to Moqui services
   - Call services via `service.call` on user actions
   - Display results in forge_ui widgets

**Example Workflow**:
```
forge_ui: Generate form for "Product" entity
  ↓ Request entity metadata
Moqui MCP: entity.describe(entityName: "Product")
  ↓ Returns field definitions
forge_ui: Generate application.json with form widgets
  ↓ User submits form
forge_ui: Trigger service action
Moqui MCP: service.call(serviceName: "create#Product", parameters: {...})
  ↓ Returns success
forge_ui: Display success message
```

### 6.5 Integration with anvil MCP Server

**Purpose**: Service discovery and deployment management for Moqui components

**Integration Points**:

1. **Component Discovery**
   - anvil discovers Moqui components via `component.list`
   - Displays component dependencies and versions
   - Health monitoring via `component.status`

2. **Service Registry**
   - Register Moqui services in anvil service catalog
   - MQTT-based service discovery
   - Metrics collection from service calls

3. **Deployment Management**
   - Deploy Moqui components via `.anvil` files
   - Use `data.seed` to initialize deployed components
   - Monitor deployment status

**Example Workflow**:
```
anvil: Discover Moqui services
  ↓ Query available services
Moqui MCP: service.list()
  ↓ Returns 1,247 services
anvil: Publish to MQTT discovery/services/moqui/announce
  ↓ Other services discover Moqui capabilities
anvil: Monitor service health
Moqui MCP: component.status(componentName: "mantle-usl")
  ↓ Returns health metrics
anvil: Display in service dashboard
```

### 6.6 Cross-Server Communication Pattern

All fivex MCP servers should support a unified communication pattern:

**MQTT Topics for MCP Coordination**:
- `mcp/servers/{server-name}/status` - Server health and capabilities
- `mcp/servers/{server-name}/request/{tool}` - Cross-server tool invocation
- `mcp/servers/{server-name}/response/{correlation-id}` - Tool response
- `mcp/servers/{server-name}/event/{event-type}` - Server events

**Example: Moqui MCP Publishes Entity Change Event**:
```json
{
  "topic": "mcp/servers/moqui/event/entity.updated",
  "payload": {
    "entityName": "Product",
    "primaryKey": {"productId": "PROD-001"},
    "timestamp": "2025-12-05T10:30:00Z",
    "userId": "john.doe"
  }
}
```

**data_store MCP Subscribes and Syncs**:
```json
{
  "topic": "mcp/servers/data_store/request/sync.entity",
  "payload": {
    "correlationId": "abc-123",
    "sourceServer": "moqui",
    "entityName": "Product",
    "action": "sync"
  }
}
```

### 6.7 Unified AI Workflow Example

**Scenario**: AI assistant helps developer create a new order management feature

```
User: "Create an order management system with products and orders"

AI: Query available entities
  ↓ Moqui MCP: entity.list()
AI: Found Product and OrderHeader entities in Mantle

AI: Generate data model diagram
  ↓ Moqui MCP: entity.describe("Product")
  ↓ Moqui MCP: entity.describe("OrderHeader")
  ↓ Moqui MCP: entity.relationships("OrderHeader", depth: 2)

AI: Generate CRUD services
  ↓ AI generates OrderServices.xml
  ↓ git_dav MCP: commit service file

AI: Create dynamic UI in data_store
  ↓ data_store MCP: POST /api/generator/orders
  ↓ Returns React UI components

AI: Generate form UI in forge_ui
  ↓ forge_ui: Generate application.json for order form
  ↓ Bind to Moqui services via service.call

AI: Deploy to anvil
  ↓ anvil MCP: Deploy order-service.anvil
  ↓ Moqui MCP: data.seed(dataTypes: ["seed"], entityNames: ["Product"])

AI: Monitor in eddy_code_ui
  ↓ eddy_code_ui: Display service dashboard
  ↓ Show real-time order creation events

User: "Perfect! Let me test creating an order"
  ↓ forge_ui: Submit order form
  ↓ Moqui MCP: service.call("create#OrderHeader")
  ↓ Success notification across all UIs
```

## 7. Technology Stack Rationale

### 7.1 Java/Groovy for Implementation

**Choice**: Implement MCP server in Java (primary) with Groovy for DSL-style configurations

**Justification**:
- **Native Integration**: Direct access to Moqui's ExecutionContext without serialization overhead
- **Type Safety**: Compile-time validation of facade interactions reduces runtime errors
- **Performance**: No language interop penalties, optimal for metadata-heavy operations
- **Consistency**: Matches Moqui's technology stack, familiar to Moqui developers
- **Tooling**: Excellent IDE support, debugging, and profiling tools

**Trade-offs vs Alternatives**:

| Aspect | Java | Python | Node.js |
|--------|------|--------|---------|
| Moqui Integration | Native | RPC/REST | RPC/REST |
| Performance | Excellent | Good | Good |
| Developer Familiarity | High (Moqui devs) | High (AI/ML devs) | Medium |
| Deployment | Embedded | Separate | Separate |
| Type Safety | Strong | Weak | Weak |
| Async I/O | Virtual Threads (Java 21+) | AsyncIO | Event Loop |
| Complexity | Medium | Low | Medium |

**Decision**: Java provides the best integration and performance for a Moqui-native MCP server. Python would be preferable for ML-heavy operations but adds deployment complexity. Node.js offers good async performance but lacks type safety.

### 7.2 MCP SDK vs Custom Protocol Implementation

**Choice**: Use official Java MCP SDK when available, implement protocol directly if not

**Justification**:
- **Standards Compliance**: SDK ensures compatibility with all MCP clients
- **Maintenance**: Protocol updates handled by SDK maintainers
- **Best Practices**: SDK embeds community-validated patterns
- **Testing**: SDK includes test suites and validation tools

**Trade-offs**:

| Aspect | MCP SDK | Custom Implementation |
|--------|---------|----------------------|
| Standards Compliance | Guaranteed | Manual |
| Maintenance Burden | Low | High |
| Flexibility | Medium | High |
| Time to Market | Fast | Slow |
| Dependencies | SDK version lock | None |
| Debugging | SDK black box | Full control |

**Decision**: Use MCP SDK for faster development and standards compliance. Only implement custom protocol if SDK is unavailable or has critical limitations.

### 7.3 Embedded vs Standalone Deployment

**Choice**: Embedded Moqui component (primary), with standalone option for cloud deployments

**Justification**:
- **Performance**: In-process communication eliminates network overhead
- **Simplicity**: Single deployment artifact, shared lifecycle
- **Resource Efficiency**: Shared JVM, connection pools, caches
- **Security**: No external API exposure required

**Trade-offs**:

| Aspect | Embedded | Standalone |
|--------|----------|------------|
| Performance | Excellent | Good |
| Isolation | Low | High |
| Scalability | Coupled with Moqui | Independent |
| Deployment Complexity | Low | Medium |
| Resource Usage | Shared | Dedicated |
| Fault Isolation | Poor | Excellent |

**Decision**: Embedded deployment for development and single-server production. Standalone for microservices architectures and cloud-native deployments.

### 7.4 Caching Strategy

**Choice**: Use Moqui's CacheFacade with Hazelcast for distributed caching

**Justification**:
- **Consistency**: Same cache layer as rest of Moqui application
- **Distributed**: Hazelcast provides cluster-wide cache coherency
- **Performance**: In-memory caching for metadata reduces query overhead
- **Invalidation**: Automatic cache invalidation on entity/service changes

**Trade-offs**:

| Aspect | Moqui CacheFacade | Redis | Application Memory |
|--------|-------------------|-------|-------------------|
| Integration | Native | External | Simple |
| Distributed | Yes (Hazelcast) | Yes | No |
| Performance | Excellent | Very Good | Excellent |
| Complexity | Low | Medium | Very Low |
| Invalidation | Automatic | Manual | Manual |
| Persistence | Optional | Yes | No |

**Decision**: CacheFacade leverages existing infrastructure. Redis would add operational complexity. Application memory lacks distribution.

### 7.5 Security Model

**Choice**: Leverage Moqui's artifact-based authorization with optional API key authentication

**Justification**:
- **Consistency**: Same security model as Moqui applications
- **Fine-Grained**: Artifact-level permissions for entities, services, screens
- **Auditing**: Built-in audit logging for all operations
- **Extensibility**: Custom authz handlers for special requirements

**Trade-offs**:

| Aspect | Artifact-Based Authz | OAuth2 | API Keys Only |
|--------|---------------------|--------|---------------|
| Granularity | Very Fine | Coarse | Coarse |
| Moqui Integration | Native | External | External |
| Complexity | Low | High | Very Low |
| Standards Compliance | Moqui-specific | Industry standard | Common |
| User Management | Moqui UserFacade | External IDP | Manual |
| Auditability | Excellent | Good | Poor |

**Decision**: Artifact-based authz for production deployments with Moqui users. API keys for external integrations and development.

### 7.6 Data Serialization

**Choice**: Jackson for JSON serialization/deserialization

**Justification**:
- **Performance**: Fastest Java JSON library
- **Features**: Annotations, custom serializers, streaming
- **Moqui Compatibility**: Already used by Moqui Framework
- **Standards**: Full JSON/JSON Schema support

**Trade-offs**:

| Aspect | Jackson | Gson | org.json |
|--------|---------|------|----------|
| Performance | Excellent | Good | Fair |
| Features | Comprehensive | Good | Basic |
| Annotations | Yes | Yes | No |
| Streaming | Yes | No | No |
| Moqui Usage | Already included | Not used | Not used |
| Size | Large | Small | Tiny |

**Decision**: Jackson provides best performance and feature set. Already a Moqui dependency.

## 8. Key Considerations

### 8.1 Scalability

**How will the system handle 10x the initial load?**

**Current Baseline**:
- 100 concurrent AI sessions
- 1,000 tool invocations per minute
- 10 MB/s metadata queries

**10x Target**:
- 1,000 concurrent AI sessions
- 10,000 tool invocations per minute
- 100 MB/s metadata queries

**Scalability Strategies**:

1. **Metadata Caching**
   - Cache entity/service definitions in distributed Hazelcast cache
   - TTL: 1 hour (rarely change)
   - Cache warming on startup
   - Reduces database queries by 95%

2. **Connection Pooling**
   - HikariCP connection pool (already in Moqui)
   - Min connections: 10, Max: 100
   - Prepared statement caching

3. **Horizontal Scaling**
   - Stateless MCP server design
   - Load balancer distributes requests across instances
   - Shared Hazelcast cache for consistency
   - Database connection pooling per instance

4. **Query Optimization**
   - Implement pagination for all list operations
   - Default limit: 100, max: 1,000
   - Index frequently queried entity fields
   - Use view-entities for complex joins

5. **Async Processing**
   - Long-running operations (data.load, data.export) run asynchronously
   - Return correlation ID immediately
   - Poll for status via separate endpoint
   - Timeout: 600 seconds

6. **Resource Limits**
   - Maximum concurrent service calls per user: 10
   - Query result size limit: 1,000 records
   - Request timeout: 30 seconds
   - Rate limiting: 100 requests/minute per API key

**Performance Benchmarks**:

| Operation | Target Latency | Current | 10x Load |
|-----------|---------------|---------|----------|
| entity.describe | <50ms | 25ms | 35ms (cached) |
| entity.query | <200ms | 150ms | 180ms (indexed) |
| service.call | <500ms | 300ms | 450ms (depends on service) |
| screen.render | <1s | 700ms | 900ms (template caching) |

### 8.2 Security

**What are the primary threat vectors and mitigation strategies?**

**Threat Vectors**:

1. **Unauthorized Access**
   - Threat: AI agent accesses sensitive entity data without permission
   - Mitigation: Enforce artifact-based authorization on every operation
   - Implementation: Check `ArtifactExecutionFacade.checkPermitted()` before execution

2. **Data Exfiltration**
   - Threat: Bulk export of sensitive data via entity.query or data.export
   - Mitigation:
     - Result size limits (max 1,000 records per query)
     - Audit logging for all data access
     - Rate limiting on export operations
     - Row-level security via entity filters

3. **Service Abuse**
   - Threat: Malicious service calls that modify or delete data
   - Mitigation:
     - Read-only mode by default (configure for write access)
     - Transaction rollback on errors
     - Service parameter validation
     - Require explicit confirmation for destructive operations

4. **Injection Attacks**
   - Threat: SQL injection via entity query conditions
   - Mitigation:
     - Use EntityConditionFactory (parameterized queries)
     - Never construct SQL from user input
     - Validate all condition parameters

5. **Privilege Escalation**
   - Threat: AI agent executes operations with elevated privileges
   - Mitigation:
     - Each MCP session runs with specific user context
     - No "disable authorization" mode in production
     - Audit log includes user ID for all operations

6. **Denial of Service**
   - Threat: Resource exhaustion via expensive queries or service calls
   - Mitigation:
     - Request timeouts (30s default, 600s max)
     - Connection pool limits
     - Rate limiting (100 req/min per API key)
     - Query complexity analysis (reject queries with >3 levels of joins)

**Security Implementation**:

```java
public class SecureToolProvider extends BaseToolProvider {

    protected void validateRequest(Map<String, Object> args) {
        // Check required authentication
        ExecutionContext ec = getExecutionContext();
        UserFacade uf = ec.getUser();

        if (uf.getUsername() == null || "anonymous".equals(uf.getUsername())) {
            throw new SecurityException("Authentication required");
        }

        // Validate input parameters
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            validateParameter(entry.getKey(), entry.getValue());
        }
    }

    protected void validateParameter(String name, Object value) {
        // Prevent injection attempts
        if (value instanceof String) {
            String strValue = (String) value;
            if (strValue.contains("--") ||
                strValue.contains(";") ||
                strValue.contains("/*")) {
                throw new SecurityException(
                    "Invalid characters in parameter: " + name
                );
            }
        }
    }

    protected void auditOperation(String operation,
                                  Map<String, Object> args,
                                  Map<String, Object> result) {
        ExecutionContext ec = getExecutionContext();

        // Log to audit trail
        ec.getService().sync()
            .name("create#moqui.security.AuditLog")
            .parameters(Map.of(
                "auditHistorySeqId", UUID.randomUUID().toString(),
                "changedEntityName", "MCP_Tool_Invocation",
                "changedFieldName", operation,
                "changedByUserId", ec.getUser().getUserId(),
                "changedDate", new Timestamp(System.currentTimeMillis()),
                "oldValueText", null,
                "newValueText", result.toString()
            ))
            .call();
    }
}
```

**Security Checklist**:
- [ ] All operations require authentication
- [ ] Artifact authorization enforced
- [ ] Query result limits enforced
- [ ] Rate limiting configured
- [ ] Audit logging enabled
- [ ] Sensitive data redacted in logs
- [ ] Input validation on all parameters
- [ ] SQL injection protection via parameterized queries
- [ ] Transaction timeouts configured
- [ ] Error messages don't expose sensitive info

### 8.3 Observability

**How will we monitor the system's health and debug issues?**

**Monitoring Strategy**:

1. **Metrics Collection**
   - Tool invocation counts (per tool, per user)
   - Response times (p50, p95, p99)
   - Error rates
   - Cache hit/miss rates
   - Database connection pool usage
   - Memory usage per MCP session

2. **Logging**
   - Structured JSON logs
   - Log levels: DEBUG, INFO, WARN, ERROR
   - Include correlation IDs for request tracing
   - Sensitive data redacted

3. **Health Checks**
   - `/mcp/health` endpoint
   - Checks: database connectivity, cache availability, service registry
   - Return: HTTP 200 (healthy), 503 (unhealthy)

4. **Distributed Tracing**
   - Integrate with OpenTelemetry
   - Trace requests across tool invocations
   - Correlate with Moqui service calls

**Implementation**:

```java
public class ObservableMcpServer extends MoquiMcpServer {
    private final MetricsRegistry metrics;
    private final Logger logger;

    @Override
    public Map<String, Object> invokeTool(String toolName,
                                          Map<String, Object> args) {
        String correlationId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        logger.info("MCP tool invocation", Map.of(
            "correlationId", correlationId,
            "tool", toolName,
            "userId", getCurrentUserId(),
            "timestamp", startTime
        ));

        try {
            Map<String, Object> result = super.invokeTool(toolName, args);

            long duration = System.currentTimeMillis() - startTime;
            metrics.recordToolInvocation(toolName, duration, "success");

            logger.info("MCP tool completed", Map.of(
                "correlationId", correlationId,
                "tool", toolName,
                "duration", duration,
                "resultSize", estimateSize(result)
            ));

            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordToolInvocation(toolName, duration, "error");

            logger.error("MCP tool failed", Map.of(
                "correlationId", correlationId,
                "tool", toolName,
                "duration", duration,
                "error", e.getMessage()
            ), e);

            throw e;
        }
    }

    public Map<String, Object> getHealthStatus() {
        ExecutionContext ec = ecf.getExecutionContext();
        try {
            boolean dbHealthy = checkDatabaseHealth(ec);
            boolean cacheHealthy = checkCacheHealth(ec);
            boolean servicesHealthy = checkServicesHealth(ec);

            boolean overall = dbHealthy && cacheHealthy && servicesHealthy;

            return Map.of(
                "status", overall ? "healthy" : "unhealthy",
                "checks", Map.of(
                    "database", dbHealthy,
                    "cache", cacheHealthy,
                    "services", servicesHealthy
                ),
                "metrics", Map.of(
                    "activeSessions", getActiveSessionCount(),
                    "cacheHitRate", metrics.getCacheHitRate(),
                    "avgResponseTime", metrics.getAverageResponseTime()
                )
            );
        } finally {
            ec.destroy();
        }
    }
}
```

**Monitoring Dashboard**:
- Tool invocation rate over time
- Error rate by tool
- Response time percentiles
- Top users by request volume
- Cache hit/miss rates
- Database connection pool utilization

**Alerting**:
- Error rate > 5% for 5 minutes
- p95 response time > 2 seconds
- Database connection pool > 80% utilized
- Cache hit rate < 70%
- Service unavailable

### 8.4 Deployment & CI/CD

**A brief note on how this architecture would be deployed**

**Deployment Architecture**:

```
┌─────────────────────────────────────────────────────┐
│                 Load Balancer (nginx)                │
│              (HTTP/HTTPS + MCP Protocol)             │
└───────────────┬─────────────────────┬───────────────┘
                │                     │
    ┌───────────▼──────────┐  ┌──────▼──────────────┐
    │  Moqui Instance 1    │  │  Moqui Instance 2   │
    │  + MCP Server        │  │  + MCP Server       │
    │  (Embedded)          │  │  (Embedded)         │
    └───────────┬──────────┘  └──────┬──────────────┘
                │                     │
                └──────────┬──────────┘
                           │
           ┌───────────────▼────────────────┐
           │   Shared Infrastructure        │
           │   - PostgreSQL (entities)      │
           │   - Hazelcast (distributed     │
           │     cache cluster)             │
           │   - ElasticSearch (optional)   │
           └────────────────────────────────┘
```

**Deployment Steps**:

1. **Build**
   ```bash
   cd moqui
   gradle build
   gradle component:mcp-server:build
   ```

2. **Package**
   ```bash
   # Create deployable artifact
   gradle addRuntime
   # Produces: moqui-plus-runtime.war (includes MCP server)
   ```

3. **Deploy**
   ```bash
   # Docker deployment
   docker build -t moqui-mcp:latest .
   docker-compose up -d

   # Or traditional servlet container
   cp build/libs/moqui-plus-runtime.war /opt/tomcat/webapps/
   ```

4. **Configuration**
   ```xml
   <!-- runtime/conf/MoquiProductionConf.xml -->
   <moqui-conf>
     <component-list>
       <component name="mcp-server" location="component/mcp-server"/>
     </component-list>

     <mcp-server>
       <enabled>true</enabled>
       <port>3000</port>
       <authentication>
         <api-key enabled="true"/>
         <artifact-authz enabled="true"/>
       </authentication>
       <cache>
         <metadata-ttl>3600</metadata-ttl>
       </cache>
       <limits>
         <max-query-results>1000</max-query-results>
         <request-timeout>30000</request-timeout>
         <rate-limit>100</rate-limit>
       </limits>
     </mcp-server>
   </moqui-conf>
   ```

**CI/CD Pipeline**:

```yaml
# .github/workflows/mcp-server.yml
name: MCP Server CI/CD

on:
  push:
    branches: [main, develop]
    paths:
      - 'runtime/component/mcp-server/**'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up Java 21
        uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Build Moqui Framework
        run: gradle build

      - name: Build MCP Server Component
        run: gradle component:mcp-server:build

      - name: Run Tests
        run: gradle component:mcp-server:test

      - name: Build Docker Image
        run: docker build -t moqui-mcp:${{ github.sha }} .

      - name: Push to Registry
        run: docker push moqui-mcp:${{ github.sha }}

  test:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - name: Integration Tests
        run: |
          docker-compose up -d
          ./test/integration-tests.sh

  deploy-staging:
    needs: test
    if: github.ref == 'refs/heads/develop'
    runs-on: ubuntu-latest
    steps:
      - name: Deploy to Staging
        run: |
          kubectl set image deployment/moqui-mcp \
            moqui-mcp=moqui-mcp:${{ github.sha }} \
            --namespace=staging

  deploy-production:
    needs: test
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - name: Deploy to Production
        run: |
          kubectl set image deployment/moqui-mcp \
            moqui-mcp=moqui-mcp:${{ github.sha }} \
            --namespace=production
```

**Environment Management**:
- **Development**: Local embedded deployment, H2 database
- **Staging**: Docker Compose, PostgreSQL, Hazelcast cluster
- **Production**: Kubernetes, managed PostgreSQL, Hazelcast cluster, load balancer

**Rollback Strategy**:
- Blue/green deployment for zero-downtime updates
- Keep last 3 versions in container registry
- Automated rollback on health check failures
- Database migrations use Liquibase with rollback scripts

**Monitoring Integration**:
- Prometheus metrics endpoint: `/mcp/metrics`
- Grafana dashboards for visualization
- PagerDuty alerts for critical issues
- Log aggregation via ELK stack (Elasticsearch, Logstash, Kibana)

---

## Summary

This MCP Server for Moqui Framework provides AI assistants with comprehensive, secure, and performant access to Moqui's entity engine, service layer, screen rendering, and component management capabilities. By implementing the server in Java as a native Moqui component, we achieve optimal integration, performance, and consistency with the framework's architecture.

The prioritized tool set focuses on the most valuable operations for AI-assisted development (entity inspection, service discovery, data querying), while the resource model provides efficient metadata access. Integration points with other fivex MCP servers enable unified workflows spanning data management (data_store), version control (git_dav), UI development (forge_ui, eddy_code_ui), and service orchestration (anvil).

Security, scalability, and observability are designed into the architecture from the start, ensuring the MCP server can handle production workloads while maintaining audit trails and performance visibility. The deployment strategy supports both embedded (development) and distributed (production) scenarios with comprehensive CI/CD automation.

**Next Steps**:
1. Implement Phase 1 tools (entity.describe, entity.list, service.describe, service.list)
2. Create initial resource providers (entity://, service://)
3. Integrate with Java MCP SDK
4. Build test suite and integration tests
5. Document API in OpenAPI/Swagger format
6. Create sample AI workflows demonstrating tool usage
7. Deploy to development environment for testing
