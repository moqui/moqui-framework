# Moqui Framework 组件开发实战指导文档

## 目录

1. [组件结构概述](#组件结构概述)
2. [Screen 目录组织规范](#screen-目录组织规范)
3. [菜单配置最佳实践](#菜单配置最佳实践)
4. [列表页面实现模式](#列表页面实现模式)
5. [Tab页面实现方案](#tab页面实现方案)
6. [详情页与列表页关联](#详情页与列表页关联)
7. [表单设计模式](#表单设计模式)
8. [页面导航与路由](#页面导航与路由)
9. [组件最佳实践](#组件最佳实践)
10. [实际案例分析](#实际案例分析)

## 组件结构概述

### 标准组件目录结构

```
component/
├── MoquiConf.xml                 # 组件配置文件
├── data/                        # 初始数据
├── entity/                      # 实体定义
├── screen/                      # 屏幕定义（重点）
│   ├── apps/                   # 应用程序屏幕
│   │   └── [AppName]/          # 具体应用
│   │       ├── [Module]/       # 功能模块
│   │       └── dashboard.xml   # 仪表板
│   ├── includes/               # 可重用屏幕片段
│   └── webroot.xml            # Web根屏幕
├── service/                    # 服务定义
├── template/                   # 模板文件
└── webapp/                     # 静态资源
```

### 核心概念

- **Screen**: 页面的基本单位，使用XML定义
- **Subscreen**: 子屏幕，形成层次结构
- **Form**: 表单组件，支持查询和编辑
- **Section**: 页面区块，可条件显示

## Screen 目录组织规范

### 1. 应用级别组织

```xml
<!-- webroot.xml - 根屏幕配置 -->
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd">
    
    <subscreens default-item="dashboard">
        <subscreens-item name="apps" location="component://example/screen/apps"/>
        <subscreens-item name="dashboard" location="component://example/screen/dashboard.xml"/>
    </subscreens>
    
    <!-- 默认装饰器 -->
    <widgets>
        <subscreens-active/>
    </widgets>
</screen>
```

### 2. 模块级别组织

**HiveMind 项目管理模块示例：**

```
screen/apps/hmadmin/
├── Project/
│   ├── EditProject.xml          # 项目编辑页
│   ├── FindProject.xml          # 项目列表页  
│   ├── ProjectDetail.xml        # 项目详情页
│   └── ProjectSummary.xml       # 项目汇总页
├── Task/
│   ├── EditTask.xml             # 任务编辑页
│   ├── FindTask.xml             # 任务列表页
│   └── TaskDetail.xml           # 任务详情页
└── User/
    ├── EditUser.xml             # 用户编辑页
    └── FindUser.xml             # 用户列表页
```

## 菜单配置最佳实践

### 1. 自动菜单生成

Moqui 基于 subscreen 层次结构自动生成菜单：

```xml
<!-- apps.xml - 应用根屏幕 -->
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd">
    
    <subscreens default-item="dashboard">
        <!-- 每个 subscreens-item 自动成为菜单项 -->
        <subscreens-item name="Project" menu-title="项目管理" 
                        location="component://hivemind/screen/hmadmin/Project"/>
        <subscreens-item name="Task" menu-title="任务管理"
                        location="component://hivemind/screen/hmadmin/Task"/> 
        <subscreens-item name="User" menu-title="用户管理"
                        location="component://hivemind/screen/hmadmin/User"/>
        <subscreens-item name="Report" menu-title="报表中心"
                        location="component://hivemind/screen/hmadmin/Report"/>
    </subscreens>
    
    <!-- 菜单显示配置 -->
    <widgets>
        <container-box>
            <box-header title="系统管理"/>
            <box-body>
                <subscreens-panel id="apps-panel" type="tab"/>
            </box-body>
        </container-box>
    </widgets>
</screen>
```

### 2. 菜单权限控制

```xml
<!-- 带权限的菜单配置 -->
<subscreens-item name="Admin" menu-title="系统管理" 
                location="component://example/screen/admin"
                menu-include="Y">
    <!-- 权限检查 -->
    <condition>
        <if-service-permission service-name="ExamplePermissionServices.hasAdminPermission"/>
    </condition>
</subscreens-item>
```

### 3. 动态菜单

```xml
<!-- 基于数据的动态菜单 -->
<screen>
    <actions>
        <entity-find entity-name="ProjectCategory" list="categoryList">
            <order-by field-name="description"/>
        </entity-find>
    </actions>
    
    <subscreens>
        <!-- 静态菜单项 -->
        <subscreens-item name="all" menu-title="全部项目"/>
        
        <!-- 动态生成菜单项 -->
        <subscreens-item name="category" menu-title="按分类" 
                        menu-include="N">
            <subscreens>
                <subscreens-item name="${categoryId}" 
                               menu-title="${description}"
                               parameter-map="[categoryId:categoryId]"/>
            </subscreens>
        </subscreens-item>
    </subscreens>
</screen>
```

## 列表页面实现模式

### 1. 标准列表页模式

**FindProject.xml - 项目列表页示例：**

```xml
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd">

    <transition name="editProject">
        <default-response url="../EditProject"/>
    </transition>
    
    <transition name="createProject">
        <service-call name="hmadmin.ProjectServices.create#Project"/>
        <default-response url="../EditProject"/>
    </transition>

    <actions>
        <!-- 查询参数处理 -->
        <set field="queryString" from="projectName ?: ''"/>
        <set field="statusId" from="statusId ?: 'ProjectActive'"/>
    </actions>

    <widgets>
        <!-- 搜索表单区域 -->
        <container-row>
            <row-col lg="12">
                <form-single name="SearchForm" transition=".">
                    <field name="projectName">
                        <default-field title="项目名称">
                            <text-line/>
                        </default-field>
                    </field>
                    <field name="statusId">
                        <default-field title="状态">
                            <drop-down allow-empty="true">
                                <entity-options key="${statusId}" text="${description}">
                                    <entity-find entity-name="StatusItem">
                                        <econdition field-name="statusTypeId" value="Project"/>
                                    </entity-find>
                                </entity-options>
                            </drop-down>
                        </default-field>
                    </field>
                    <field name="submitButton">
                        <default-field title="搜索">
                            <submit/>
                        </default-field>
                    </field>
                </form-single>
            </row-col>
        </container-row>

        <!-- 操作按钮区域 -->
        <container-row>
            <row-col lg="12">
                <container style="text-align: right; margin: 10px 0;">
                    <link url="editProject" text="新增项目" btn-type="success"/>
                </container>
            </row-col>
        </container-row>

        <!-- 列表数据区域 -->
        <form-list name="ProjectList" list="projectList" skip-form="true">
            <entity-find entity-name="Project" list="projectList">
                <search-form-inputs default-order-by="projectName"/>
                <econdition field-name="projectName" operator="like" 
                           value="%${queryString}%" ignore-if-empty="true"/>
                <econdition field-name="statusId" ignore-if-empty="true"/>
            </entity-find>

            <field name="projectId">
                <default-field title="项目ID">
                    <display/>
                </default-field>
            </field>
            
            <field name="projectName">
                <default-field title="项目名称">
                    <link url="editProject" text="${projectName}" 
                          link-type="anchor"
                          parameter-map="[projectId:projectId]"/>
                </default-field>
            </field>
            
            <field name="statusId">
                <default-field title="状态">
                    <display-entity entity-name="StatusItem"/>
                </default-field>
            </field>
            
            <field name="estimatedStartDate">
                <default-field title="预计开始">
                    <display format="yyyy-MM-dd"/>
                </default-field>
            </field>

            <field name="actions">
                <default-field title="操作">
                    <link url="editProject" text="编辑" btn-type="info" btn-size="sm"
                          parameter-map="[projectId:projectId]"/>
                    <link url="projectDetail" text="详情" btn-type="primary" btn-size="sm"
                          parameter-map="[projectId:projectId]"/>
                </default-field>
            </field>
        </form-list>
    </widgets>
</screen>
```

### 2. 高级列表功能

**批量操作列表：**

```xml
<form-list name="ProjectListWithBatch" list="projectList">
    <entity-find entity-name="Project" list="projectList"/>
    
    <!-- 批量选择 -->
    <field name="projectId">
        <header-field title="全选">
            <check type="all"/>
        </header-field>
        <default-field title="">
            <check>
                <option key="${projectId}" text=""/>
            </check>
        </default-field>
    </field>
    
    <!-- 其他字段... -->
    
    <!-- 批量操作按钮 -->
    <form-list-column>
        <container>
            <button name="batchUpdate" text="批量更新状态"/>
            <button name="batchDelete" text="批量删除"/>
        </container>
    </form-list-column>
</form-list>
```

## Tab页面实现方案

### 1. 基本Tab页面结构

**ProjectDetail.xml - 项目详情Tab页：**

```xml
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd">

    <parameter name="projectId" required="true"/>
    
    <!-- 子Tab页面定义 -->
    <subscreens default-item="Summary">
        <subscreens-item name="Summary" menu-title="项目概况"/>
        <subscreens-item name="Tasks" menu-title="任务管理"/>  
        <subscreens-item name="Members" menu-title="团队成员"/>
        <subscreens-item name="Files" menu-title="项目文件"/>
        <subscreens-item name="Timeline" menu-title="时间轴"/>
    </subscreens>

    <actions>
        <!-- 加载项目基本信息 -->
        <entity-find-one entity-name="Project" value-field="project">
            <field-map field-name="projectId"/>
        </entity-find-one>
    </actions>

    <widgets>
        <!-- 项目头部信息 -->
        <container-row>
            <row-col lg="12">
                <container-box>
                    <box-header title="${project.projectName}"/>
                    <box-body>
                        <container-row>
                            <row-col lg="3">
                                <label text="状态: " type="strong"/>
                                <label text="${project.statusId}"/>
                            </row-col>
                            <row-col lg="3">
                                <label text="负责人: " type="strong"/>
                                <label text="${project.managerPartyId}"/>
                            </row-col>
                            <row-col lg="3">
                                <label text="开始日期: " type="strong"/>
                                <label text="${ec.l10n.format(project.estimatedStartDate, 'yyyy-MM-dd')}"/>
                            </row-col>
                            <row-col lg="3">
                                <label text="预计完成: " type="strong"/>
                                <label text="${ec.l10n.format(project.estimatedCompletionDate, 'yyyy-MM-dd')}"/>
                            </row-col>
                        </container-row>
                    </box-body>
                </container-box>
            </row-col>
        </container-row>

        <!-- Tab页面容器 -->
        <container-row>
            <row-col lg="12">
                <subscreens-panel id="project-tabs" type="tab"/>
            </row-col>
        </container-row>
    </widgets>
</screen>
```

### 2. Tab子页面实现

**Summary.xml - 项目概况Tab：**

```xml
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
    <parameter name="projectId" required="true"/>
    
    <actions>
        <!-- 统计数据查询 -->
        <entity-count entity-name="Task" count-field="totalTasks">
            <econdition field-name="projectId"/>
        </entity-count>
        
        <entity-count entity-name="Task" count-field="completedTasks">
            <econdition field-name="projectId"/>
            <econdition field-name="statusId" value="TaskCompleted"/>
        </entity-count>
    </actions>

    <widgets>
        <container-row>
            <!-- 统计卡片 -->
            <row-col lg="3">
                <container-box>
                    <box-header title="任务统计"/>
                    <box-body>
                        <label text="总任务数: ${totalTasks}" type="h4"/>
                        <label text="已完成: ${completedTasks}" type="h4"/>
                        <label text="完成率: ${completedTasks*100/totalTasks}%" type="h4"/>
                    </box-body>
                </container-box>
            </row-col>
            
            <!-- 更多统计信息... -->
        </container-row>

        <!-- 最近动态 -->
        <container-row>
            <row-col lg="12">
                <container-box>
                    <box-header title="最近动态"/>
                    <box-body>
                        <form-list name="RecentActivities" list="activityList">
                            <entity-find entity-name="ProjectActivity" list="activityList">
                                <econdition field-name="projectId"/>
                                <order-by field-name="-activityDate"/>
                                <limit count="10"/>
                            </entity-find>
                            
                            <!-- 活动列表字段定义... -->
                        </form-list>
                    </box-body>
                </container-box>
            </row-col>
        </container-row>
    </widgets>
</screen>
```

### 3. 动态Tab页面

```xml
<!-- 基于权限的动态Tab -->
<subscreens>
    <subscreens-item name="Summary" menu-title="概况"/>
    
    <!-- 条件显示的Tab -->
    <subscreens-item name="Financial" menu-title="财务信息" menu-include="N">
        <condition>
            <if-has-permission permission="PROJECT_FINANCIAL_VIEW"/>
        </condition>
    </subscreens-item>
    
    <!-- 基于项目类型的Tab -->
    <subscreens-item name="Development" menu-title="开发信息" menu-include="N">
        <condition>
            <expression>project.projectTypeId == 'ProjectSoftware'</expression>
        </condition>
    </subscreens-item>
</subscreens>
```

## 详情页与列表页关联

### 1. 标准关联模式

**列表页链接到详情页：**

```xml
<!-- FindProject.xml 中的链接设置 -->
<field name="projectName">
    <default-field title="项目名称">
        <link url="ProjectDetail" text="${projectName}" 
              link-type="anchor"
              parameter-map="[projectId:projectId]"/>
    </default-field>
</field>

<!-- 操作按钮 -->
<field name="actions">
    <default-field title="操作">
        <link url="EditProject" text="编辑" btn-type="info"
              parameter-map="[projectId:projectId]"/>
        <link url="ProjectDetail" text="详情" btn-type="primary"  
              parameter-map="[projectId:projectId]"/>
        <link url="deleteProject" text="删除" btn-type="danger"
              confirmation="确定要删除吗？"
              parameter-map="[projectId:projectId]"/>
    </default-field>
</field>
```

### 2. 面包屑导航

**ProjectDetail.xml 面包屑配置：**

```xml
<screen>
    <widgets>
        <!-- 面包屑导航 -->
        <container>
            <link url="../FindProject" text="项目列表"/>
            <label text=" > "/>
            <label text="${project.projectName}"/>
        </container>
        
        <!-- 返回按钮 -->
        <container style="text-align: right; margin: 10px 0;">
            <link url="../FindProject" text="返回列表" btn-type="default"/>
            <link url="../EditProject" text="编辑项目" btn-type="primary"
                  parameter-map="[projectId:projectId]"/>
        </container>
        
        <!-- 页面内容 -->
        <subscreens-active/>
    </widgets>
</screen>
```

### 3. 主从表关系

**项目-任务 主从关系：**

```xml
<!-- ProjectDetail/Tasks.xml -->
<screen>
    <parameter name="projectId" required="true"/>
    
    <transition name="editTask">
        <default-response url="../../Task/EditTask"/>
    </transition>
    
    <widgets>
        <!-- 新增任务按钮 -->
        <container>
            <link url="editTask" text="新增任务" btn-type="success"
                  parameter-map="[projectId:projectId]"/>
        </container>
        
        <!-- 项目下的任务列表 -->
        <form-list name="ProjectTaskList" list="taskList">
            <entity-find entity-name="Task" list="taskList">
                <econdition field-name="projectId"/>
                <order-by field-name="priority"/>
            </entity-find>
            
            <field name="taskName">
                <default-field title="任务名称">
                    <link url="../../Task/EditTask" text="${taskName}"
                          parameter-map="[taskId:taskId]"/>
                </default-field>
            </field>
            
            <!-- 其他字段... -->
        </form-list>
    </widgets>
</screen>
```

## 表单设计模式

### 1. 编辑表单

**EditProject.xml - 项目编辑表单：**

```xml
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
    <parameter name="projectId"/>
    
    <transition name="updateProject">
        <service-call name="update#Project"/>
        <default-response url="../ProjectDetail" parameter-map="[projectId:projectId]"/>
    </transition>
    
    <transition name="createProject">
        <service-call name="create#Project"/>
        <default-response url="../ProjectDetail" parameter-map="[projectId:projectId]"/>
    </transition>

    <actions>
        <!-- 加载数据 -->
        <entity-find-one entity-name="Project" value-field="project" 
                        for-update="true">
            <field-map field-name="projectId"/>
        </entity-find-one>
    </actions>

    <widgets>
        <form-single name="EditProjectForm" 
                    transition="${projectId ? 'updateProject' : 'createProject'}"
                    map="project">
            
            <!-- 隐藏字段 -->
            <field name="projectId">
                <default-field>
                    <hidden/>
                </default-field>
            </field>
            
            <!-- 基本信息 -->
            <field name="projectName">
                <default-field title="项目名称">
                    <text-line size="40"/>
                </default-field>
            </field>
            
            <field name="description">
                <default-field title="项目描述">
                    <text-area rows="4" cols="60"/>
                </default-field>
            </field>
            
            <!-- 下拉选择 -->
            <field name="statusId">
                <default-field title="项目状态">
                    <drop-down>
                        <entity-options key="${statusId}" text="${description}">
                            <entity-find entity-name="StatusItem">
                                <econdition field-name="statusTypeId" value="Project"/>
                            </entity-find>
                        </entity-options>
                    </drop-down>
                </default-field>
            </field>
            
            <!-- 日期选择 -->
            <field name="estimatedStartDate">
                <default-field title="预计开始日期">
                    <date-time type="date"/>
                </default-field>
            </field>
            
            <!-- 人员选择 -->
            <field name="managerPartyId">
                <default-field title="项目经理">
                    <drop-down>
                        <entity-options key="${partyId}" text="${firstName} ${lastName}">
                            <entity-find entity-name="PersonAndPartyDetail">
                                <econdition field-name="roleTypeId" value="Manager"/>
                            </entity-find>
                        </entity-options>
                    </drop-down>
                </default-field>
            </field>
            
            <!-- 提交按钮 -->
            <field name="submitButton">
                <default-field title="">
                    <submit text="${projectId ? '更新' : '创建'}"/>
                </default-field>
            </field>
            
            <!-- 取消按钮 -->
            <field name="cancelButton">
                <default-field title="">
                    <link url="../FindProject" text="取消" btn-type="default"/>
                </default-field>
            </field>
        </form-single>
    </widgets>
</screen>
```

### 2. 复杂表单布局

```xml
<!-- 多列布局表单 -->
<form-single name="ComplexForm" map="project">
    <field-layout>
        <!-- 第一行：两列布局 -->
        <field-row>
            <field-ref name="projectName"/>
            <field-ref name="statusId"/>
        </field-row>
        
        <!-- 第二行：三列布局 -->
        <field-row>
            <field-ref name="estimatedStartDate"/>
            <field-ref name="estimatedCompletionDate"/>
            <field-ref name="managerPartyId"/>
        </field-row>
        
        <!-- 第三行：单列布局 -->
        <field-row>
            <field-ref name="description"/>
        </field-row>
    </field-layout>
    
    <!-- 字段定义保持不变 -->
</form-single>
```

### 3. 条件表单字段

```xml
<!-- 基于条件显示的表单字段 -->
<field name="budgetAmount">
    <conditional-field condition="project.projectTypeId == 'ProjectCommercial'">
        <default-field title="项目预算">
            <text-line size="20" format="#,##0.00"/>
        </default-field>
    </conditional-field>
</field>

<!-- 动态验证 -->
<field name="estimatedCompletionDate">
    <default-field title="预计完成日期">
        <date-time type="date"/>
        <field-validation>
            <compare operator="greater-equals" to-field="estimatedStartDate"/>
        </field-validation>
    </default-field>
</field>
```

## 页面导航与路由

### 1. URL 路由规则

```
基础URL模式: /apps/[AppName]/[Module]/[Screen]

实际例子：
- /apps/hivemind/Project/FindProject    # 项目列表
- /apps/hivemind/Project/EditProject    # 项目编辑
- /apps/hivemind/Project/ProjectDetail  # 项目详情
- /apps/hivemind/Task/FindTask          # 任务列表
```

### 2. 参数传递

```xml
<!-- URL参数传递 -->
<link url="EditProject" text="编辑" 
      parameter-map="[projectId:projectId, mode:'edit']"/>

<!-- 表单参数传递 -->
<transition name="saveAndContinue">
    <actions>
        <service-call name="update#Project"/>
        <set field="successMessage" value="保存成功"/>
    </actions>
    <default-response url="." save-parameters="true"/>
</transition>
```

### 3. 重定向控制

```xml
<!-- 条件重定向 -->
<transition name="processProject">
    <actions>
        <service-call name="ProjectServices.process#Project"/>
        <if condition="project.statusId == 'ProjectCompleted'">
            <then>
                <set field="redirectUrl" value="../ProjectSummary"/>
            </then>
            <else>
                <set field="redirectUrl" value="."/>
            </else>
        </if>
    </actions>
    <default-response url="${redirectUrl}"/>
</transition>
```

## 组件最佳实践

### 1. 代码组织原则

- **模块化**: 按业务功能组织screen目录
- **可重用**: 公共组件放在 includes 目录
- **命名规范**: 使用统一的命名约定
- **层次清晰**: 保持合理的 subscreen 层次

### 2. 性能优化

```xml
<!-- 数据库查询优化 -->
<entity-find entity-name="Project" list="projectList" cache="true">
    <select-field field-name="projectId,projectName,statusId"/>
    <order-by field-name="projectName"/>
    <limit count="50"/>
</entity-find>

<!-- 条件加载 -->
<section name="ProjectDetails" condition="projectId">
    <widgets>
        <include-screen location="component://example/screen/includes/ProjectInfo.xml"/>
    </widgets>
</section>
```

### 3. 错误处理

```xml
<!-- 数据验证 -->
<actions>
    <entity-find-one entity-name="Project" value-field="project">
        <field-map field-name="projectId"/>
    </entity-find-one>
    
    <if condition="!project">
        <then>
            <message error="true">项目不存在 (ID: ${projectId})</message>
            <script>ec.web.sendRedirect("/apps/hivemind/Project/FindProject")</script>
        </then>
    </if>
</actions>
```

## 实际案例分析

### Example 组件结构

```
example/screen/
├── ExampleApp/
│   ├── Example/
│   │   ├── EditExample.xml          # 示例编辑页
│   │   ├── FindExample.xml          # 示例列表页
│   │   └── ExampleDetail.xml        # 示例详情页
│   ├── ExampleFeature/
│   │   ├── EditFeature.xml          # 功能编辑页
│   │   └── FindFeature.xml          # 功能列表页
│   └── dashboard.xml                # 应用仪表板
├── includes/
│   ├── Header.xml                   # 公共头部
│   ├── Footer.xml                   # 公共底部
│   └── CommonForms.xml              # 公共表单
└── webroot.xml                      # Web根配置
```

### HiveMind 组件结构分析

```
hivemind/screen/
├── hmadmin/                         # 管理后台
│   ├── Project/
│   │   ├── FindProject.xml          # 项目列表 - 标准列表页模式
│   │   ├── EditProject.xml          # 项目编辑 - 标准编辑表单
│   │   ├── ProjectDetail.xml        # 项目详情 - Tab页面容器
│   │   ├── Summary.xml              # 项目概况Tab
│   │   ├── Tasks.xml                # 项目任务Tab - 主从关系
│   │   ├── Members.xml              # 团队成员Tab
│   │   ├── Milestones.xml           # 项目里程碑Tab
│   │   └── Reports.xml              # 项目报表Tab
│   ├── Task/
│   │   ├── FindTask.xml             # 任务列表
│   │   ├── EditTask.xml             # 任务编辑
│   │   ├── TaskDetail.xml           # 任务详情
│   │   └── MyTasks.xml              # 我的任务 - 个性化视图
│   ├── User/
│   │   ├── FindUser.xml             # 用户管理列表
│   │   ├── EditUser.xml             # 用户编辑
│   │   └── UserDetail.xml           # 用户详情
│   ├── Request/
│   │   ├── FindRequest.xml          # 需求列表
│   │   ├── EditRequest.xml          # 需求编辑
│   │   └── RequestDetail.xml        # 需求详情
│   └── dashboard.xml                # 管理仪表板
├── hm/                              # 用户前台
│   ├── MyProjects.xml               # 我的项目
│   ├── MyTasks.xml                  # 我的任务
│   ├── MyRequests.xml               # 我的需求
│   └── dashboard.xml                # 用户仪表板
└── includes/
    ├── ProjectSummaryInfo.xml       # 项目摘要信息组件
    ├── TaskTimeTracking.xml         # 任务时间跟踪组件
    └── UserActivityLog.xml          # 用户活动日志组件
```

## 深度实战案例

### 案例1：项目管理完整流程

#### 1. 项目列表页 (FindProject.xml)

```xml
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd">

    <!-- 页面转换定义 -->
    <transition name="editProject">
        <default-response url="EditProject"/>
    </transition>
    
    <transition name="projectDetail">  
        <default-response url="ProjectDetail"/>
    </transition>
    
    <transition name="deleteProject">
        <service-call name="hmadmin.ProjectServices.delete#Project"/>
        <default-response url="."/>
    </transition>
    
    <transition name="batchUpdateStatus">
        <service-call name="hmadmin.ProjectServices.batchUpdate#ProjectStatus"/>
        <default-response url="."/>
    </transition>

    <!-- 页面逻辑 -->
    <actions>
        <!-- 当前用户权限检查 -->
        <set field="canCreateProject" from="ec.user.hasPermission('PROJECT_CREATE')"/>
        <set field="canDeleteProject" from="ec.user.hasPermission('PROJECT_DELETE')"/>
        
        <!-- 搜索参数处理 -->
        <set field="queryString" from="projectName ?: ''"/>
        <set field="statusId" from="statusId ?: ''"/>
        <set field="managerPartyId" from="managerPartyId ?: ''"/>
        
        <!-- 状态选项加载 -->
        <entity-find entity-name="moqui.basic.StatusItem" list="statusList">
            <econdition field-name="statusTypeId" value="Project"/>
            <order-by field-name="sequenceNum"/>
        </entity-find>
    </actions>

    <widgets>
        <container-box>
            <box-header title="项目管理" icon="fa fa-project-diagram"/>
            <box-toolbar>
                <!-- 权限控制的新增按钮 -->
                <container-dialog id="CreateProjectDialog" button-text="新增项目" 
                                condition="canCreateProject">
                    <form-single name="QuickCreateProject" transition="editProject">
                        <field name="projectName">
                            <default-field title="项目名称">
                                <text-line size="30" required="true"/>
                            </default-field>
                        </field>
                        <field name="description">
                            <default-field title="项目描述">
                                <text-area rows="3" cols="40"/>
                            </default-field>
                        </field>
                        <field name="submitButton">
                            <default-field title="">
                                <submit text="创建项目"/>
                            </default-field>
                        </field>
                    </form-single>
                </container-dialog>
                
                <!-- 导出功能 -->
                <link url="ExportProjects" text="导出Excel" btn-type="info"/>
            </box-toolbar>
            
            <box-body>
                <!-- 高级搜索表单 -->
                <form-single name="SearchForm" transition="." extends="component://webroot/screen/webroot/apps/common/includes/SearchFormBase.xml">
                    <field name="projectName">
                        <default-field title="项目名称">
                            <text-line size="20" ac-transition="suggestProjectNames"/>
                        </default-field>
                    </field>
                    
                    <field name="statusId">
                        <default-field title="项目状态">
                            <drop-down allow-empty="true">
                                <list-options list="statusList" key="${statusId}" text="${description}"/>
                            </drop-down>
                        </default-field>
                    </field>
                    
                    <field name="managerPartyId">
                        <default-field title="项目经理">
                            <drop-down allow-empty="true">
                                <entity-options key="${partyId}" text="${firstName} ${lastName}">
                                    <entity-find entity-name="mantle.party.PersonWithUserAccount">
                                        <econdition field-name="disabled" value="N"/>
                                        <order-by field-name="firstName"/>
                                    </entity-find>
                                </entity-options>
                            </drop-down>
                        </default-field>
                    </field>
                    
                    <field name="dateRange">
                        <default-field title="创建时间">
                            <date-period/>
                        </default-field>
                    </field>
                    
                    <field name="submitButton">
                        <default-field title="">
                            <submit text="搜索" icon="fa fa-search"/>
                        </default-field>
                    </field>
                    
                    <field name="resetButton">
                        <default-field title="">
                            <link url="." text="重置" btn-type="default"/>
                        </default-field>
                    </field>
                </form-single>

                <!-- 项目列表 -->
                <form-list name="ProjectList" list="projectList" 
                          skip-form="true" select-columns="true" saved-finds="true">
                    
                    <!-- 数据查询 -->
                    <entity-find entity-name="hmadmin.ProjectDetailView" list="projectList">
                        <search-form-inputs default-order-by="-createdDate"/>
                        
                        <!-- 搜索条件 -->
                        <econditions combine="and">
                            <econdition field-name="projectName" operator="like" 
                                       value="%${queryString}%" ignore-if-empty="true"/>
                            <econdition field-name="statusId" ignore-if-empty="true"/>
                            <econdition field-name="managerPartyId" ignore-if-empty="true"/>
                            <date-filter from-field-name="fromDate" thru-field-name="thruDate"/>
                        </econditions>
                        
                        <!-- 分页 -->
                        <select-field field-name="projectId,projectName,description,statusId"/>
                        <select-field field-name="managerPartyId,estimatedStartDate,estimatedCompletionDate"/>
                        <select-field field-name="createdDate,priority,completedTaskCount,totalTaskCount"/>
                    </entity-find>

                    <!-- 批量选择 -->
                    <field name="projectId">
                        <header-field title="选择" show-order-by="false">
                            <check type="all-checkbox"/>
                        </header-field>
                        <default-field>
                            <check all-checked="false">
                                <option key="${projectId}" text=""/>
                            </check>
                        </default-field>
                    </field>

                    <!-- 项目名称 - 链接到详情页 -->
                    <field name="projectName">
                        <header-field title="项目名称" show-order-by="true"/>
                        <default-field>
                            <link url="projectDetail" text="${projectName}" 
                                  link-type="anchor" parameter-map="[projectId:projectId]"
                                  tooltip="${description}"/>
                        </default-field>
                    </field>

                    <!-- 项目状态 - 带颜色标识 -->
                    <field name="statusId">
                        <header-field title="状态" show-order-by="true"/>
                        <default-field>
                            <display text="${statusDescription}" 
                                    encode="false" also-hidden="false">
                                <depends-on field="statusId"/>
                            </display>
                        </default-field>
                    </field>

                    <!-- 项目经理 -->
                    <field name="managerName">
                        <header-field title="项目经理" show-order-by="true"/>
                        <default-field>
                            <link url="../User/UserDetail" text="${managerName}"
                                  parameter-map="[partyId:managerPartyId]"/>
                        </default-field>
                    </field>

                    <!-- 进度显示 -->
                    <field name="progress">
                        <header-field title="进度"/>
                        <default-field>
                            <render-mode>
                                <text type="html" location="component://hivemind/template/ProjectProgressBar.ftl"/>
                            </render-mode>
                        </default-field>
                    </field>

                    <!-- 预计完成日期 -->
                    <field name="estimatedCompletionDate">
                        <header-field title="预计完成" show-order-by="true"/>
                        <default-field>
                            <display format="yyyy-MM-dd" style="${groovy:
                                def today = ec.user.nowTimestamp
                                def estDate = estimatedCompletionDate
                                if (estDate && estDate < today) return 'text-danger'
                                else if (estDate && estDate < (today + 7)) return 'text-warning'
                                else return ''
                            }"/>
                        </default-field>
                    </field>

                    <!-- 操作列 -->
                    <field name="actions">
                        <header-field title="操作"/>
                        <default-field>
                            <container>
                                <!-- 查看详情 -->
                                <link url="projectDetail" text="详情" btn-type="info" btn-size="xs"
                                      parameter-map="[projectId:projectId]" icon="fa fa-eye"/>
                                      
                                <!-- 编辑 -->
                                <link url="editProject" text="编辑" btn-type="primary" btn-size="xs"
                                      parameter-map="[projectId:projectId]" icon="fa fa-edit"
                                      condition="ec.user.hasPermission('PROJECT_UPDATE')"/>
                                      
                                <!-- 删除 -->
                                <link url="deleteProject" text="删除" btn-type="danger" btn-size="xs"
                                      parameter-map="[projectId:projectId]" icon="fa fa-trash"
                                      confirmation="确定要删除项目 '${projectName}' 吗？"
                                      condition="canDeleteProject"/>
                            </container>
                        </default-field>
                    </field>

                    <!-- 批量操作区域 -->
                    <field name="batchOperations">
                        <header-field>
                            <container>
                                <label text="批量操作:" type="strong"/>
                                <form-single name="BatchOperationForm" transition="batchUpdateStatus">
                                    <field name="projectIds">
                                        <default-field>
                                            <hidden/>
                                        </default-field>
                                    </field>
                                    <field name="newStatusId">
                                        <default-field title="">
                                            <drop-down>
                                                <list-options list="statusList" key="${statusId}" text="${description}"/>
                                            </drop-down>
                                        </default-field>
                                    </field>
                                    <field name="batchUpdate">
                                        <default-field title="">
                                            <submit text="批量更新状态" btn-type="warning"/>
                                        </default-field>
                                    </field>
                                </form-single>
                            </container>
                        </header-field>
                    </field>
                </form-list>
            </box-body>
        </container-box>

        <!-- 页面脚本 -->
        <render-mode>
            <text type="html"><![CDATA[
                <script>
                $(document).ready(function() {
                    // 全选功能
                    $('#ProjectList_selectAll').change(function() {
                        $('input[name="projectIds"]').prop('checked', this.checked);
                    });
                    
                    // 批量操作前的选择验证
                    $('#BatchOperationForm_batchUpdate').click(function(e) {
                        if ($('input[name="projectIds"]:checked').length === 0) {
                            alert('请至少选择一个项目');
                            e.preventDefault();
                        }
                    });
                });
                </script>
            ]]></text>
        </render-mode>
    </widgets>
</screen>
```

#### 2. 项目详情Tab页面 (ProjectDetail.xml)

```xml
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd">

    <parameter name="projectId" required="true"/>
    
    <!-- Tab页面定义 -->
    <subscreens default-item="Summary">
        <subscreens-item name="Summary" menu-title="项目概况" menu-index="1"/>
        <subscreens-item name="Tasks" menu-title="任务管理" menu-index="2"/>
        <subscreens-item name="Members" menu-title="团队成员" menu-index="3"/>
        <subscreens-item name="Timeline" menu-title="项目时间轴" menu-index="4"/>
        <subscreens-item name="Documents" menu-title="项目文档" menu-index="5"/>
        <subscreens-item name="Financial" menu-title="财务信息" menu-index="6" menu-include="N">
            <condition>
                <if-has-permission permission="PROJECT_FINANCIAL_VIEW"/>
            </condition>
        </subscreens-item>
    </subscreens>
    
    <!-- 页面转换 -->
    <transition name="updateProject">
        <service-call name="hmadmin.ProjectServices.update#Project"/>
        <default-response url="."/>
    </transition>
    
    <transition name="updateProjectStatus">
        <service-call name="hmadmin.ProjectServices.updateStatus#Project"/>
        <default-response url="."/>
    </transition>

    <!-- 页面数据加载 -->
    <actions>
        <!-- 加载项目详细信息 -->
        <entity-find-one entity-name="hmadmin.ProjectDetailView" value-field="project">
            <field-map field-name="projectId"/>
        </entity-find-one>
        
        <!-- 检查项目是否存在 -->
        <if condition="!project">
            <then>
                <message error="true">项目不存在 (ID: ${projectId})</message>
                <script>ec.web.sendRedirect('/apps/hivemind/Project/FindProject')</script>
                <return/>
            </then>
        </if>
        
        <!-- 权限检查 -->
        <set field="canEditProject" from="ec.user.hasPermission('PROJECT_UPDATE') || 
                                         project.managerPartyId == ec.user.userAccount?.partyId"/>
        <set field="canViewFinancial" from="ec.user.hasPermission('PROJECT_FINANCIAL_VIEW')"/>
        
        <!-- 计算项目统计数据 -->
        <service-call name="hmadmin.ProjectServices.get#ProjectStats" out-map="projectStats">
            <field-map field-name="projectId"/>
        </service-call>
        
        <!-- 项目状态选项 -->
        <entity-find entity-name="moqui.basic.StatusItem" list="statusList">
            <econdition field-name="statusTypeId" value="Project"/>
            <order-by field-name="sequenceNum"/>
        </entity-find>
    </actions>

    <widgets>
        <!-- 面包屑导航 -->
        <container-row>
            <row-col lg="12">
                <container class="breadcrumb-container">
                    <link url="../FindProject" text="项目列表"/>
                    <label text=" > " encode="false"/>
                    <label text="${project.projectName}" type="span" style="font-weight: bold;"/>
                </container>
            </row-col>
        </container-row>

        <!-- 项目头部信息卡片 -->
        <container-row>
            <row-col lg="12">
                <container-box>
                    <box-header title="${project.projectName}" icon="fa fa-project-diagram">
                        <!-- 项目状态徽章 -->
                        <container-dialog id="StatusUpdateDialog" button-text="更改状态" 
                                        condition="canEditProject" btn-type="primary" btn-size="sm">
                            <form-single name="UpdateStatusForm" transition="updateProjectStatus">
                                <field name="projectId">
                                    <default-field><hidden default="${projectId}"/></default-field>
                                </field>
                                <field name="statusId">
                                    <default-field title="新状态">
                                        <drop-down>
                                            <list-options list="statusList" key="${statusId}" text="${description}"/>
                                        </drop-down>
                                    </default-field>
                                </field>
                                <field name="statusChangeReason">
                                    <default-field title="变更原因">
                                        <text-area rows="3" cols="40"/>
                                    </default-field>
                                </field>
                                <field name="submitButton">
                                    <default-field title="">
                                        <submit text="更改状态"/>
                                    </default-field>
                                </field>
                            </form-single>
                        </container-dialog>
                    </box-header>
                    
                    <box-body>
                        <!-- 项目基本信息网格 -->
                        <container-row>
                            <!-- 左侧：基本信息 -->
                            <row-col lg="8">
                                <container-row>
                                    <row-col md="6">
                                        <label text="项目状态：" type="strong"/>
                                        <label text="${project.statusDescription}" 
                                              style="color: ${groovy: 
                                                  project.statusId == 'ProjectActive' ? 'green' : 
                                                  project.statusId == 'ProjectCompleted' ? 'blue' :
                                                  project.statusId == 'ProjectCancelled' ? 'red' : 'orange'
                                              }"/>
                                    </row-col>
                                    <row-col md="6">
                                        <label text="项目经理：" type="strong"/>
                                        <link url="../User/UserDetail" text="${project.managerName}"
                                              parameter-map="[partyId:project.managerPartyId]"/>
                                    </row-col>
                                </container-row>
                                
                                <container-row>
                                    <row-col md="6">
                                        <label text="开始日期：" type="strong"/>
                                        <label text="${ec.l10n.format(project.estimatedStartDate, 'yyyy-MM-dd')}"/>
                                    </row-col>
                                    <row-col md="6">
                                        <label text="预计完成：" type="strong"/>
                                        <label text="${ec.l10n.format(project.estimatedCompletionDate, 'yyyy-MM-dd')}"/>
                                    </row-col>
                                </container-row>
                                
                                <container-row>
                                    <row-col md="12">
                                        <label text="项目描述：" type="strong"/>
                                        <label text="${project.description ?: '无描述'}"/>
                                    </row-col>
                                </container-row>
                            </row-col>
                            
                            <!-- 右侧：统计信息 -->
                            <row-col lg="4">
                                <container-box type="info">
                                    <box-header title="项目统计"/>
                                    <box-body>
                                        <container-row>
                                            <row-col xs="6">
                                                <label text="总任务数" type="small"/>
                                                <label text="${projectStats.totalTasks ?: 0}" type="h4"/>
                                            </row-col>
                                            <row-col xs="6">
                                                <label text="已完成" type="small"/>
                                                <label text="${projectStats.completedTasks ?: 0}" type="h4"/>
                                            </row-col>
                                        </container-row>
                                        
                                        <container-row>
                                            <row-col xs="6">
                                                <label text="团队成员" type="small"/>
                                                <label text="${projectStats.memberCount ?: 0}" type="h4"/>
                                            </row-col>
                                            <row-col xs="6">
                                                <label text="完成率" type="small"/>
                                                <label text="${projectStats.completionPercentage ?: 0}%" type="h4"/>
                                            </row-col>
                                        </container-row>
                                        
                                        <!-- 进度条 -->
                                        <container-row>
                                            <row-col xs="12">
                                                <render-mode>
                                                    <text type="html"><![CDATA[
                                                        <div class="progress" style="margin-top: 10px;">
                                                            <div class="progress-bar progress-bar-info" 
                                                                 style="width: ${projectStats.completionPercentage ?: 0}%">
                                                                ${projectStats.completionPercentage ?: 0}%
                                                            </div>
                                                        </div>
                                                    ]]></text>
                                                </render-mode>
                                            </row-col>
                                        </container-row>
                                    </box-body>
                                </container-box>
                            </row-col>
                        </container-row>
                    </box-body>
                    
                    <!-- 操作按钮区域 -->
                    <box-toolbar condition="canEditProject">
                        <link url="../EditProject" text="编辑项目" btn-type="primary"
                              parameter-map="[projectId:projectId]" icon="fa fa-edit"/>
                        <link url="ExportProjectReport" text="导出报告" btn-type="info"
                              parameter-map="[projectId:projectId]" icon="fa fa-download"/>
                    </box-toolbar>
                </container-box>
            </row-col>
        </container-row>

        <!-- Tab页面容器 -->
        <container-row>
            <row-col lg="12">
                <subscreens-panel id="project-detail-tabs" type="tab" 
                                dynamic-active="true" active-sub-menu="Summary"/>
            </row-col>
        </container-row>
        
        <!-- 返回按钮 -->
        <container-row>
            <row-col lg="12" style="text-align: center; margin-top: 20px;">
                <link url="../FindProject" text="返回项目列表" btn-type="default" 
                      icon="fa fa-arrow-left"/>
            </row-col>
        </container-row>
    </widgets>
</screen>
```

### 案例2：复杂表单实现

#### EditProject.xml - 高级项目编辑表单

```xml
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd">

    <parameter name="projectId"/>
    
    <!-- 表单转换定义 -->
    <transition name="saveProject">
        <actions>
            <if condition="projectId">
                <then>
                    <service-call name="hmadmin.ProjectServices.update#Project" in-map="true"/>
                    <set field="successMessage" value="项目更新成功"/>
                </then>
                <else>
                    <service-call name="hmadmin.ProjectServices.create#Project" in-map="true" out-map="context"/>
                    <set field="successMessage" value="项目创建成功"/>
                    <set field="projectId" from="context.projectId"/>
                </else>
            </if>
        </actions>
        <default-response url="../ProjectDetail" parameter-map="[projectId:projectId]"/>
    </transition>
    
    <transition name="validateProjectName">
        <service-call name="hmadmin.ProjectServices.validate#ProjectName"/>
        <default-response type="json-object"/>
    </transition>

    <!-- 数据加载和验证 -->
    <actions>
        <!-- 加载项目数据 -->
        <if condition="projectId">
            <then>
                <entity-find-one entity-name="hmadmin.Project" value-field="project" for-update="true">
                    <field-map field-name="projectId"/>
                </entity-find-one>
                <if condition="!project">
                    <then>
                        <message error="true">项目不存在</message>
                        <script>ec.web.sendRedirect('/apps/hivemind/Project/FindProject')</script>
                        <return/>
                    </then>
                </if>
                <set field="pageTitle" value="编辑项目 - ${project.projectName}"/>
            </then>
            <else>
                <set field="pageTitle" value="新增项目"/>
                <!-- 设置默认值 -->
                <set field="project" from="[:]"/>
                <set field="project.statusId" value="ProjectPlanning"/>
                <set field="project.priority" value="5"/>
                <set field="project.estimatedStartDate" from="ec.user.nowTimestamp"/>
            </else>
        </if>
        
        <!-- 加载相关选项数据 -->
        <entity-find entity-name="moqui.basic.StatusItem" list="projectStatusList">
            <econdition field-name="statusTypeId" value="Project"/>
            <order-by field-name="sequenceNum"/>
        </entity-find>
        
        <entity-find entity-name="hmadmin.ProjectType" list="projectTypeList">
            <order-by field-name="description"/>
        </entity-find>
        
        <entity-find entity-name="mantle.party.PersonWithUserAccount" list="managerList">
            <econdition field-name="disabled" value="N"/>
            <econdition field-name="roleTypeId" value="Manager"/>
            <order-by field-name="firstName"/>
        </entity-find>
        
        <entity-find entity-name="mantle.party.Organization" list="clientOrgList">
            <econdition field-name="disabled" value="N"/>
            <order-by field-name="organizationName"/>
        </entity-find>
    </actions>

    <widgets>
        <!-- 页面标题 -->
        <container-row>
            <row-col lg="12">
                <label text="${pageTitle}" type="h2"/>
            </row-col>
        </container-row>

        <!-- 主表单 -->
        <container-row>
            <row-col lg="12">
                <container-box>
                    <box-header title="项目信息"/>
                    <box-body>
                        <form-single name="EditProjectForm" transition="saveProject" 
                                    map="project" focus-field="projectName">
                            
                            <!-- 隐藏字段 -->
                            <field name="projectId">
                                <default-field><hidden/></default-field>
                            </field>
                            
                            <!-- 表单布局定义 -->
                            <field-layout>
                                <!-- 第一行：项目基本信息 -->
                                <field-row>
                                    <field-ref name="projectName"/>
                                    <field-ref name="projectTypeId"/>
                                </field-row>
                                
                                <!-- 第二行：状态和优先级 -->
                                <field-row>
                                    <field-ref name="statusId"/>
                                    <field-ref name="priority"/>
                                </field-row>
                                
                                <!-- 第三行：项目经理和客户 -->
                                <field-row>
                                    <field-ref name="managerPartyId"/>
                                    <field-ref name="clientPartyId"/>
                                </field-row>
                                
                                <!-- 第四行：时间规划 -->
                                <field-row>
                                    <field-ref name="estimatedStartDate"/>
                                    <field-ref name="estimatedCompletionDate"/>
                                </field-row>
                                
                                <!-- 第五行：预算信息 -->
                                <field-row>
                                    <field-ref name="estimatedWorkHours"/>
                                    <field-ref name="budgetAmount"/>
                                </field-row>
                                
                                <!-- 第六行：描述 -->
                                <field-row>
                                    <field-ref name="description"/>
                                </field-row>
                                
                                <!-- 按钮行 -->
                                <field-row>
                                    <field-ref name="submitButton"/>
                                    <field-ref name="cancelButton"/>
                                </field-row>
                            </field-layout>

                            <!-- 项目名称 - 带实时验证 -->
                            <field name="projectName">
                                <default-field title="项目名称" tooltip="项目的唯一标识名称">
                                    <text-line size="40" maxlength="100" required="true"
                                              ac-transition="validateProjectName"
                                              ac-min-length="2"/>
                                </default-field>
                            </field>

                            <!-- 项目类型 -->
                            <field name="projectTypeId">
                                <default-field title="项目类型">
                                    <drop-down required="true">
                                        <option key="" text="请选择项目类型"/>
                                        <list-options list="projectTypeList" 
                                                     key="${projectTypeId}" text="${description}"/>
                                    </drop-down>
                                </default-field>
                            </field>

                            <!-- 项目状态 -->
                            <field name="statusId">
                                <default-field title="项目状态">
                                    <drop-down required="true">
                                        <list-options list="projectStatusList" 
                                                     key="${statusId}" text="${description}"/>
                                    </drop-down>
                                </default-field>
                            </field>

                            <!-- 优先级 - 滑块控件 -->
                            <field name="priority">
                                <default-field title="优先级 (1-10)">
                                    <range-find min="1" max="10" step="1"/>
                                </default-field>
                            </field>

                            <!-- 项目经理 - 支持搜索的下拉框 -->
                            <field name="managerPartyId">
                                <default-field title="项目经理">
                                    <drop-down required="true" allow-empty="true" 
                                              current="selected" combo-box="true">
                                        <option key="" text="请选择项目经理"/>
                                        <list-options list="managerList" key="${partyId}" 
                                                     text="${firstName} ${lastName} (${username})"/>
                                    </drop-down>
                                </default-field>
                            </field>

                            <!-- 客户组织 -->
                            <field name="clientPartyId">
                                <default-field title="客户">
                                    <drop-down allow-empty="true" combo-box="true">
                                        <option key="" text="内部项目"/>
                                        <list-options list="clientOrgList" key="${partyId}" 
                                                     text="${organizationName}"/>
                                    </drop-down>
                                </default-field>
                            </field>

                            <!-- 预计开始日期 -->
                            <field name="estimatedStartDate">
                                <default-field title="预计开始日期">
                                    <date-time type="date" required="true" format="yyyy-MM-dd"/>
                                </default-field>
                            </field>

                            <!-- 预计完成日期 - 有联动验证 -->
                            <field name="estimatedCompletionDate">
                                <default-field title="预计完成日期">
                                    <date-time type="date" format="yyyy-MM-dd"/>
                                    <field-validation>
                                        <compare operator="greater" to-field="estimatedStartDate"/>
                                    </field-validation>
                                </default-field>
                            </field>

                            <!-- 预估工时 -->
                            <field name="estimatedWorkHours">
                                <default-field title="预估工时 (小时)">
                                    <text-line size="10" format="#,##0.0"/>
                                </default-field>
                            </field>

                            <!-- 项目预算 - 条件显示 -->
                            <field name="budgetAmount">
                                <conditional-field condition="clientPartyId" title="项目预算 (元)">
                                    <text-line size="15" format="#,##0.00"/>
                                </conditional-field>
                            </field>

                            <!-- 项目描述 -->
                            <field name="description">
                                <default-field title="项目描述">
                                    <text-area rows="4" cols="60" maxlength="1000"/>
                                </default-field>
                            </field>

                            <!-- 提交按钮 -->
                            <field name="submitButton">
                                <default-field title="">
                                    <submit text="${projectId ? '更新项目' : '创建项目'}" 
                                           icon="fa fa-save" btn-type="success"/>
                                </default-field>
                            </field>

                            <!-- 取消按钮 -->
                            <field name="cancelButton">
                                <default-field title="">
                                    <link url="${projectId ? '../ProjectDetail' : '../FindProject'}" 
                                          text="取消" btn-type="default"
                                          parameter-map="[projectId:projectId]"/>
                                </default-field>
                            </field>
                        </form-single>
                    </box-body>
                </container-box>
            </row-col>
        </container-row>

        <!-- 如果是编辑模式，显示相关信息 -->
        <section name="EditModeExtras" condition="projectId">
            <widgets>
                <!-- 项目团队快速添加 -->
                <container-row>
                    <row-col lg="6">
                        <container-box>
                            <box-header title="团队成员管理"/>
                            <box-body>
                                <form-single name="AddMemberForm" transition="addProjectMember">
                                    <field name="projectId">
                                        <default-field><hidden default="${projectId}"/></default-field>
                                    </field>
                                    <field name="partyId">
                                        <default-field title="添加成员">
                                            <drop-down combo-box="true">
                                                <entity-options key="${partyId}" text="${firstName} ${lastName}">
                                                    <entity-find entity-name="mantle.party.PersonWithUserAccount">
                                                        <econdition field-name="disabled" value="N"/>
                                                        <order-by field-name="firstName"/>
                                                    </entity-find>
                                                </entity-options>
                                            </drop-down>
                                        </default-field>
                                    </field>
                                    <field name="roleTypeId">
                                        <default-field title="角色">
                                            <drop-down>
                                                <option key="Developer" text="开发人员"/>
                                                <option key="Tester" text="测试人员"/>
                                                <option key="Designer" text="设计师"/>
                                                <option key="Analyst" text="分析师"/>
                                            </drop-down>
                                        </default-field>
                                    </field>
                                    <field name="addMember">
                                        <default-field title="">
                                            <submit text="添加" btn-type="info" btn-size="sm"/>
                                        </default-field>
                                    </field>
                                </form-single>

                                <!-- 当前团队成员列表 -->
                                <form-list name="CurrentMembersList" list="currentMembers" skip-form="true">
                                    <entity-find entity-name="hmadmin.ProjectParty" list="currentMembers">
                                        <econdition field-name="projectId"/>
                                        <econdition field-name="thruDate" from="null"/>
                                    </entity-find>
                                    
                                    <field name="memberName">
                                        <default-field title="姓名">
                                            <display text="${firstName} ${lastName}"/>
                                        </default-field>
                                    </field>
                                    <field name="roleTypeId">
                                        <default-field title="角色">
                                            <display/>
                                        </default-field>
                                    </field>
                                    <field name="fromDate">
                                        <default-field title="加入时间">
                                            <display format="yyyy-MM-dd"/>
                                        </default-field>
                                    </field>
                                    <field name="actions">
                                        <default-field title="">
                                            <link url="removeMember" text="移除" btn-type="danger" btn-size="xs"
                                                  parameter-map="[projectId:projectId, partyId:partyId, fromDate:fromDate]"/>
                                        </default-field>
                                    </field>
                                </form-list>
                            </box-body>
                        </container-box>
                    </row-col>

                    <!-- 项目文件快速上传 -->
                    <row-col lg="6">
                        <container-box>
                            <box-header title="项目文件"/>
                            <box-body>
                                <form-single name="UploadFileForm" transition="uploadProjectFile">
                                    <field name="projectId">
                                        <default-field><hidden default="${projectId}"/></default-field>
                                    </field>
                                    <field name="contentFile">
                                        <default-field title="选择文件">
                                            <file/>
                                        </default-field>
                                    </field>
                                    <field name="description">
                                        <default-field title="文件描述">
                                            <text-line size="30"/>
                                        </default-field>
                                    </field>
                                    <field name="uploadFile">
                                        <default-field title="">
                                            <submit text="上传" btn-type="success" btn-size="sm"/>
                                        </default-field>
                                    </field>
                                </form-single>

                                <!-- 已上传文件列表 -->
                                <form-list name="ProjectFilesList" list="projectFiles" skip-form="true">
                                    <entity-find entity-name="hmadmin.ProjectContent" list="projectFiles">
                                        <econdition field-name="projectId"/>
                                        <order-by field-name="-createdDate"/>
                                        <limit count="5"/>
                                    </entity-find>
                                    
                                    <field name="contentName">
                                        <default-field title="文件名">
                                            <link url="downloadFile" text="${contentName}"
                                                  parameter-map="[contentId:contentId]"/>
                                        </default-field>
                                    </field>
                                    <field name="createdDate">
                                        <default-field title="上传时间">
                                            <display format="MM-dd HH:mm"/>
                                        </default-field>
                                    </field>
                                </form-list>
                            </box-body>
                        </container-box>
                    </row-col>
                </container-row>
            </widgets>
        </section>

        <!-- JavaScript 增强功能 -->
        <render-mode>
            <text type="html"><![CDATA[
                <script>
                $(document).ready(function() {
                    // 项目名称实时验证
                    $('#EditProjectForm_projectName').on('blur', function() {
                        var projectName = $(this).val();
                        var projectId = $('#EditProjectForm_projectId').val();
                        
                        if (projectName.length >= 2) {
                            $.ajax({
                                url: 'validateProjectName',
                                data: { projectName: projectName, projectId: projectId },
                                success: function(result) {
                                    if (!result.isValid) {
                                        $('#EditProjectForm_projectName').addClass('error');
                                        alert('项目名称已存在，请使用其他名称');
                                    } else {
                                        $('#EditProjectForm_projectName').removeClass('error');
                                    }
                                }
                            });
                        }
                    });
                    
                    // 预计完成日期联动验证
                    $('#EditProjectForm_estimatedStartDate, #EditProjectForm_estimatedCompletionDate').change(function() {
                        var startDate = new Date($('#EditProjectForm_estimatedStartDate').val());
                        var endDate = new Date($('#EditProjectForm_estimatedCompletionDate').val());
                        
                        if (endDate <= startDate) {
                            $('#EditProjectForm_estimatedCompletionDate').addClass('error');
                            alert('完成日期必须晚于开始日期');
                        } else {
                            $('#EditProjectForm_estimatedCompletionDate').removeClass('error');
                            
                            // 计算项目周期并显示
                            var timeDiff = endDate.getTime() - startDate.getTime();
                            var daysDiff = Math.ceil(timeDiff / (1000 * 3600 * 24));
                            $('#project-duration-info').text('项目周期: ' + daysDiff + ' 天');
                        }
                    });
                    
                    // 客户选择联动显示预算字段
                    $('#EditProjectForm_clientPartyId').change(function() {
                        var clientId = $(this).val();
                        if (clientId) {
                            $('#EditProjectForm_budgetAmount').closest('.form-group').show();
                        } else {
                            $('#EditProjectForm_budgetAmount').closest('.form-group').hide();
                        }
                    });
                    
                    // 表单提交前验证
                    $('#EditProjectForm').submit(function(e) {
                        var isValid = true;
                        var errors = [];
                        
                        // 验证必填字段
                        if (!$('#EditProjectForm_projectName').val().trim()) {
                            errors.push('项目名称不能为空');
                            isValid = false;
                        }
                        
                        if (!$('#EditProjectForm_managerPartyId').val()) {
                            errors.push('必须指定项目经理');
                            isValid = false;
                        }
                        
                        // 验证日期逻辑
                        var startDate = new Date($('#EditProjectForm_estimatedStartDate').val());
                        var endDate = new Date($('#EditProjectForm_estimatedCompletionDate').val());
                        
                        if (endDate && endDate <= startDate) {
                            errors.push('完成日期必须晚于开始日期');
                            isValid = false;
                        }
                        
                        if (!isValid) {
                            alert('请检查以下错误:\n' + errors.join('\n'));
                            e.preventDefault();
                        }
                    });
                });
                </script>
                
                <!-- 项目周期信息显示区域 -->
                <div id="project-duration-info" style="margin-top: 10px; color: #666; font-style: italic;"></div>
            ]]></text>
        </render-mode>
    </widgets>
</screen>
```

### 案例3：任务管理子Tab页面

#### ProjectDetail/Tasks.xml - 项目任务管理Tab

```xml
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd">

    <parameter name="projectId" required="true"/>
    
    <!-- 任务相关转换 -->
    <transition name="editTask">
        <default-response url="../../Task/EditTask"/>
    </transition>
    
    <transition name="createTask">
        <service-call name="hmadmin.TaskServices.create#Task"/>
        <default-response url="../../Task/EditTask" parameter-map="[taskId:taskId]"/>
    </transition>
    
    <transition name="updateTaskStatus">
        <service-call name="hmadmin.TaskServices.update#TaskStatus"/>
        <default-response url="."/>
    </transition>
    
    <transition name="assignTask">
        <service-call name="hmadmin.TaskServices.assign#Task"/>
        <default-response url="."/>
    </transition>

    <actions>
        <!-- 加载项目信息 -->
        <entity-find-one entity-name="hmadmin.Project" value-field="project">
            <field-map field-name="projectId"/>
        </entity-find-one>
        
        <!-- 任务统计 -->
        <service-call name="hmadmin.TaskServices.get#ProjectTaskStats" out-map="taskStats">
            <field-map field-name="projectId"/>
        </service-call>
        
        <!-- 可分配的团队成员 -->
        <entity-find entity-name="hmadmin.ProjectPartyDetail" list="teamMembers">
            <econdition field-name="projectId"/>
            <econdition field-name="thruDate" from="null"/>
            <order-by field-name="firstName"/>
        </entity-find>
        
        <!-- 任务状态选项 -->
        <entity-find entity-name="moqui.basic.StatusItem" list="taskStatusList">
            <econdition field-name="statusTypeId" value="Task"/>
            <order-by field-name="sequenceNum"/>
        </entity-find>
    </actions>

    <widgets>
        <!-- 任务统计面板 -->
        <container-row>
            <row-col lg="12">
                <container class="task-stats-panel">
                    <container-row>
                        <row-col md="2">
                            <container-box type="primary">
                                <box-body style="text-align: center;">
                                    <label text="${taskStats.totalTasks ?: 0}" type="h3"/>
                                    <label text="总任务" type="small"/>
                                </box-body>
                            </container-box>
                        </row-col>
                        <row-col md="2">
                            <container-box type="info">
                                <box-body style="text-align: center;">
                                    <label text="${taskStats.newTasks ?: 0}" type="h3"/>
                                    <label text="新建" type="small"/>
                                </box-body>
                            </container-box>
                        </row-col>
                        <row-col md="2">
                            <container-box type="warning">
                                <box-body style="text-align: center;">
                                    <label text="${taskStats.inProgressTasks ?: 0}" type="h3"/>
                                    <label text="进行中" type="small"/>
                                </box-body>
                            </container-box>
                        </row-col>
                        <row-col md="2">
                            <container-box type="success">
                                <box-body style="text-align: center;">
                                    <label text="${taskStats.completedTasks ?: 0}" type="h3"/>
                                    <label text="已完成" type="small"/>
                                </box-body>
                            </container-box>
                        </row-col>
                        <row-col md="2">
                            <container-box type="danger">
                                <box-body style="text-align: center;">
                                    <label text="${taskStats.overdueTasks ?: 0}" type="h3"/>
                                    <label text="逾期" type="small"/>
                                </box-body>
                            </container-box>
                        </row-col>
                        <row-col md="2">
                            <container-box>
                                <box-body style="text-align: center;">
                                    <label text="${taskStats.completionRate ?: 0}%" type="h3"/>
                                    <label text="完成率" type="small"/>
                                </box-body>
                            </container-box>
                        </row-col>
                    </container-row>
                </container>
            </row-col>
        </container-row>

        <!-- 快速创建任务 -->
        <container-row>
            <row-col lg="12">
                <container-box>
                    <box-header title="快速创建任务"/>
                    <box-body>
                        <form-single name="QuickCreateTaskForm" transition="createTask">
                            <field name="projectId">
                                <default-field><hidden default="${projectId}"/></default-field>
                            </field>
                            
                            <field-layout>
                                <field-row>
                                    <field-ref name="taskName"/>
                                    <field-ref name="assigneePartyId"/>
                                    <field-ref name="priority"/>
                                    <field-ref name="createButton"/>
                                </field-row>
                            </field-layout>
                            
                            <field name="taskName">
                                <default-field title="任务名称">
                                    <text-line size="30" required="true" placeholder="输入任务名称..."/>
                                </default-field>
                            </field>
                            
                            <field name="assigneePartyId">
                                <default-field title="指派给">
                                    <drop-down allow-empty="true">
                                        <option key="" text="暂不指派"/>
                                        <list-options list="teamMembers" key="${partyId}" text="${firstName} ${lastName}"/>
                                    </drop-down>
                                </default-field>
                            </field>
                            
                            <field name="priority">
                                <default-field title="优先级">
                                    <drop-down>
                                        <option key="1" text="低"/>
                                        <option key="5" text="中" selected="selected"/>
                                        <option key="9" text="高"/>
                                    </drop-down>
                                </default-field>
                            </field>
                            
                            <field name="createButton">
                                <default-field title="">
                                    <submit text="创建任务" btn-type="success" icon="fa fa-plus"/>
                                </default-field>
                            </field>
                        </form-single>
                    </box-body>
                </container-box>
            </row-col>
        </container-row>

        <!-- 任务列表 -->
        <container-row>
            <row-col lg="12">
                <container-box>
                    <box-header title="项目任务列表"/>
                    <box-toolbar>
                        <!-- 视图切换 -->
                        <container>
                            <label text="视图: "/>
                            <link url="." text="列表" btn-type="default" btn-size="sm" 
                                  parameter-map="[projectId:projectId, viewMode:'list']"/>
                            <link url="." text="看板" btn-type="default" btn-size="sm"
                                  parameter-map="[projectId:projectId, viewMode:'kanban']"/>
                            <link url="." text="甘特图" btn-type="default" btn-size="sm"
                                  parameter-map="[projectId:projectId, viewMode:'gantt']"/>
                        </container>
                        
                        <!-- 批量操作 -->
                        <container-dialog id="BatchAssignDialog" button-text="批量分配" btn-type="info">
                            <form-single name="BatchAssignForm" transition="batchAssignTasks">
                                <field name="taskIds">
                                    <default-field><hidden/></default-field>
                                </field>
                                <field name="assigneePartyId">
                                    <default-field title="分配给">
                                        <drop-down>
                                            <list-options list="teamMembers" key="${partyId}" text="${firstName} ${lastName}"/>
                                        </drop-down>
                                    </default-field>
                                </field>
                                <field name="batchAssign">
                                    <default-field title="">
                                        <submit text="批量分配"/>
                                    </default-field>
                                </field>
                            </form-single>
                        </container-dialog>
                    </box-toolbar>
                    
                    <box-body>
                        <!-- 任务搜索过滤 -->
                        <form-single name="TaskFilterForm" transition="." extends-resource="component://webroot/screen/webroot/apps/common/includes/SearchFormBase.xml">
                            <field name="projectId">
                                <default-field><hidden default="${projectId}"/></default-field>
                            </field>
                            
                            <field-layout>
                                <field-row>
                                    <field-ref name="taskName"/>
                                    <field-ref name="statusId"/>
                                    <field-ref name="assigneePartyId"/>
                                    <field-ref name="submitButton"/>
                                </field-row>
                            </field-layout>
                            
                            <field name="taskName">
                                <default-field title="任务名称">
                                    <text-line size="20" placeholder="搜索任务..."/>
                                </default-field>
                            </field>
                            
                            <field name="statusId">
                                <default-field title="状态">
                                    <drop-down allow-empty="true">
                                        <option key="" text="全部状态"/>
                                        <list-options list="taskStatusList" key="${statusId}" text="${description}"/>
                                    </drop-down>
                                </default-field>
                            </field>
                            
                            <field name="assigneePartyId">
                                <default-field title="负责人">
                                    <drop-down allow-empty="true">
                                        <option key="" text="全部成员"/>
                                        <list-options list="teamMembers" key="${partyId}" text="${firstName} ${lastName}"/>
                                    </drop-down>
                                </default-field>
                            </field>
                            
                            <field name="submitButton">
                                <default-field title="">
                                    <submit text="筛选" icon="fa fa-filter"/>
                                </default-field>
                            </field>
                        </form-single>

                        <!-- 任务数据列表 -->
                        <form-list name="ProjectTaskList" list="taskList" 
                                  skip-form="true" select-columns="true" saved-finds="true">
                            
                            <!-- 任务查询 -->
                            <entity-find entity-name="hmadmin.TaskDetailView" list="taskList">
                                <search-form-inputs default-order-by="priority,-createdDate"/>
                                <econdition field-name="projectId"/>
                                <econdition field-name="taskName" operator="like" value="%${taskName}%" ignore-if-empty="true"/>
                                <econdition field-name="statusId" ignore-if-empty="true"/>
                                <econdition field-name="assigneePartyId" ignore-if-empty="true"/>
                            </entity-find>

                            <!-- 批量选择 -->
                            <field name="taskId">
                                <header-field title="选择">
                                    <check type="all-checkbox"/>
                                </header-field>
                                <default-field>
                                    <check all-checked="false">
                                        <option key="${taskId}" text=""/>
                                    </check>
                                </default-field>
                            </field>

                            <!-- 任务名称 -->
                            <field name="taskName">
                                <header-field title="任务名称" show-order-by="true"/>
                                <default-field>
                                    <link url="editTask" text="${taskName}" 
                                          parameter-map="[taskId:taskId]" tooltip="${description}"/>
                                </default-field>
                            </field>

                            <!-- 任务状态 - 可直接更改 -->
                            <field name="statusId">
                                <header-field title="状态" show-order-by="true"/>
                                <default-field>
                                    <drop-down submit-on-select="updateTaskStatus" 
                                              parameter-map="[taskId:taskId]">
                                        <list-options list="taskStatusList" key="${statusId}" text="${description}"/>
                                    </drop-down>
                                </default-field>
                            </field>

                            <!-- 优先级 - 彩色显示 -->
                            <field name="priority">
                                <header-field title="优先级" show-order-by="true"/>
                                <default-field>
                                    <display text="${priority == 9 ? '高' : priority == 5 ? '中' : '低'}"
                                            style="color: ${priority == 9 ? 'red' : priority == 5 ? 'orange' : 'green'}; font-weight: bold;"/>
                                </default-field>
                            </field>

                            <!-- 负责人 - 可直接分配 -->
                            <field name="assigneePartyId">
                                <header-field title="负责人"/>
                                <default-field>
                                    <drop-down submit-on-select="assignTask" 
                                              parameter-map="[taskId:taskId]" allow-empty="true">
                                        <option key="" text="未分配"/>
                                        <list-options list="teamMembers" key="${partyId}" text="${firstName} ${lastName}"/>
                                    </drop-down>
                                </default-field>
                            </field>

                            <!-- 截止日期 - 带逾期提醒 -->
                            <field name="estimatedCompletionDate">
                                <header-field title="截止日期" show-order-by="true"/>
                                <default-field>
                                    <display format="MM-dd" style="${groovy:
                                        def today = ec.user.nowTimestamp
                                        def dueDate = estimatedCompletionDate
                                        if (dueDate && dueDate < today && statusId != 'TaskCompleted') return 'color: red; font-weight: bold;'
                                        else if (dueDate && dueDate < (today + 3) && statusId != 'TaskCompleted') return 'color: orange; font-weight: bold;'
                                        else return ''
                                    }"/>
                                </default-field>
                            </field>

                            <!-- 预估工时 -->
                            <field name="estimatedHours">
                                <header-field title="预估工时"/>
                                <default-field>
                                    <display format="#0.0" text="${estimatedHours ?: 0}h"/>
                                </default-field>
                            </field>

                            <!-- 实际工时 -->
                            <field name="actualHours">
                                <header-field title="实际工时"/>
                                <default-field>
                                    <display format="#0.0" text="${actualHours ?: 0}h" 
                                            style="${groovy: actualHours > estimatedHours ? 'color: red;' : ''}"/>
                                </default-field>
                            </field>

                            <!-- 操作列 -->
                            <field name="actions">
                                <header-field title="操作"/>
                                <default-field>
                                    <container>
                                        <link url="editTask" text="编辑" btn-type="primary" btn-size="xs"
                                              parameter-map="[taskId:taskId]" icon="fa fa-edit"/>
                                        
                                        <!-- 条件操作按钮 -->
                                        <link url="startTask" text="开始" btn-type="success" btn-size="xs"
                                              parameter-map="[taskId:taskId]" icon="fa fa-play"
                                              condition="statusId == 'TaskReady'"/>
                                              
                                        <link url="completeTask" text="完成" btn-type="info" btn-size="xs"
                                              parameter-map="[taskId:taskId]" icon="fa fa-check"
                                              condition="statusId == 'TaskInProgress'"/>
                                              
                                        <link url="logTime" text="记录工时" btn-type="warning" btn-size="xs"
                                              parameter-map="[taskId:taskId]" icon="fa fa-clock"
                                              condition="assigneePartyId == ec.user.userAccount?.partyId"/>
                                    </container>
                                </default-field>
                            </field>
                        </form-list>
                    </box-body>
                </container-box>
            </row-col>
        </container-row>

        <!-- 看板视图 (条件显示) -->
        <section name="KanbanView" condition="ec.web.parameters.viewMode == 'kanban'">
            <widgets>
                <container-row>
                    <row-col lg="12">
                        <container-box>
                            <box-header title="任务看板"/>
                            <box-body>
                                <container-row class="kanban-board">
                                    <!-- 待办列 -->
                                    <row-col md="3">
                                        <container-box type="default">
                                            <box-header title="待办 (${todoCount})"/>
                                            <box-body class="kanban-column" data-status="TaskReady">
                                                <iterate list="todoTasks" entry="task">
                                                    <container class="kanban-card" draggable="true" data-task-id="${task.taskId}">
                                                        <label text="${task.taskName}" type="strong"/>
                                                        <label text="${task.assigneeName ?: '未分配'}" type="small"/>
                                                        <label text="${ec.l10n.format(task.estimatedCompletionDate, 'MM-dd')}" type="small"/>
                                                    </container>
                                                </iterate>
                                            </box-body>
                                        </container-box>
                                    </row-col>
                                    
                                    <!-- 进行中列 -->
                                    <row-col md="3">
                                        <container-box type="info">
                                            <box-header title="进行中 (${inProgressCount})"/>
                                            <box-body class="kanban-column" data-status="TaskInProgress">
                                                <iterate list="inProgressTasks" entry="task">
                                                    <container class="kanban-card" draggable="true" data-task-id="${task.taskId}">
                                                        <label text="${task.taskName}" type="strong"/>
                                                        <label text="${task.assigneeName}" type="small"/>
                                                        <render-mode>
                                                            <text type="html"><![CDATA[
                                                                <div class="progress" style="height: 5px;">
                                                                    <div class="progress-bar" style="width: ${task.progressPercentage ?: 0}%"></div>
                                                                </div>
                                                            ]]></text>
                                                        </render-mode>
                                                    </container>
                                                </iterate>
                                            </box-body>
                                        </container-box>
                                    </row-col>
                                    
                                    <!-- 测试列 -->
                                    <row-col md="3">
                                        <container-box type="warning">
                                            <box-header title="测试中 (${testingCount})"/>
                                            <box-body class="kanban-column" data-status="TaskTesting">
                                                <iterate list="testingTasks" entry="task">
                                                    <container class="kanban-card" draggable="true" data-task-id="${task.taskId}">
                                                        <label text="${task.taskName}" type="strong"/>
                                                        <label text="${task.assigneeName}" type="small"/>
                                                    </container>
                                                </iterate>
                                            </box-body>
                                        </container-box>
                                    </row-col>
                                    
                                    <!-- 完成列 -->
                                    <row-col md="3">
                                        <container-box type="success">
                                            <box-header title="已完成 (${completedCount})"/>
                                            <box-body class="kanban-column" data-status="TaskCompleted">
                                                <iterate list="completedTasks" entry="task">
                                                    <container class="kanban-card" data-task-id="${task.taskId}">
                                                        <label text="${task.taskName}" type="strong"/>
                                                        <label text="${task.assigneeName}" type="small"/>
                                                        <label text="完成于 ${ec.l10n.format(task.actualCompletionDate, 'MM-dd')}" type="small"/>
                                                    </container>
                                                </iterate>
                                            </box-body>
                                        </container-box>
                                    </row-col>
                                </container-row>
                            </box-body>
                        </container-box>
                    </row-col>
                </container-row>
            </widgets>
        </section>

        <!-- 页面增强脚本 -->
        <render-mode>
            <text type="html"><![CDATA[
                <script>
                $(document).ready(function() {
                    // 快速创建任务回车支持
                    $('#QuickCreateTaskForm_taskName').keypress(function(e) {
                        if (e.which == 13) {
                            $('#QuickCreateTaskForm').submit();
                        }
                    });
                    
                    // 批量选择功能
                    $('#ProjectTaskList_selectAll').change(function() {
                        $('input[name="taskIds"]').prop('checked', this.checked);
                        updateBatchOperationUI();
                    });
                    
                    $('input[name="taskIds"]').change(function() {
                        updateBatchOperationUI();
                    });
                    
                    function updateBatchOperationUI() {
                        var selectedCount = $('input[name="taskIds"]:checked').length;
                        if (selectedCount > 0) {
                            $('#BatchAssignDialog').show();
                            $('#batch-operation-info').text('已选择 ' + selectedCount + ' 个任务');
                        } else {
                            $('#BatchAssignDialog').hide();
                        }
                    }
                    
                    // 看板拖拽功能
                    if ($('.kanban-board').length > 0) {
                        $('.kanban-card').draggable({
                            revert: true,
                            zIndex: 1000
                        });
                        
                        $('.kanban-column').droppable({
                            accept: '.kanban-card',
                            hoverClass: 'kanban-drop-hover',
                            drop: function(event, ui) {
                                var taskId = ui.draggable.data('task-id');
                                var newStatus = $(this).data('status');
                                
                                $.ajax({
                                    url: 'updateTaskStatus',
                                    method: 'POST',
                                    data: { taskId: taskId, statusId: newStatus },
                                    success: function() {
                                        ui.draggable.appendTo(event.target);
                                        ui.draggable.css({top: 0, left: 0});
                                        location.reload(); // 刷新统计数字
                                    },
                                    error: function() {
                                        alert('更新任务状态失败');
                                    }
                                });
                            }
                        });
                    }
                    
                    // 任务状态快速切换
                    $('select[name="statusId"]').change(function() {
                        var $this = $(this);
                        var taskId = $this.closest('tr').find('input[name="taskIds"]').val();
                        var newStatus = $this.val();
                        
                        // 显示加载状态
                        $this.prop('disabled', true);
                        
                        $.ajax({
                            url: 'updateTaskStatus',
                            method: 'POST',
                            data: { taskId: taskId, statusId: newStatus },
                            success: function() {
                                // 成功反馈
                                $this.closest('td').addClass('success-highlight');
                                setTimeout(function() {
                                    $this.closest('td').removeClass('success-highlight');
                                }, 1000);
                            },
                            error: function() {
                                alert('更新状态失败');
                                // 恢复原状态
                                $this.val($this.data('original-value'));
                            },
                            complete: function() {
                                $this.prop('disabled', false);
                            }
                        });
                    });
                    
                    // 保存下拉框原始值
                    $('select[name="statusId"]').each(function() {
                        $(this).data('original-value', $(this).val());
                    });
                });
                </script>
                
                <style>
                .task-stats-panel .container-box {
                    margin-bottom: 0;
                }
                
                .kanban-board {
                    min-height: 600px;
                }
                
                .kanban-column {
                    min-height: 500px;
                    padding: 10px;
                }
                
                .kanban-card {
                    background: white;
                    border: 1px solid #ddd;
                    border-radius: 4px;
                    padding: 10px;
                    margin-bottom: 10px;
                    cursor: move;
                    box-shadow: 0 1px 3px rgba(0,0,0,0.1);
                    transition: box-shadow 0.2s;
                }
                
                .kanban-card:hover {
                    box-shadow: 0 2px 8px rgba(0,0,0,0.2);
                }
                
                .kanban-drop-hover {
                    background-color: #f0f8ff;
                    border: 2px dashed #007bff;
                }
                
                .success-highlight {
                    background-color: #d4edda !important;
                    transition: background-color 1s;
                }
                
                .progress {
                    margin: 5px 0;
                }
                </style>
            ]]></text>
        </render-mode>
    </widgets>
</screen>
```

## 高级功能实现模式

### 1. 权限控制模式

```xml
<!-- 基于角色的权限控制 -->
<screen>
    <actions>
        <!-- 权限检查 -->
        <set field="isProjectManager" from="project.managerPartyId == ec.user.userAccount?.partyId"/>
        <set field="isTeamMember" from="ec.entity.find('ProjectParty')
                                          .condition('projectId', projectId)
                                          .condition('partyId', ec.user.userAccount?.partyId)
                                          .condition('thruDate', null)
                                          .count() > 0"/>
        <set field="hasAdminPermission" from="ec.user.hasPermission('PROJECT_ADMIN')"/>
        
        <!-- 综合权限判断 -->
        <set field="canEditProject" from="hasAdminPermission || isProjectManager"/>
        <set field="canViewProject" from="canEditProject || isTeamMember || ec.user.hasPermission('PROJECT_VIEW')"/>
        
        <!-- 权限不足处理 -->
        <if condition="!canViewProject">
            <then>
                <message error="true">您没有权限访问此项目</message>
                <script>ec.web.sendRedirect('/apps/hivemind/Project/FindProject')</script>
                <return/>
            </then>
        </if>
    </actions>
    
    <widgets>
        <!-- 条件显示的操作按钮 -->
        <section name="ProjectActions" condition="canEditProject">
            <widgets>
                <link url="EditProject" text="编辑项目"/>
                <link url="AddMember" text="添加成员"/>
            </widgets>
        </section>
        
        <!-- 只读模式提示 -->
        <section name="ReadOnlyNotice" condition="!canEditProject && canViewProject">
            <widgets>
                <container class="alert alert-info">
                    <label text="您当前是以只读模式查看此项目"/>
                </container>
            </widgets>
        </section>
    </widgets>
</screen>
```

### 2. 数据验证模式

```xml
<!-- 复杂表单验证 -->
<form-single name="ProjectForm" transition="saveProject">
    <field name="projectName">
        <default-field title="项目名称">
            <text-line required="true" maxlength="100"/>
            <!-- 服务端验证 -->
            <field-validation>
                <service-call name="ProjectServices.validate#ProjectName"/>
            </field-validation>
            <!-- 客户端验证 -->
            <field-validation>
                <matches regexp="^[a-zA-Z0-9\u4e00-\u9fa5\s\-_]+$" 
                         message="项目名称只能包含字母、数字、中文、空格、连字符和下划线"/>
            </field-validation>
        </default-field>
    </field>
    
    <field name="estimatedCompletionDate">
        <default-field title="预计完成日期">
            <date-time type="date"/>
            <!-- 日期逻辑验证 -->
            <field-validation>
                <compare operator="greater" to-field="estimatedStartDate" 
                        message="完成日期必须晚于开始日期"/>
            </field-validation>
            <!-- 自定义验证服务 -->
            <field-validation>
                <service-call name="ProjectServices.validate#ProjectDates"/>
            </field-validation>
        </default-field>
    </field>
    
    <field name="budgetAmount">
        <default-field title="预算金额">
            <text-line format="#,##0.00"/>
            <!-- 数值范围验证 -->
            <field-validation>
                <number-range min="0" max="10000000" 
                             message="预算金额必须在0到1000万之间"/>
            </field-validation>
        </default-field>
    </field>
</form-single>
```

### 3. 国际化支持模式

```xml
<!-- 多语言支持 -->
<screen>
    <actions>
        <!-- 获取当前语言 -->
        <set field="currentLocale" from="ec.user.locale"/>
    </actions>
    
    <widgets>
        <form-list name="ProjectList" list="projectList">
            <field name="projectName">
                <default-field title="${ec.l10n.localize('ProjectName')}">
                    <display text="${ec.l10n.localize('Project.' + projectId + '.name', projectName)}"/>
                </default-field>
            </field>
            
            <field name="statusId">
                <default-field title="${ec.l10n.localize('Status')}">
                    <!-- 状态描述本地化 -->
                    <display-entity entity-name="StatusItem" 
                                   text="${ec.l10n.localize('Status.' + statusId, description)}"/>
                </default-field>
            </field>
        </form-list>
    </widgets>
</screen>
```

## 性能优化最佳实践

### 1. 查询优化

```xml
<!-- 优化的数据查询 -->
<entity-find entity-name="Project" list="projectList" cache="true">
    <!-- 只查询需要的字段 -->
    <select-field field-name="projectId,projectName,statusId,managerPartyId"/>
    <select-field field-name="estimatedStartDate,estimatedCompletionDate"/>
    
    <!-- 使用索引字段排序 -->
    <order-by field-name="projectId"/>
    
    <!-- 合理的分页 -->
    <offset-limit offset="${pageIndex * pageSize}" limit="${pageSize}"/>
    
    <!-- 避免N+1查询，使用视图 -->
    <!-- <entity-condition>... 使用 ProjectSummaryView 而不是多次关联查询 -->
</entity-find>

<!-- 统计查询优化 -->
<entity-count entity-name="ProjectTask" count-field="taskCount">
    <econdition field-name="projectId"/>
    <!-- 添加必要的条件以利用索引 -->
    <econdition field-name="statusId" operator="not-equals" value="TaskCancelled"/>
</entity-count>
```

### 2. 缓存策略

```xml
<!-- 合理使用缓存 -->
<actions>
    <!-- 缓存不频繁变化的数据 -->
    <entity-find entity-name="StatusItem" list="statusList" cache="true">
        <econdition field-name="statusTypeId" value="Project"/>
    </entity-find>
    
    <!-- 不缓存频繁变化的数据 -->
    <entity-find entity-name="TaskTimeEntry" list="recentTimeEntries">
        <econdition field-name="projectId"/>
        <order-by field-name="-fromDate"/>
        <limit count="10"/>
        <!-- 不使用 cache="true" -->
    </entity-find>
</actions>
```

### 3. 延迟加载

```xml
<!-- 条件加载重数据 -->
<section name="ProjectDetails" condition="showDetails == 'true'">
    <actions>
        <!-- 只在需要时加载详细数据 -->
        <service-call name="ProjectServices.get#ProjectFullDetails" out-map="projectDetails">
            <field-map field-name="projectId"/>
        </service-call>
    </actions>
    <widgets>
        <!-- 详细信息显示 -->
    </widgets>
</section>

<!-- 分步加载 -->
<container-dialog id="ProjectDetailsDialog" button-text="查看详情">
    <!-- 点击时才加载详细信息 -->
    <include-screen location="component://hivemind/screen/hmadmin/Project/ProjectDetailDialog.xml"/>
</container-dialog>
```

## 组件开发规范总结

### 1. 目录结构规范

```
推荐的screen目录组织：
- 按功能模块分目录 (Project/, Task/, User/)
- 统一的命名约定 (Find*.xml, Edit*.xml, *Detail.xml)
- 公共组件放在 includes/ 目录
- 每个功能至少包含：列表页、编辑页、详情页
```

### 2. 页面设计模式

- **列表页**: Find*.xml - 搜索表单 + 数据列表 + 批量操作
- **编辑页**: Edit*.xml - 表单验证 + 数据保存 + 错误处理
- **详情页**: *Detail.xml - Tab页面 + 相关数据展示
- **Dashboard**: 仪表板汇总多个模块数据

### 3. 用户体验优化

- 合理的面包屑导航
- 清晰的操作按钮布局
- 友好的错误提示信息
- 响应式布局设计
- 快捷键和批量操作支持

### 4. 代码质量保证

- 统一的代码格式和注释
- 合理的权限控制检查
- 完善的数据验证机制
- 国际化和本地化支持
- 性能优化和缓存策略

这份文档总结了 Moqui Framework 中 screen 开发的最佳实践，通过分析 HiveMind、Example 等组件的实际案例，提供了完整的开发指导和规范。开发者可以参考这些模式和示例，快速构建高质量的 Moqui 应用组件。