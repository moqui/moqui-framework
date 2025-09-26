# Moqui 组件开发标准模板集

## 问题现状分析

您准确指出了 Moqui 框架当前面临的两大核心问题:
1. **调试工具不足**: 问题定位困难，错误信息不够直观
2. **开发模板缺失**: 依赖个人经验，缺乏标准化开发指南

## 解决方案：标准化组件模板

### 1. 基础组件结构模板

```
标准组件目录结构:
my-component/
├── MoquiConf.xml                    # 组件配置 [必需]
├── component.xml                    # 组件元数据 [推荐]
├── data/                           # 初始数据
│   ├── MyComponentTypeData.xml     # 基础类型数据
│   ├── MyComponentSecurityData.xml # 权限数据
│   └── MyComponentDemoData.xml     # 演示数据
├── entity/                         # 实体定义
│   └── MyComponentEntities.xml
├── screen/                         # 页面定义 [核心]
│   ├── MyApp/                     # 应用主目录
│   │   ├── apps.xml               # 应用入口 [必需]
│   │   ├── dashboard.xml          # 仪表板
│   │   ├── Entity1/               # 实体1管理
│   │   │   ├── FindEntity1.xml    # 列表页 [标准]
│   │   │   ├── EditEntity1.xml    # 编辑页 [标准]
│   │   │   └── Entity1Detail.xml  # 详情页 [标准]
│   │   ├── Entity2/               # 实体2管理
│   │   └── includes/              # 公共组件
│   │       ├── CommonForms.xml    # 公共表单
│   │       └── CommonWidgets.xml  # 公共控件
│   └── webroot.xml                # Web根 [可选]
├── service/                        # 服务定义
│   └── MyComponentServices.xml
├── template/                       # 模板文件
│   ├── email/                     # 邮件模板
│   └── report/                    # 报表模板
├── webapp/                         # 静态资源
│   ├── css/
│   ├── js/
│   └── images/
└── README.md                       # 组件说明
```

### 2. 组件配置模板 (MoquiConf.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<moqui-conf xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
            xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/moqui-conf-3.xsd">

    <!-- 组件基本信息 -->
    <component name="my-component" version="1.0.0"/>
    
    <!-- 依赖组件 -->
    <component-list>
        <component name="moqui-framework"/>
        <component name="webroot"/>
        <!-- 其他依赖 -->
    </component-list>
    
    <!-- 实体定义加载 -->
    <entity-facade>
        <datasource group-name="transactional">
            <inline-other entity-group="my-component" 
                         entities-location="component://my-component/entity/MyComponentEntities.xml"/>
        </datasource>
    </entity-facade>
    
    <!-- 服务定义加载 -->
    <service-facade>
        <service-location location="component://my-component/service/MyComponentServices.xml"/>
    </service-facade>
    
    <!-- 屏幕路径映射 -->
    <screen-facade>
        <screen-location location="component://my-component/screen"/>
    </screen-facade>
    
    <!-- 安全配置 -->
    <user-facade>
        <password min-length="8" history-limit="5"/>
    </user-facade>
    
    <!-- Web应用配置 -->
    <webapp-list>
        <webapp name="webroot">
            <root-screen location="component://my-component/screen/webroot.xml"/>
            <!-- 静态资源映射 -->
            <webapp-resource location="component://my-component/webapp" url-path="/static/my-component"/>
        </webapp>
    </webapp-list>

</moqui-conf>
```

### 3. 应用入口模板 (apps.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="我的应用"
        default-menu-index="50"
        default-menu-include="true"
        require-authentication="true">

    <!-- 子屏幕定义 - 按菜单顺序 -->
    <subscreens default-item="dashboard">
        <!-- 仪表板 -->
        <subscreens-item name="dashboard" location="dashboard.xml" 
                        menu-title="仪表板" menu-index="1" menu-include="true"/>
                        
        <!-- 核心功能模块 -->
        <subscreens-item name="Entity1" location="Entity1" 
                        menu-title="实体1管理" menu-index="10" menu-include="true"/>
        <subscreens-item name="Entity2" location="Entity2" 
                        menu-title="实体2管理" menu-index="20" menu-include="true"/>
        
        <!-- 系统管理 (管理员权限) -->
        <subscreens-item name="admin" location="admin" 
                        menu-title="系统管理" menu-index="90" menu-include="N">
            <condition>
                <if-has-permission permission="MY_COMPONENT_ADMIN"/>
            </condition>
        </subscreens-item>
        
        <!-- 帮助和关于 -->
        <subscreens-item name="help" location="help.xml" 
                        menu-title="帮助" menu-index="99" menu-include="true"/>
    </subscreens>

    <!-- 页面级权限控制 -->
    <pre-actions>
        <!-- 检查基本访问权限 -->
        <if condition="!ec.user.hasPermission('MY_COMPONENT_VIEW')">
            <then>
                <message error="true">您没有权限访问此应用</message>
                <script>ec.web.sendRedirect('/Login')</script>
                <return/>
            </then>
        </if>
    </pre-actions>

    <!-- 页面内容 -->
    <widgets>
        <!-- 应用标题和描述 -->
        <section name="AppHeader" condition="ec.web.requestParameters._isRootPath">
            <widgets>
                <container-row>
                    <row-col lg="12">
                        <container class="app-header">
                            <label text="我的应用" type="h1"/>
                            <label text="这是一个标准的Moqui应用组件示例" type="p"/>
                        </container>
                    </row-col>
                </container-row>
            </widgets>
        </section>
        
        <!-- 子屏幕内容 -->
        <subscreens-active/>
    </widgets>
</screen>
```

### 4. 标准列表页模板 (FindEntity.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="实体列表"
        require-authentication="true">

    <!-- 页面转换定义 -->
    <transition name="editEntity">
        <default-response url="EditEntity"/>
    </transition>
    
    <transition name="entityDetail">
        <default-response url="EntityDetail"/>
    </transition>
    
    <transition name="deleteEntity">
        <service-call name="my.component.MyServices.delete#Entity"/>
        <default-response url="."/>
    </transition>
    
    <transition name="exportEntities">
        <default-response url="." url-type="plain">
            <parameter name="renderMode" value="csv"/>
            <parameter name="pageNoLimit" value="true"/>
        </default-response>
    </transition>

    <!-- 数据加载和权限检查 -->
    <actions>
        <!-- 权限检查 -->
        <set field="hasCreatePermission" from="ec.user.hasPermission('MY_ENTITY_CREATE')"/>
        <set field="hasUpdatePermission" from="ec.user.hasPermission('MY_ENTITY_UPDATE')"/>
        <set field="hasDeletePermission" from="ec.user.hasPermission('MY_ENTITY_DELETE')"/>
        
        <!-- 搜索参数处理 -->
        <set field="entityName" from="entityName ?: ''"/>
        <set field="statusId" from="statusId ?: ''"/>
        <set field="fromDate" from="fromDate ?: null"/>
        <set field="thruDate" from="thruDate ?: null"/>
        
        <!-- 加载选项数据 -->
        <entity-find entity-name="StatusItem" list="statusList" cache="true">
            <econdition field-name="statusTypeId" value="MyEntityStatus"/>
            <order-by field-name="sequenceNum"/>
        </entity-find>
    </actions>

    <!-- 页面内容 -->
    <widgets>
        <container-box>
            <box-header title="实体管理" icon="fa fa-list"/>
            
            <!-- 工具栏 -->
            <box-toolbar>
                <!-- 新增按钮 -->
                <section name="CreateButton" condition="hasCreatePermission">
                    <widgets>
                        <link url="editEntity" text="新增实体" btn-type="success" 
                              icon="fa fa-plus"/>
                    </widgets>
                </section>
                
                <!-- 导出按钮 -->
                <link url="exportEntities" text="导出Excel" btn-type="info" 
                      icon="fa fa-download"/>
                      
                <!-- 批量操作 -->
                <container-dialog id="BatchOperationDialog" button-text="批量操作" 
                                btn-type="warning">
                    <!-- 批量操作表单 -->
                    <form-single name="BatchOperationForm" transition="batchUpdate">
                        <field name="entityIds"><default-field><hidden/></default-field></field>
                        <field name="operation">
                            <default-field title="操作">
                                <radio>
                                    <option key="activate" text="激活"/>
                                    <option key="deactivate" text="停用"/>
                                    <option key="delete" text="删除"/>
                                </radio>
                            </default-field>
                        </field>
                        <field name="submitButton">
                            <default-field><submit text="执行"/></default-field>
                        </field>
                    </form-single>
                </container-dialog>
            </box-toolbar>

            <box-body>
                <!-- 搜索表单 -->
                <form-single name="SearchForm" transition="." 
                           extends="component://my-component/screen/MyApp/includes/CommonSearchForm.xml#SearchFormBase">
                    <field name="entityName">
                        <default-field title="实体名称">
                            <text-line size="30" ac-transition="suggestEntityNames"/>
                        </default-field>
                    </field>
                    <field name="statusId">
                        <default-field title="状态">
                            <drop-down allow-empty="true">
                                <list-options list="statusList" key="${statusId}" text="${description}"/>
                            </drop-down>
                        </default-field>
                    </field>
                    <field name="dateRange">
                        <default-field title="创建时间">
                            <date-period/>
                        </default-field>
                    </field>
                    <field name="submitButton">
                        <default-field><submit text="搜索" icon="fa fa-search"/></default-field>
                    </field>
                    <field name="resetButton">
                        <default-field><link url="." text="重置"/></default-field>
                    </field>
                </form-single>

                <!-- 数据列表 -->
                <form-list name="EntityList" list="entityList" 
                          skip-form="true" select-columns="true" saved-finds="true"
                          paginate="true">
                    
                    <!-- 数据查询 -->
                    <entity-find entity-name="MyEntity" list="entityList">
                        <search-form-inputs default-order-by="-createdDate"/>
                        <econdition field-name="entityName" operator="like" 
                                   value="%${entityName}%" ignore-if-empty="true"/>
                        <econdition field-name="statusId" ignore-if-empty="true"/>
                        <date-filter from-field-name="fromDate" thru-field-name="thruDate"/>
                        <select-field field-name="entityId,entityName,statusId,createdDate"/>
                    </entity-find>

                    <!-- 批量选择 -->
                    <field name="entityId">
                        <header-field title="选择" show-order-by="false">
                            <check type="all-checkbox"/>
                        </header-field>
                        <default-field>
                            <check all-checked="false">
                                <option key="${entityId}" text=""/>
                            </check>
                        </default-field>
                    </field>

                    <!-- 实体名称 - 链接到详情页 -->
                    <field name="entityName">
                        <header-field title="实体名称" show-order-by="true"/>
                        <default-field>
                            <link url="entityDetail" text="${entityName}" 
                                  parameter-map="[entityId:entityId]"/>
                        </default-field>
                    </field>

                    <!-- 状态显示 -->
                    <field name="statusId">
                        <header-field title="状态" show-order-by="true"/>
                        <default-field>
                            <display-entity entity-name="StatusItem"/>
                        </default-field>
                    </field>

                    <!-- 创建时间 -->
                    <field name="createdDate">
                        <header-field title="创建时间" show-order-by="true"/>
                        <default-field>
                            <display format="yyyy-MM-dd HH:mm"/>
                        </default-field>
                    </field>

                    <!-- 操作列 -->
                    <field name="actions">
                        <header-field title="操作"/>
                        <default-field>
                            <container>
                                <!-- 查看详情 -->
                                <link url="entityDetail" text="详情" btn-type="info" btn-size="xs"
                                      parameter-map="[entityId:entityId]" icon="fa fa-eye"/>
                                      
                                <!-- 编辑 -->
                                <section name="EditAction" condition="hasUpdatePermission">
                                    <widgets>
                                        <link url="editEntity" text="编辑" btn-type="primary" btn-size="xs"
                                              parameter-map="[entityId:entityId]" icon="fa fa-edit"/>
                                    </widgets>
                                </section>
                                
                                <!-- 删除 -->
                                <section name="DeleteAction" condition="hasDeletePermission">
                                    <widgets>
                                        <link url="deleteEntity" text="删除" btn-type="danger" btn-size="xs"
                                              parameter-map="[entityId:entityId]" icon="fa fa-trash"
                                              confirmation="确定要删除这个实体吗？"/>
                                    </widgets>
                                </section>
                            </container>
                        </default-field>
                    </field>
                </form-list>
            </box-body>
        </container-box>
    </widgets>
</screen>
```

### 5. 标准编辑页模板 (EditEntity.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        require-authentication="true">

    <parameter name="entityId"/>
    
    <!-- 表单转换 -->
    <transition name="createEntity">
        <service-call name="my.component.MyServices.create#Entity"/>
        <default-response url="../EntityDetail" parameter-map="[entityId:entityId]"/>
    </transition>
    
    <transition name="updateEntity">
        <service-call name="my.component.MyServices.update#Entity"/>
        <default-response url="../EntityDetail" parameter-map="[entityId:entityId]"/>
    </transition>
    
    <transition name="validateEntityName">
        <service-call name="my.component.MyServices.validate#EntityName"/>
        <default-response type="json-object"/>
    </transition>

    <!-- 数据加载和权限检查 -->
    <actions>
        <!-- 权限检查 -->
        <if condition="entityId">
            <then>
                <if condition="!ec.user.hasPermission('MY_ENTITY_UPDATE')">
                    <then>
                        <message error="true">您没有权限编辑此实体</message>
                        <script>ec.web.sendRedirect('../FindEntity')</script>
                        <return/>
                    </then>
                </if>
            </then>
            <else>
                <if condition="!ec.user.hasPermission('MY_ENTITY_CREATE')">
                    <then>
                        <message error="true">您没有权限创建新实体</message>
                        <script>ec.web.sendRedirect('../FindEntity')</script>
                        <return/>
                    </then>
                </if>
            </else>
        </if>
        
        <!-- 加载实体数据 -->
        <if condition="entityId">
            <then>
                <entity-find-one entity-name="MyEntity" value-field="entity" for-update="true">
                    <field-map field-name="entityId"/>
                </entity-find-one>
                <if condition="!entity">
                    <then>
                        <message error="true">实体不存在 (ID: ${entityId})</message>
                        <script>ec.web.sendRedirect('../FindEntity')</script>
                        <return/>
                    </then>
                </if>
                <set field="pageTitle" value="编辑实体 - ${entity.entityName}"/>
            </then>
            <else>
                <set field="pageTitle" value="新增实体"/>
                <!-- 设置默认值 -->
                <set field="entity" from="[:]"/>
                <set field="entity.statusId" value="MyEntityActive"/>
                <set field="entity.createdDate" from="ec.user.nowTimestamp"/>
            </else>
        </if>
        
        <!-- 加载选项数据 -->
        <entity-find entity-name="StatusItem" list="statusList" cache="true">
            <econdition field-name="statusTypeId" value="MyEntityStatus"/>
            <order-by field-name="sequenceNum"/>
        </entity-find>
        
        <entity-find entity-name="MyEntityType" list="entityTypeList" cache="true">
            <order-by field-name="description"/>
        </entity-find>
    </actions>

    <!-- 页面内容 -->
    <widgets>
        <!-- 面包屑导航 -->
        <container-row>
            <row-col lg="12">
                <container class="breadcrumb-container">
                    <link url="../FindEntity" text="实体列表"/>
                    <label text=" > " encode="false"/>
                    <label text="${pageTitle}" style="font-weight: bold;"/>
                </container>
            </row-col>
        </container-row>

        <!-- 主表单 -->
        <container-row>
            <row-col lg="12">
                <container-box>
                    <box-header title="${pageTitle}"/>
                    <box-body>
                        <form-single name="EditEntityForm" 
                                    transition="${entityId ? 'updateEntity' : 'createEntity'}"
                                    map="entity" focus-field="entityName">
                            
                            <!-- 隐藏字段 -->
                            <field name="entityId">
                                <default-field><hidden/></default-field>
                            </field>
                            
                            <!-- 表单布局 -->
                            <field-layout>
                                <!-- 基本信息 -->
                                <field-row>
                                    <field-ref name="entityName"/>
                                    <field-ref name="entityTypeId"/>
                                </field-row>
                                
                                <field-row>
                                    <field-ref name="statusId"/>
                                    <field-ref name="priority"/>
                                </field-row>
                                
                                <!-- 描述信息 -->
                                <field-row>
                                    <field-ref name="description"/>
                                </field-row>
                                
                                <!-- 时间信息 -->
                                <field-row>
                                    <field-ref name="effectiveDate"/>
                                    <field-ref name="expirationDate"/>
                                </field-row>
                                
                                <!-- 按钮 -->
                                <field-row>
                                    <field-ref name="submitButton"/>
                                    <field-ref name="cancelButton"/>
                                </field-row>
                            </field-layout>

                            <!-- 实体名称 -->
                            <field name="entityName">
                                <default-field title="实体名称" tooltip="实体的唯一标识名称">
                                    <text-line size="40" maxlength="100" required="true"
                                              ac-transition="validateEntityName" ac-min-length="2"/>
                                </default-field>
                            </field>

                            <!-- 实体类型 -->
                            <field name="entityTypeId">
                                <default-field title="实体类型">
                                    <drop-down required="true">
                                        <option key="" text="请选择实体类型"/>
                                        <list-options list="entityTypeList" 
                                                     key="${entityTypeId}" text="${description}"/>
                                    </drop-down>
                                </default-field>
                            </field>

                            <!-- 状态 -->
                            <field name="statusId">
                                <default-field title="状态">
                                    <drop-down required="true">
                                        <list-options list="statusList" 
                                                     key="${statusId}" text="${description}"/>
                                    </drop-down>
                                </default-field>
                            </field>

                            <!-- 优先级 -->
                            <field name="priority">
                                <default-field title="优先级 (1-10)">
                                    <range-find min="1" max="10" step="1"/>
                                </default-field>
                            </field>

                            <!-- 描述 -->
                            <field name="description">
                                <default-field title="描述">
                                    <text-area rows="4" cols="60" maxlength="1000"/>
                                </default-field>
                            </field>

                            <!-- 生效日期 -->
                            <field name="effectiveDate">
                                <default-field title="生效日期">
                                    <date-time type="date" required="true"/>
                                </default-field>
                            </field>

                            <!-- 失效日期 -->
                            <field name="expirationDate">
                                <default-field title="失效日期">
                                    <date-time type="date"/>
                                    <field-validation>
                                        <compare operator="greater" to-field="effectiveDate"/>
                                    </field-validation>
                                </default-field>
                            </field>

                            <!-- 提交按钮 -->
                            <field name="submitButton">
                                <default-field title="">
                                    <submit text="${entityId ? '更新实体' : '创建实体'}" 
                                           btn-type="success" icon="fa fa-save"/>
                                </default-field>
                            </field>

                            <!-- 取消按钮 -->
                            <field name="cancelButton">
                                <default-field title="">
                                    <link url="${entityId ? '../EntityDetail' : '../FindEntity'}" 
                                          text="取消" btn-type="default"
                                          parameter-map="[entityId:entityId]"/>
                                </default-field>
                            </field>
                        </form-single>
                    </box-body>
                </container-box>
            </row-col>
        </container-row>

        <!-- 客户端脚本增强 -->
        <render-mode>
            <text type="html"><![CDATA[
                <script>
                $(document).ready(function() {
                    // 实体名称验证
                    $('#EditEntityForm_entityName').on('blur', function() {
                        var entityName = $(this).val();
                        var entityId = $('#EditEntityForm_entityId').val();
                        
                        if (entityName.length >= 2) {
                            $.ajax({
                                url: 'validateEntityName',
                                data: { entityName: entityName, entityId: entityId },
                                success: function(result) {
                                    if (!result.isValid) {
                                        $('#EditEntityForm_entityName').addClass('error');
                                        alert('实体名称已存在，请使用其他名称');
                                    } else {
                                        $('#EditEntityForm_entityName').removeClass('error');
                                    }
                                }
                            });
                        }
                    });
                    
                    // 日期验证
                    $('#EditEntityForm_effectiveDate, #EditEntityForm_expirationDate').change(function() {
                        var effectiveDate = new Date($('#EditEntityForm_effectiveDate').val());
                        var expirationDate = new Date($('#EditEntityForm_expirationDate').val());
                        
                        if (expirationDate && expirationDate <= effectiveDate) {
                            $('#EditEntityForm_expirationDate').addClass('error');
                            alert('失效日期必须晚于生效日期');
                        } else {
                            $('#EditEntityForm_expirationDate').removeClass('error');
                        }
                    });
                });
                </script>
            ]]></text>
        </render-mode>
    </widgets>
</screen>
```

### 6. 标准详情页模板 (EntityDetail.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        require-authentication="true">

    <parameter name="entityId" required="true"/>
    
    <!-- Tab页面定义 -->
    <subscreens default-item="Summary">
        <subscreens-item name="Summary" menu-title="概况" menu-index="1"/>
        <subscreens-item name="Details" menu-title="详细信息" menu-index="2"/>
        <subscreens-item name="History" menu-title="历史记录" menu-index="3"/>
        <subscreens-item name="Related" menu-title="相关信息" menu-index="4"/>
        <subscreens-item name="Settings" menu-title="设置" menu-index="5" menu-include="N">
            <condition>
                <if-has-permission permission="MY_ENTITY_ADMIN"/>
            </condition>
        </subscreens-item>
    </subscreens>
    
    <!-- 页面转换 -->
    <transition name="updateEntity">
        <service-call name="my.component.MyServices.update#Entity"/>
        <default-response url="."/>
    </transition>

    <!-- 数据加载和权限检查 -->
    <actions>
        <!-- 加载实体详情 -->
        <entity-find-one entity-name="MyEntityDetail" value-field="entity">
            <field-map field-name="entityId"/>
        </entity-find-one>
        
        <if condition="!entity">
            <then>
                <message error="true">实体不存在 (ID: ${entityId})</message>
                <script>ec.web.sendRedirect('../FindEntity')</script>
                <return/>
            </then>
        </if>
        
        <!-- 权限检查 -->
        <set field="hasViewPermission" from="ec.user.hasPermission('MY_ENTITY_VIEW')"/>
        <set field="hasUpdatePermission" from="ec.user.hasPermission('MY_ENTITY_UPDATE')"/>
        <set field="hasDeletePermission" from="ec.user.hasPermission('MY_ENTITY_DELETE')"/>
        
        <if condition="!hasViewPermission">
            <then>
                <message error="true">您没有权限查看此实体</message>
                <script>ec.web.sendRedirect('../FindEntity')</script>
                <return/>
            </then>
        </if>
        
        <!-- 加载统计数据 -->
        <service-call name="my.component.MyServices.get#EntityStats" out-map="entityStats">
            <field-map field-name="entityId"/>
        </service-call>
    </actions>

    <!-- 页面内容 -->
    <widgets>
        <!-- 面包屑导航 -->
        <container-row>
            <row-col lg="12">
                <container class="breadcrumb-container">
                    <link url="../FindEntity" text="实体列表"/>
                    <label text=" > " encode="false"/>
                    <label text="${entity.entityName}" style="font-weight: bold;"/>
                </container>
            </row-col>
        </container-row>

        <!-- 实体头部信息卡片 -->
        <container-row>
            <row-col lg="12">
                <container-box>
                    <box-header title="${entity.entityName}" icon="fa fa-cube">
                        <!-- 状态徽章 -->
                        <label text="${entity.statusDescription}" 
                               style="background-color: ${groovy: 
                                   entity.statusId == 'MyEntityActive' ? '#28a745' : 
                                   entity.statusId == 'MyEntityInactive' ? '#dc3545' : '#ffc107'
                               }; color: white; padding: 2px 8px; border-radius: 3px; font-size: 0.8em;"/>
                    </box-header>
                    
                    <box-toolbar>
                        <!-- 操作按钮 -->
                        <section name="UpdateButton" condition="hasUpdatePermission">
                            <widgets>
                                <link url="../EditEntity" text="编辑实体" btn-type="primary"
                                      parameter-map="[entityId:entityId]" icon="fa fa-edit"/>
                            </widgets>
                        </section>
                        
                        <link url="exportEntity" text="导出详情" btn-type="info"
                              parameter-map="[entityId:entityId]" icon="fa fa-download"/>
                              
                        <section name="DeleteButton" condition="hasDeletePermission">
                            <widgets>
                                <link url="deleteEntity" text="删除实体" btn-type="danger"
                                      parameter-map="[entityId:entityId]" icon="fa fa-trash"
                                      confirmation="确定要删除实体 '${entity.entityName}' 吗？"/>
                            </widgets>
                        </section>
                    </box-toolbar>
                    
                    <box-body>
                        <!-- 基本信息网格 -->
                        <container-row>
                            <!-- 左侧：基本信息 -->
                            <row-col lg="8">
                                <container-row>
                                    <row-col md="6">
                                        <label text="实体类型：" type="strong"/>
                                        <label text="${entity.entityTypeDescription}"/>
                                    </row-col>
                                    <row-col md="6">
                                        <label text="优先级：" type="strong"/>
                                        <label text="${entity.priority ?: 'N/A'}"/>
                                    </row-col>
                                </container-row>
                                
                                <container-row>
                                    <row-col md="6">
                                        <label text="生效日期：" type="strong"/>
                                        <label text="${ec.l10n.format(entity.effectiveDate, 'yyyy-MM-dd')}"/>
                                    </row-col>
                                    <row-col md="6">
                                        <label text="失效日期：" type="strong"/>
                                        <label text="${ec.l10n.format(entity.expirationDate, 'yyyy-MM-dd') ?: '无限期'}"/>
                                    </row-col>
                                </container-row>
                                
                                <container-row>
                                    <row-col md="12">
                                        <label text="描述：" type="strong"/>
                                        <label text="${entity.description ?: '无描述'}"/>
                                    </row-col>
                                </container-row>
                            </row-col>
                            
                            <!-- 右侧：统计信息 -->
                            <row-col lg="4">
                                <container-box type="info">
                                    <box-header title="统计信息"/>
                                    <box-body>
                                        <container-row>
                                            <row-col xs="6">
                                                <label text="关联数量" type="small"/>
                                                <label text="${entityStats.relatedCount ?: 0}" type="h4"/>
                                            </row-col>
                                            <row-col xs="6">
                                                <label text="访问次数" type="small"/>
                                                <label text="${entityStats.accessCount ?: 0}" type="h4"/>
                                            </row-col>
                                        </container-row>
                                        
                                        <container-row>
                                            <row-col xs="12">
                                                <label text="最后更新" type="small"/>
                                                <label text="${ec.l10n.format(entity.lastUpdatedStamp, 'MM-dd HH:mm')}" type="p"/>
                                            </row-col>
                                        </container-row>
                                    </box-body>
                                </container-box>
                            </row-col>
                        </container-row>
                    </box-body>
                </container-box>
            </row-col>
        </container-row>

        <!-- Tab页面容器 -->
        <container-row>
            <row-col lg="12">
                <subscreens-panel id="entity-detail-tabs" type="tab" 
                                dynamic-active="true" active-sub-menu="Summary"/>
            </row-col>
        </container-row>
        
        <!-- 返回按钮 -->
        <container-row>
            <row-col lg="12" style="text-align: center; margin-top: 20px;">
                <link url="../FindEntity" text="返回实体列表" btn-type="default" 
                      icon="fa fa-arrow-left"/>
            </row-col>
        </container-row>
    </widgets>
</screen>
```

### 7. 调试工具模板

```xml
<!-- DebugTool.xml - 专用调试页面模板 -->
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        require-authentication="true"
        standalone="true">

    <actions>
        <script><![CDATA[
            // 收集所有调试信息
            def debugInfo = [:]
            
            // 1. 组件信息
            def componentRegistry = ec.ecfi.componentRegistry
            debugInfo.components = [:]
            componentRegistry.getAllComponents().each { name, info ->
                debugInfo.components[name] = [
                    location: info.location,
                    loadState: info.loadState?.toString(),
                    version: info.componentNode?.attribute('version')
                ]
            }
            
            // 2. 屏幕信息
            debugInfo.screens = [:]
            def checkScreen = { location ->
                try {
                    def screen = ec.ecfi.getScreenFacade().getScreenDefinition(location)
                    return [
                        exists: true,
                        subscreens: screen.getSubscreensDefList().size(),
                        menuTitle: screen.getDefaultMenuName(),
                        menuInclude: screen.getDefaultMenuInclude()
                    ]
                } catch (Exception e) {
                    return [exists: false, error: e.message]
                }
            }
            
            debugInfo.screens['webroot'] = checkScreen('webroot')
            debugInfo.screens['apps'] = checkScreen('component://webroot/screen/webroot/apps.xml')
            debugInfo.screens['myapp'] = checkScreen('component://my-component/screen/MyApp/apps.xml')
            
            // 3. 用户信息
            debugInfo.user = [
                userId: ec.user.userId,
                username: ec.user.username,
                groups: ec.user.userGroupIdSet.join(', '),
                permissions: ec.user.userPermissions.keySet().take(10).join(', ')
            ]
            
            // 4. 系统信息
            debugInfo.system = [
                moquiVersion: ec.ecfi.getComponentBaseLocation('moqui-framework'),
                javaVersion: System.getProperty('java.version'),
                timestamp: ec.user.nowTimestamp.toString()
            ]
            
            context.debugInfo = debugInfo
        ]]></script>
    </actions>

    <widgets>
        <container-box>
            <box-header title="Moqui 调试信息面板"/>
            <box-body>
                <container-row>
                    <!-- 组件状态 -->
                    <row-col lg="6">
                        <container-box type="info">
                            <box-header title="组件状态"/>
                            <box-body>
                                <iterate list="debugInfo.components" entry="comp" key="name">
                                    <container style="margin-bottom: 10px; padding: 5px; border-left: 3px solid #007bff;">
                                        <label text="${name}" type="strong"/>
                                        <label text="位置: ${comp.location}" type="small"/>
                                        <label text="状态: ${comp.loadState}" type="small"/>
                                        <label text="版本: ${comp.version ?: 'N/A'}" type="small"/>
                                    </container>
                                </iterate>
                            </box-body>
                        </container-box>
                    </row-col>
                    
                    <!-- 屏幕状态 -->
                    <row-col lg="6">
                        <container-box type="warning">
                            <box-header title="屏幕状态"/>
                            <box-body>
                                <iterate list="debugInfo.screens" entry="screen" key="name">
                                    <container style="margin-bottom: 10px;">
                                        <label text="${name}" type="strong"/>
                                        <section name="screenExists" condition="screen.exists">
                                            <widgets>
                                                <label text="✅ 存在" style="color: green;"/>
                                                <label text="子屏幕: ${screen.subscreens}" type="small"/>
                                                <label text="菜单: ${screen.menuTitle ?: 'N/A'}" type="small"/>
                                            </widgets>
                                        </section>
                                        <section name="screenNotExists" condition="!screen.exists">
                                            <widgets>
                                                <label text="❌ 不存在" style="color: red;"/>
                                                <label text="错误: ${screen.error}" type="small" style="color: red;"/>
                                            </widgets>
                                        </section>
                                    </container>
                                </iterate>
                            </box-body>
                        </container-box>
                    </row-col>
                </container-row>
                
                <container-row>
                    <!-- 用户信息 -->
                    <row-col lg="6">
                        <container-box type="success">
                            <box-header title="用户信息"/>
                            <box-body>
                                <label text="用户ID: ${debugInfo.user.userId}" type="p"/>
                                <label text="用户名: ${debugInfo.user.username}" type="p"/>
                                <label text="用户组: ${debugInfo.user.groups}" type="p"/>
                                <label text="部分权限: ${debugInfo.user.permissions}" type="small"/>
                            </box-body>
                        </container-box>
                    </row-col>
                    
                    <!-- 系统信息 -->
                    <row-col lg="6">
                        <container-box>
                            <box-header title="系统信息"/>
                            <box-body>
                                <label text="Java版本: ${debugInfo.system.javaVersion}" type="p"/>
                                <label text="检查时间: ${debugInfo.system.timestamp}" type="p"/>
                            </box-body>
                        </container-box>
                    </row-col>
                </container-row>
            </box-body>
        </container-box>
    </widgets>
</screen>
```

## 开发流程规范

### 1. 组件创建检查清单
- [ ] 创建标准目录结构
- [ ] 配置 MoquiConf.xml
- [ ] 创建基础实体定义
- [ ] 创建基础服务定义
- [ ] 创建 apps.xml 入口
- [ ] 创建标准的 Find/Edit/Detail 页面
- [ ] 配置权限和安全
- [ ] 创建调试页面
- [ ] 测试菜单显示
- [ ] 测试基本功能

### 2. 问题排查步骤
1. 检查组件是否正确加载
2. 检查屏幕路径是否正确
3. 检查菜单配置是否正确
4. 检查权限是否配置
5. 使用调试页面获取详细信息
6. 查看服务器日志

### 3. 开发最佳实践
- 使用标准命名约定
- 始终包含权限检查
- 提供友好的错误处理
- 添加适当的日志记录
- 使用事务和验证
- 提供完整的测试数据

这套模板系统解决了您提到的两个核心问题：提供了系统化的调试方法和标准化的开发模板。开发者可以# Moqui 组件开发标准模板集

## 问题现状分析

您准确指出了 Moqui 框架当前面临的两大核心问题:
1. **调试工具不足**: 问题定位困难，错误信息不够直观
2. **开发模板缺失**: 依赖个人经验，缺乏标准化开发指南

## 解决方案：标准化组件模板

### 1. 基础组件结构模板

```
标准组件目录结构:
my-component/
├── MoquiConf.xml                    # 组件配置 [必需]
├── component.xml                    # 组件元数据 [推荐]
├── data/                           # 初始数据
│   ├── MyComponentTypeData.xml     # 基础类型数据
│   ├── MyComponentSecurityData.xml # 权限数据
│   └── MyComponentDemoData.xml     # 演示数据
├── entity/                         # 实体定义
│   └── MyComponentEntities.xml
├── screen/                         # 页面定义 [核心]
│   ├── MyApp/                     # 应用主目录
│   │   ├── apps.xml               # 应用入口 [必需]
│   │   ├── dashboard.xml          # 仪表板
│   │   ├── Entity1/               # 实体1管理
│   │   │   ├── FindEntity1.xml    # 列表页 [标准]
│   │   │   ├── EditEntity1.xml    # 编辑页 [标准]
│   │   │   └── Entity1Detail.xml  # 详情页 [标准]
│   │   ├── Entity2/               # 实体2管理
│   │   └── includes/              # 公共组件
│   │       ├── CommonForms.xml    # 公共表单
│   │       └── CommonWidgets.xml  # 公共控件
│   └── webroot.xml                # Web根 [可选]
├── service/                        # 服务定义
│   └── MyComponentServices.xml
├── template/                       # 模板文件
│   ├── email/                     # 邮件模板
│   └── report/                    # 报表模板
├── webapp/                         # 静态资源
│   ├── css/
│   ├── js/
│   └── images/
└── README.md                       # 组件说明
```

### 2. 组件配置模板 (MoquiConf.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<moqui-conf xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
            xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/moqui-conf-3.xsd">

    <!-- 组件基本信息 -->
    <component name="my-component" version="1.0.0"/>
    
    <!-- 依赖组件 -->
    <component-list>
        <component name="moqui-framework"/>
        <component name="webroot"/>
        <!-- 其他依赖 -->
    </component-list>
    
    <!-- 实体定义加载 -->
    <entity-facade>
        <datasource group-name="transactional">
            <inline-other entity-group="my-component" 
                         entities-location="component://my-component/entity/MyComponentEntities.xml"/>
        </datasource>
    </entity-facade>
    
    <!-- 服务定义加载 -->
    <service-facade>
        <service-location location="component://my-component/service/MyComponentServices.xml"/>
    </service-facade>
    
    <!-- 屏幕路径映射 -->
    <screen-facade>
        <screen-location location="component://my-component/screen"/>
    </screen-facade>
    
    <!-- 安全配置 -->
    <user-facade>
        <password min-length="8" history-limit="5"/>
    </user-facade>
    
    <!-- Web应用配置 -->
    <webapp-list>
        <webapp name="webroot">
            <root-screen location="component://my-component/screen/webroot.xml"/>
            <!-- 静态资源映射 -->
            <webapp-resource location="component://my-component/webapp" url-path="/static/my-component"/>
        </webapp>
    </webapp-list>

</moqui-conf>
```

### 3. 应用入口模板 (apps.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="我的应用"
        default-menu-index="50"
        default-menu-include="true"
        require-authentication="true">

    <!-- 子屏幕定义 - 按菜单顺序 -->
    <subscreens default-item="dashboard">
        <!-- 仪表板 -->
        <subscreens-item name="dashboard" location="dashboard.xml" 
                        menu-title="仪表板" menu-index="1" menu-include="true"/>
                        
        <!-- 核心功能模块 -->
        <subscreens-item name="Entity1" location="Entity1" 
                        menu-title="实体1管理" menu-index="10" menu-include="true"/>
        <subscreens-item name="Entity2" location="Entity2" 
                        menu-title="实体2管理" menu-index="20" menu-include="true"/>
        
        <!-- 系统管理 (管理员权限) -->
        <subscreens-item name="admin" location="admin" 
                        menu-title="系统管理" menu-index="90" menu-include="N">
            <condition>
                <if-has-permission permission="MY_COMPONENT_ADMIN"/>
            </condition>
        </subscreens-item>
        
        <!-- 帮助和关于 -->
        <subscreens-item name="help" location="help.xml" 
                        menu-title="帮助" menu-index="99" menu-include="true"/>
    </subscreens>

    <!-- 页面级权限控制 -->
    <pre-actions>
        <!-- 检查基本访问权限 -->
        <if condition="!ec.user.hasPermission('MY_COMPONENT_VIEW')">
            <then>
                <message error="true">您没有权限访问此应用</message>
                <script>ec.web.sendRedirect('/Login')</script>
                <return/>
            </then>
        </if>
    </pre-actions>

    <!-- 页面内容 -->
    <widgets>
        <!-- 应用标题和描述 -->
        <section name="AppHeader" condition="ec.web.requestParameters._isRootPath">
            <widgets>
                <container-row>
                    <row-col lg="12">
                        <container class="app-header">
                            <label text="我的应用" type="h1"/>
                            <label text="这是一个标准的Moqui应用组件示例" type="p"/>
                        </container>
                    </row-col>
                </container-row>
            </widgets>
        </section>
        
        <!-- 子屏幕内容 -->
        <subscreens-active/>
    </widgets>
</screen>
```

### 4. 标准列表页模板 (FindEntity.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="实体列表"
        require-authentication="true">

    <!-- 页面转换定义 -->
    <transition name="editEntity">
        <default-response url="EditEntity"/>
    </transition>
    
    <transition name="entityDetail">
        <default-response url="EntityDetail"/>
    </transition>
    
    <transition name="deleteEntity">
        <service-call name="my.component.MyServices.delete#Entity"/>
        <default-response url="."/>
    </transition>
    
    <transition name="exportEntities">
        <default-response url="." url-type="plain">
            <parameter name="renderMode" value="csv"/>
            <parameter name="pageNoLimit" value="true"/>
        </default-response>
    </transition>

    <!-- 数据加载和权限检查 -->
    <actions>
        <!-- 权限检查 -->
        <set field="hasCreatePermission" from="ec.user.hasPermission('MY_ENTITY_CREATE')"/>
        <set field="hasUpdatePermission" from="ec.user.hasPermission('MY_ENTITY_UPDATE')"/>
        <set field="hasDeletePermission" from="ec.user.hasPermission('MY_ENTITY_DELETE')"/>
        
        <!-- 搜索参数处理 -->
        <set field="entityName" from="entityName ?: ''"/>
        <set field="statusId" from="statusId ?: ''"/>
        <set field="fromDate" from="fromDate ?: null"/>
        <set field="thruDate" from="thruDate ?: null"/>
        
        <!-- 加载选项数据 -->
        <entity-find entity-name="StatusItem" list="statusList" cache="true">
            <econdition field-name="statusTypeId" value="MyEntityStatus"/>
            <order-by field-name="sequenceNum"/>
        </entity-find>
    </actions>

    <!-- 页面内容 -->
    <widgets>
        <container-box>
            <box-header title="实体管理" icon="fa fa-list"/>
            
            <!-- 工具栏 -->
            <box-toolbar>
                <!-- 新增按钮 -->
                <section name="CreateButton" condition="hasCreatePermission">
                    <widgets>
                        <link url="editEntity" text="新增实体" btn-type="success" 
                              icon="fa fa-plus"/>
                    </widgets>
                </section>
                
                <!-- 导出按钮 -->
                <link url="exportEntities" text="导出Excel" btn-type="info" 
                      icon="fa fa-download"/>
                      
                <!-- 批量操作 -->
                <container-dialog id="BatchOperationDialog" button-text="批量操作" 
                                btn-type="warning">
                    <!-- 批量操作表单 -->
                    <form-single name="BatchOperationForm" transition="batchUpdate">
                        <field name="entityIds"><default-field><hidden/></default-field></field>
                        <field name="operation">
                            <default-field title="操作">
                                <radio>
                                    <option key="activate" text="激活"/>
                                    <option key="deactivate" text="停用"/>
                                    <option key="delete" text="删除"/>
                                </radio>
                            </default-field>
                        </field>
                        <field name="submitButton">
                            <default-field><submit text="执行"/></default-field>
                        </field>
                    </form-single>
                </container-dialog>
            </box-toolbar>

            <box-body>
                <!-- 搜索表单 -->
                <form-single name="SearchForm" transition="." 
                           extends="component://my-component/screen/MyApp/includes/CommonSearchForm.xml#SearchFormBase">
                    <field name="entityName">
                        <default-field title="实体名称">
                            <text-line size="30" ac-transition="suggestEntityNames"/>
                        </default-field>
                    </field>
                    <field name="statusId">
                        <default-field title="状态">
                            <drop-down allow-empty="true">
                                <list-options list="statusList" key="${statusId}" text="${description}"/>
                            </drop-down>
                        </default-field>
                    </field>
                    <field name="dateRange">
                        <default-field title="创建时间">
                            <date-period/>
                        </default-field>
                    </field>
                    <field name="submitButton">
                        <default-field><submit text="搜索" icon="fa fa-search"/></default-field>
                    </field>
                    <field name="resetButton">
                        <default-field><link url="." text="重置"/></default-field>
                    </field>
                </form-single>

                <!-- 数据列表 -->
                <form-list name="EntityList" list="entityList" 
                          skip-form="true" select-columns="true" saved-finds="true"
                          paginate="true">
                    
                   <!-- 数据查询 -->
                    <entity-find entity-name="MyEntity" list="entityList">
                        <search-form-inputs default-order-by="-createdDate"/>
                        <econdition field-name="entityName" operator="like" 
                                   value="%${entityName}%" ignore-if-empty="true"/>
                        <econdition field-name="statusId" ignore-if-empty="true"/>
                        <date-filter from-field-name="fromDate" thru-field-name="thruDate"/>
                        <select-field field-name="entityId,entityName,statusId,createdDate"/>
                    </entity-find>

                    <!-- 批量选择 -->
                    <field name="entityId">
                        <header-field title="选择" show-order-by="false">
                            <check type="all-checkbox"/>
                        </header-field>
                        <default-field>
                            <check all-checked="false">
                                <option key="${entityId}" text=""/>
                            </check>
                        </default-field>
                    </field>

                    <!-- 实体名称 - 链接到详情页 -->
                    <field name="entityName">
                        <header-field title="实体名称" show-order-by="true"/>
                        <default-field>
                            <link url="entityDetail" text="${entityName}" 
                                  parameter-map="[entityId:entityId]"/>
                        </default-field>
                    </field>

                    <!-- 状态显示 -->
                    <field name="statusId">
                        <header-field title="状态" show-order-by="true"/>
                        <default-field>
                            <display-entity entity-name="StatusItem"/>
                        </default-field>
                    </field>

                    <!-- 创建时间 -->
                    <field name="createdDate">
                        <header-field title="创建时间" show-order-by="true"/>
                        <default-field>
                            <display format="yyyy-MM-dd HH:mm"/>
                        </default-field>
                    </field>

                    <!-- 操作列 -->
                    <field name="actions">
                        <header-field title="操作"/>
                        <default-field>
                            <container>
                                <!-- 查看详情 -->
                                <link url="entityDetail" text="详情" btn-type="info" btn-size="xs"
                                      parameter-map="[entityId:entityId]" icon="fa fa-eye"/>
                                      
                                <!-- 编辑 -->
                                <section name="EditAction" condition="hasUpdatePermission">
                                    <widgets>
                                        <link url="editEntity" text="编辑" btn-type="primary" btn-size="xs"
                                              parameter-map="[entityId:entityId]" icon="fa fa-edit"/>
                                    </widgets>
                                </section>
                                
                                <!-- 删除 -->
                                <section name="DeleteAction" condition="hasDeletePermission">
                                    <widgets>
                                        <link url="deleteEntity" text="删除" btn-type="danger" btn-size="xs"
                                              parameter-map="[entityId:entityId]" icon="fa fa-trash"
                                              confirmation="确定要删除这个实体吗？"/>
                                    </widgets>
                                </section>
                            </container>
                        </default-field>
                    </field>
                </form-list>
            </box-body>
        </container-box>
    </widgets>
</screen>
```

### 5. 标准编辑页模板 (EditEntity.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        require-authentication="true">

    <parameter name="entityId"/>
    
    <!-- 表单转换 -->
    <transition name="createEntity">
        <service-call name="my.component.MyServices.create#Entity"/>
        <default-response url="../EntityDetail" parameter-map="[entityId:entityId]"/>
    </transition>
    
    <transition name="updateEntity">
        <service-call name="my.component.MyServices.update#Entity"/>
        <default-response url="../EntityDetail" parameter-map="[entityId:entityId]"/>
    </transition>
    
    <transition name="validateEntityName">
        <service-call name="my.component.MyServices.validate#EntityName"/>
        <default-response type="json-object"/>
    </transition>

    <!-- 数据加载和权限检查 -->
    <actions>
        <!-- 权限检查 -->
        <if condition="entityId">
            <then>
                <if condition="!ec.user.hasPermission('MY_ENTITY_UPDATE')">
                    <then>
                        <message error="true">您没有权限编辑此实体</message>
                        <script>ec.web.sendRedirect('../FindEntity')</script>
                        <return/>
                    </then>
                </if>
            </then>
            <else>
                <if condition="!ec.user.hasPermission('MY_ENTITY_CREATE')">
                    <then>
                        <message error="true">您没有权限创建新实体</message>
                        <script>ec.web.sendRedirect('../FindEntity')</script>
                        <return/>
                    </then>
                </if>
            </else>
        </if>
        
        <!-- 加载实体数据 -->
        <if condition="entityId">
            <then>
                <entity-find-one entity-name="MyEntity" value-field="entity" for-update="true">
                    <field-map field-name="entityId"/>
                </entity-find-one>
                <if condition="!entity">
                    <then>
                        <message error="true">实体不存在 (ID: ${entityId})</message>
                        <script>ec.web.sendRedirect('../FindEntity')</script>
                        <return/>
                    </then>
                </if>
                <set field="pageTitle" value="编辑实体 - ${entity.entityName}"/>
            </then>
            <else>
                <set field="pageTitle" value="新增实体"/>
                <!-- 设置默认值 -->
                <set field="entity" from="[:]"/>
                <set field="entity.statusId" value="MyEntityActive"/>
                <set field="entity.createdDate" from="ec.user.nowTimestamp"/>
            </else>
        </if>
        
        <!-- 加载选项数据 -->
        <entity-find entity-name="StatusItem" list="statusList" cache="true">
            <econdition field-name="statusTypeId" value="MyEntityStatus"/>
            <order-by field-name="sequenceNum"/>
        </entity-find>
        
        <entity-find entity-name="MyEntityType" list="entityTypeList" cache="true">
            <order-by field-name="description"/>
        </entity-find>
    </actions>

    <!-- 页面内容 -->
    <widgets>
        <!-- 面包屑导航 -->
        <container-row>
            <row-col lg="12">
                <container class="breadcrumb-container">
                    <link url="../FindEntity" text="实体列表"/>
                    <label text=" > " encode="false"/>
                    <label text="${pageTitle}" style="font-weight: bold;"/>
                </container>
            </row-col>
        </container-row>

        <!-- 主表单 -->
        <container-row>
            <row-col lg="12">
                <container-box>
                    <box-header title="${pageTitle}"/>
                    <box-body>
                        <form-single name="EditEntityForm" 
                                    transition="${entityId ? 'updateEntity' : 'createEntity'}"
                                    map="entity" focus-field="entityName">
                            
                            <!-- 隐藏字段 -->
                            <field name="entityId">
                                <default-field><hidden/></default-field>
                            </field>
                            
                            <!-- 表单布局 -->
                            <field-layout>
                                <!-- 基本信息 -->
                                <field-row>
                                    <field-ref name="entityName"/>
                                    <field-ref name="entityTypeId"/>
                                </field-row>
                                
                                <field-row>
                                    <field-ref name="statusId"/>
                                    <field-ref name="priority"/>
                                </field-row>
                                
                                <!-- 描述信息 -->
                                <field-row>
                                    <field-ref name="description"/>
                                </field-row>
                                
                                <!-- 时间信息 -->
                                <field-row>
                                    <field-ref name="effectiveDate"/>
                                    <field-ref name="expirationDate"/>
                                </field-row>
                                
                                <!-- 按钮 -->
                                <field-row>
                                    <field-ref name="submitButton"/>
                                    <field-ref name="cancelButton"/>
                                </field-row>
                            </field-layout>

                            <!-- 实体名称 -->
                            <field name="entityName">
                                <default-field title="实体名称" tooltip="实体的唯一标识名称">
                                    <text-line size="40" maxlength="100" required="true"
                                              ac-transition="validateEntityName" ac-min-length="2"/>
                                </default-field>
                            </field>

                            <!-- 实体类型 -->
                            <field name="entityTypeId">
                                <default-field title="实体类型">
                                    <drop-down required="true">
                                        <option key="" text="请选择实体类型"/>
                                        <list-options list="entityTypeList" 
                                                     key="${entityTypeId}" text="${description}"/>
                                    </drop-down>
                                </default-field>
                            </field>

                            <!-- 状态 -->
                            <field name="statusId">
                                <default-field title="状态">
                                    <drop-down required="true">
                                        <list-options list="statusList" 
                                                     key="${statusId}" text="${description}"/>
                                    </drop-down>
                                </default-field>
                            </field>

                            <!-- 优先级 -->
                            <field name="priority">
                                <default-field title="优先级 (1-10)">
                                    <range-find min="1" max="10" step="1"/>
                                </default-field>
                            </field>

                            <!-- 描述 -->
                            <field name="description">
                                <default-field title="描述">
                                    <text-area rows="4" cols="60" maxlength="1000"/>
                                </default-field>
                            </field>

                            <!-- 生效日期 -->
                            <field name="effectiveDate">
                                <default-field title="生效日期">
                                    <date-time type="date" required="true"/>
                                </default-field>
                            </field>

                            <!-- 失效日期 -->
                            <field name="expirationDate">
                                <default-field title="失效日期">
                                    <date-time type="date"/>
                                    <field-validation>
                                        <compare operator="greater" to-field="effectiveDate"/>
                                    </field-validation>
                                </default-field>
                            </field>

                            <!-- 提交按钮 -->
                            <field name="submitButton">
                                <default-field title="">
                                    <submit text="${entityId ? '更新实体' : '创建实体'}" 
                                           btn-type="success" icon="fa fa-save"/>
                                </default-field>
                            </field>

                            <!-- 取消按钮 -->
                            <field name="cancelButton">
                                <default-field title="">
                                    <link url="${entityId ? '../EntityDetail' : '../FindEntity'}" 
                                          text="取消" btn-type="default"
                                          parameter-map="[entityId:entityId]"/>
                                </default-field>
                            </field>
                        </form-single>
                    </box-body>
                </container-box>
            </row-col>
        </container-row>

        <!-- 客户端脚本增强 -->
        <render-mode>
            <text type="html"><![CDATA[
                <script>
                $(document).ready(function() {
                    // 实体名称验证
                    $('#EditEntityForm_entityName').on('blur', function() {
                        var entityName = $(this).val();
                        var entityId = $('#EditEntityForm_entityId').val();
                        
                        if (entityName.length >= 2) {
                            $.ajax({
                                url: 'validateEntityName',
                                data: { entityName: entityName, entityId: entityId },
                                success: function(result) {
                                    if (!result.isValid) {
                                        $('#EditEntityForm_entityName').addClass('error');
                                        alert('实体名称已存在，请使用其他名称');
                                    } else {
                                        $('#EditEntityForm_entityName').removeClass('error');
                                    }
                                }
                            });
                        }
                    });
                    
                    // 日期验证
                    $('#EditEntityForm_effectiveDate, #EditEntityForm_expirationDate').change(function() {
                        var effectiveDate = new Date($('#EditEntityForm_effectiveDate').val());
                        var expirationDate = new Date($('#EditEntityForm_expirationDate').val());
                        
                        if (expirationDate && expirationDate <= effectiveDate) {
                            $('#EditEntityForm_expirationDate').addClass('error');
                            alert('失效日期必须晚于生效日期');
                        } else {
                            $('#EditEntityForm_expirationDate').removeClass('error');
                        }
                    });
                });
                </script>
            ]]></text>
        </render-mode>
    </widgets>
</screen>
```

### 6. 标准详情页模板 (EntityDetail.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        require-authentication="true">

    <parameter name="entityId" required="true"/>
    
    <!-- Tab页面定义 -->
    <subscreens default-item="Summary">
        <subscreens-item name="Summary" menu-title="概况" menu-index="1"/>
        <subscreens-item name="Details" menu-title="详细信息" menu-index="2"/>
        <subscreens-item name="History" menu-title="历史记录" menu-index="3"/>
        <subscreens-item name="Related" menu-title="相关信息" menu-index="4"/>
        <subscreens-item name="Settings" menu-title="设置" menu-index="5" menu-include="N">
            <condition>
                <if-has-permission permission="MY_ENTITY_ADMIN"/>
            </condition>
        </subscreens-item>
    </subscreens>
    
    <!-- 页面转换 -->
    <transition name="updateEntity">
        <service-call name="my.component.MyServices.update#Entity"/>
        <default-response url="."/>
    </transition>

    <!-- 数据加载和权限检查 -->
    <actions>
        <!-- 加载实体详情 -->
        <entity-find-one entity-name="MyEntityDetail" value-field="entity">
            <field-map field-name="entityId"/>
        </entity-find-one>
        
        <if condition="!entity">
            <then>
                <message error="true">实体不存在 (ID: ${entityId})</message>
                <script>ec.web.sendRedirect('../FindEntity')</script>
                <return/>
            </then>
        </if>
        
        <!-- 权限检查 -->
        <set field="hasViewPermission" from="ec.user.hasPermission('MY_ENTITY_VIEW')"/>
        <set field="hasUpdatePermission" from="ec.user.hasPermission('MY_ENTITY_UPDATE')"/>
        <set field="hasDeletePermission" from="ec.user.hasPermission('MY_ENTITY_DELETE')"/>
        
        <if condition="!hasViewPermission">
            <then>
                <message error="true">您没有权限查看此实体</message>
                <script>ec.web.sendRedirect('../FindEntity')</script>
                <return/>
            </then>
        </if>
        
        <!-- 加载统计数据 -->
        <service-call name="my.component.MyServices.get#EntityStats" out-map="entityStats">
            <field-map field-name="entityId"/>
        </service-call>
    </actions>

    <!-- 页面内容 -->
    <widgets>
        <!-- 面包屑导航 -->
        <container-row>
            <row-col lg="12">
                <container class="breadcrumb-container">
                    <link url="../FindEntity" text="实体列表"/>
                    <label text=" > " encode="false"/>
                    <label text="${entity.entityName}" style="font-weight: bold;"/>
                </container>
            </row-col>
        </container-row>

        <!-- 实体头部信息卡片 -->
        <container-row>
            <row-col lg="12">
                <container-box>
                    <box-header title="${entity.entityName}" icon="fa fa-cube">
                        <!-- 状态徽章 -->
                        <label text="${entity.statusDescription}" 
                               style="background-color: ${groovy: 
                                   entity.statusId == 'MyEntityActive' ? '#28a745' : 
                                   entity.statusId == 'MyEntityInactive' ? '#dc3545' : '#ffc107'
                               }; color: white; padding: 2px 8px; border-radius: 3px; font-size: 0.8em;"/>
                    </box-header>
                    
                    <box-toolbar>
                        <!-- 操作按钮 -->
                        <section name="UpdateButton" condition="hasUpdatePermission">
                            <widgets>
                                <link url="../EditEntity" text="编辑实体" btn-type="primary"
                                      parameter-map="[entityId:entityId]" icon="fa fa-edit"/>
                            </widgets>
                        </section>
                        
                        <link url="exportEntity" text="导出详情" btn-type="info"
                              parameter-map="[entityId:entityId]" icon="fa fa-download"/>
                              
                        <section name="DeleteButton" condition="hasDeletePermission">
                            <widgets>
                                <link url="deleteEntity" text="删除实体" btn-type="danger"
                                      parameter-map="[entityId:entityId]" icon="fa fa-trash"
                                      confirmation="确定要删除实体 '${entity.entityName}' 吗？"/>
                            </widgets>
                        </section>
                    </box-toolbar>
                    
                    <box-body>
                        <!-- 基本信息网格 -->
                        <container-row>
                            <!-- 左侧：基本信息 -->
                            <row-col lg="8">
                                <container-row>
                                    <row-col md="6">
                                        <label text="实体类型：" type="strong"/>
                                        <label text="${entity.entityTypeDescription}"/>
                                    </row-col>
                                    <row-col md="6">
                                        <label text="优先级：" type="strong"/>
                                        <label text="${entity.priority ?: 'N/A'}"/>
                                    </row-col>
                                </container-row>
                                
                                <container-row>
                                    <row-col md="6">
                                        <label text="生效日期：" type="strong"/>
                                        <label text="${ec.l10n.format(entity.effectiveDate, 'yyyy-MM-dd')}"/>
                                    </row-col>
                                    <row-col md="6">
                                        <label text="失效日期：" type="strong"/>
                                        <label text="${ec.l10n.format(entity.expirationDate, 'yyyy-MM-dd') ?: '无限期'}"/>
                                    </row-col>
                                </container-row>
                                
                                <container-row>
                                    <row-col md="12">
                                        <label text="描述：" type="strong"/>
                                        <label text="${entity.description ?: '无描述'}"/>
                                    </row-col>
                                </container-row>
                            </row-col>
                            
                            <!-- 右侧：统计信息 -->
                            <row-col lg="4">
                                <container-box type="info">
                                    <box-header title="统计信息"/>
                                    <box-body>
                                        <container-row>
                                            <row-col xs="6">
                                                <label text="关联数量" type="small"/>
                                                <label text="${entityStats.relatedCount ?: 0}" type="h4"/>
                                            </row-col>
                                            <row-col xs="6">
                                                <label text="访问次数" type="small"/>
                                                <label text="${entityStats.accessCount ?: 0}" type="h4"/>
                                            </row-col>
                                        </container-row>
                                        
                                        <container-row>
                                            <row-col xs="12">
                                                <label text="最后更新" type="small"/>
                                                <label text="${ec.l10n.format(entity.lastUpdatedStamp, 'MM-dd HH:mm')}" type="p"/>
                                            </row-col>
                                        </container-row>
                                    </box-body>
                                </container-box>
                            </row-col>
                        </container-row>
                    </box-body>
                </container-box>
            </row-col>
        </container-row>

        <!-- Tab页面容器 -->
        <container-row>
            <row-col lg="12">
                <subscreens-panel id="entity-detail-tabs" type="tab" 
                                dynamic-active="true" active-sub-menu="Summary"/>
            </row-col>
        </container-row>
        
        <!-- 返回按钮 -->
        <container-row>
            <row-col lg="12" style="text-align: center; margin-top: 20px;">
                <link url="../FindEntity" text="返回实体列表" btn-type="default" 
                      icon="fa fa-arrow-left"/>
            </row-col>
        </container-row>
    </widgets>
</screen>
```

### 7. 调试工具模板

```xml
<!-- DebugTool.xml - 专用调试页面模板 -->
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        require-authentication="true"
        standalone="true">

    <actions>
        <script><![CDATA[
            // 收集所有调试信息
            def debugInfo = [:]
            
            // 1. 组件信息
            def componentRegistry = ec.ecfi.componentRegistry
            debugInfo.components = [:]
            componentRegistry.getAllComponents().each { name, info ->
                debugInfo.components[name] = [
                    location: info.location,
                    loadState: info.loadState?.toString(),
                    version: info.componentNode?.attribute('version')
                ]
            }
            
            // 2. 屏幕信息
            debugInfo.screens = [:]
            def checkScreen = { location ->
                try {
                    def screen = ec.ecfi.getScreenFacade().getScreenDefinition(location)
                    return [
                        exists: true,
                        subscreens: screen.getSubscreensDefList().size(),
                        menuTitle: screen.getDefaultMenuName(),
                        menuInclude: screen.getDefaultMenuInclude()
                    ]
                } catch (Exception e) {
                    return [exists: false, error: e.message]
                }
            }
            
            debugInfo.screens['webroot'] = checkScreen('webroot')
            debugInfo.screens['apps'] = checkScreen('component://webroot/screen/webroot/apps.xml')
            debugInfo.screens['myapp'] = checkScreen('component://my-component/screen/MyApp/apps.xml')
            
            // 3. 用户信息
            debugInfo.user = [
                userId: ec.user.userId,
                username: ec.user.username,
                groups: ec.user.userGroupIdSet.join(', '),
                permissions: ec.user.userPermissions.keySet().take(10).join(', ')
            ]
            
            // 4. 系统信息
            debugInfo.system = [
                moquiVersion: ec.ecfi.getComponentBaseLocation('moqui-framework'),
                javaVersion: System.getProperty('java.version'),
                timestamp: ec.user.nowTimestamp.toString()
            ]
            
            context.debugInfo = debugInfo
        ]]></script>
    </actions>

    <widgets>
        <container-box>
            <box-header title="Moqui 调试信息面板"/>
            <box-body>
                <container-row>
                    <!-- 组件状态 -->
                    <row-col lg="6">
                        <container-box type="info">
                            <box-header title="组件状态"/>
                            <box-body>
                                <iterate list="debugInfo.components" entry="comp" key="name">
                                    <container style="margin-bottom: 10px; padding: 5px; border-left: 3px solid #007bff;">
                                        <label text="${name}" type="strong"/>
                                        <label text="位置: ${comp.location}" type="small"/>
                                        <label text="状态: ${comp.loadState}" type="small"/>
                                        <label text="版本: ${comp.version ?: 'N/A'}" type="small"/>
                                    </container>
                                </iterate>
                            </box-body>
                        </container-box>
                    </row-col>
                    
                    <!-- 屏幕状态 -->
                    <row-col lg="6">
                        <container-box type="warning">
                            <box-header title="屏幕状态"/>
                            <box-body>
                                <iterate list="debugInfo.screens" entry="screen" key="name">
                                    <container style="margin-bottom: 10px;">
                                        <label text="${name}" type="strong"/>
                                        <section name="screenExists" condition="screen.exists">
                                            <widgets>
                                                <label text="✅ 存在" style="color: green;"/>
                                                <label text="子屏幕: ${screen.subscreens}" type="small"/>
                                                <label text="菜单: ${screen.menuTitle ?: 'N/A'}" type="small"/>
                                            </widgets>
                                        </section>
                                        <section name="screenNotExists" condition="!screen.exists">
                                            <widgets>
                                                <label text="❌ 不存在" style="color: red;"/>
                                                <label text="错误: ${screen.error}" type="small" style="color: red;"/>
                                            </widgets>
                                        </section>
                                    </container>
                                </iterate>
                            </box-body>
                        </container-box>
                    </row-col>
                </container-row>
                
                <container-row>
                    <!-- 用户信息 -->
                    <row-col lg="6">
                        <container-box type="success">
                            <box-header title="用户信息"/>
                            <box-body>
                                <label text="用户ID: ${debugInfo.user.userId}" type="p"/>
                                <label text="用户名: ${debugInfo.user.username}" type="p"/>
                                <label text="用户组: ${debugInfo.user.groups}" type="p"/>
                                <label text="部分权限: ${debugInfo.user.permissions}" type="small"/>
                            </box-body>
                        </container-box>
                    </row-col>
                    
                    <!-- 系统信息 -->
                    <row-col lg="6">
                        <container-box>
                            <box-header title="系统信息"/>
                            <box-body>
                                <label text="Java版本: ${debugInfo.system.javaVersion}" type="p"/>
                                <label text="检查时间: ${debugInfo.system.timestamp}" type="p"/>
                            </box-body>
                        </container-box>
                    </row-col>
                </container-row>
            </box-body>
        </container-box>
    </widgets>
</screen>
```

## 开发流程规范

### 1. 组件创建检查清单
- [ ] 创建标准目录结构
- [ ] 配置 MoquiConf.xml
- [ ] 创建基础实体定义
- [ ] 创建基础服务定义
- [ ] 创建 apps.xml 入口
- [ ] 创建标准的 Find/Edit/Detail 页面
- [ ] 配置权限和安全
- [ ] 创建调试页面
- [ ] 测试菜单显示
- [ ] 测试基本功能

### 2. 问题排查步骤
1. 检查组件是否正确加载
2. 检查屏幕路径是否正确
3. 检查菜单配置是否正确
4. 检查权限是否配置
5. 使用调试页面获取详细信息
6. 查看服务器日志

### 3. 开发最佳实践
- 使用标准命名约定
- 始终包含权限检查
- 提供友好的错误处理
- 添加适当的日志记录
- 使用事务和验证
- 提供完整的测试数据

这套模板系统解决了您提到的两个核心问题：提供了系统化的调试方法和标准化的开发模板。开发者可以直接复制这些模板开始新组件的开发，大大降低学习成本。

## 三、实战问题解决方案

### 解决当前 minio 组件菜单问题的步骤

#### 1. 立即执行的调试步骤

```bash
# 在B机器上执行以下命令
echo "=== 检查组件目录结构 ==="
find runtime/component -name "*minio*" -type d
find runtime/component -name "MoquiConf.xml" -path "*minio*" -exec echo "配置文件: {}" \; -exec cat {} \;

echo "=== 检查关键文件是否存在 ==="
ls -la runtime/component/moqui-minio/screen/MinioApp/apps.xml
ls -la runtime/component/webroot/screen/webroot/apps.xml

echo "=== 检查文件权限 ==="
find runtime/component/moqui-minio -name "*.xml" -exec ls -la {} \;

echo "=== 检查启动日志 ==="
tail -100 runtime/log/moqui.log | grep -i minio
```

#### 2. 创建强制调试页面

在B机器上创建 `runtime/component/moqui-minio/screen/MinioApp/ForceDebug.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        require-authentication="true"
        standalone="true">

    <actions>
        <log level="warn" message="=== FORCE DEBUG: Minio Component Analysis ==="/>
        
        <script><![CDATA[
            def analysis = [:]
            
            // 1. 检查组件注册
            try {
                def componentRegistry = ec.ecfi.componentRegistry
                def allComponents = componentRegistry.getAllComponents()
                analysis.totalComponents = allComponents.size()
                
                def minioComponents = []
                allComponents.each { name, info ->
                    if (name.toLowerCase().contains('minio')) {
                        minioComponents.add([
                            name: name,
                            location: info.location?.toString(),
                            loadState: info.loadState?.toString(),
                            hasScreens: new File("${info.location}/screen").exists()
                        ])
                        ec.logger.warn("Found Minio Component: ${name} at ${info.location}")
                    }
                }
                analysis.minioComponents = minioComponents
            } catch (Exception e) {
                ec.logger.warn("Error checking components: ${e.message}")
                analysis.componentError = e.message
            }
            
            // 2. 检查webroot apps.xml
            try {
                def appsScreen = ec.ecfi.getScreenFacade().getScreenDefinition("component://webroot/screen/webroot/apps.xml")
                if (appsScreen) {
                    def subscreens = appsScreen.getSubscreensDefList()
                    analysis.appsSubscreens = []
                    subscreens.each { sub ->
                        analysis.appsSubscreens.add([
                            name: sub.name,
                            location: sub.location,
                            menuTitle: sub.menuTitle,
                            menuInclude: sub.menuInclude
                        ])
                        if (sub.name.toLowerCase().contains('minio')) {
                            ec.logger.warn("*** Found Minio in apps.xml: ${sub.name} -> ${sub.location}")
                        }
                    }
                } else {
                    analysis.appsError = "apps.xml not found"
                }
            } catch (Exception e) {
                ec.logger.warn("Error checking apps.xml: ${e.message}")
                analysis.appsError = e.message
            }
            
            // 3. 直接检查Minio屏幕
            def minioScreenPaths = [
                "component://moqui-minio/screen/MinioApp/apps.xml",
                "component://moqui-minio/screen/MinioApp/Bucket/FindBucket.xml"
            ]
            
            analysis.minioScreens = [:]
            minioScreenPaths.each { path ->
                try {
                    def screen = ec.ecfi.getScreenFacade().getScreenDefinition(path)
                    analysis.minioScreens[path] = [
                        exists: true,
                        menuTitle: screen.getDefaultMenuName(),
                        subscreens: screen.getSubscreensDefList().size()
                    ]
                    ec.logger.warn("Minio Screen OK: ${path}")
                } catch (Exception e) {
                    analysis.minioScreens[path] = [
                        exists: false,
                        error: e.message
                    ]
                    ec.logger.warn("Minio Screen ERROR: ${path} - ${e.message}")
                }
            }
            
            // 4. 检查服务是否可用
            try {
                def services = ec.service.getServiceRegistry().getServiceDefinitions()
                def minioServices = []
                services.each { name, def ->
                    if (name.toLowerCase().contains('minio')) {
                        minioServices.add(name)
                    }
                }
                analysis.minioServices = minioServices
                ec.logger.warn("Found ${minioServices.size()} Minio services")
            } catch (Exception e) {
                analysis.servicesError = e.message
            }
            
            context.analysis = analysis
            ec.logger.warn("=== FORCE DEBUG: Analysis Complete ===")
        ]]></script>
    </actions>

    <widgets>
        <container-box>
            <box-header title="Minio 组件强制调试分析" style="background-color: #d32f2f; color: white;"/>
            <box-body>
                <!-- 组件状态 -->
                <container-box type="danger">
                    <box-header title="组件加载状态"/>
                    <box-body>
                        <label text="系统总组件数: ${analysis.totalComponents ?: 'ERROR'}" type="h5"/>
                        
                        <section name="hasMinioComponents" condition="analysis.minioComponents">
                            <widgets>
                                <label text="发现的Minio组件:" type="h6" style="color: green;"/>
                                <iterate list="analysis.minioComponents" entry="comp">
                                    <container style="background: #e8f5e8; padding: 10px; margin: 5px 0; border-radius: 4px;">
                                        <label text="✅ ${comp.name}" type="strong"/>
                                        <label text="位置: ${comp.location}" type="p"/>
                                        <label text="加载状态: ${comp.loadState}" type="p"/>
                                        <label text="包含screens目录: ${comp.hasScreens}" type="p"/>
                                    </container>
                                </iterate>
                            </widgets>
                        </section>
                        
                        <section name="noMinioComponents" condition="!analysis.minioComponents || analysis.minioComponents.isEmpty()">
                            <widgets>
                                <label text="❌ 未发现任何Minio组件!" type="h5" style="color: red;"/>
                                <label text="这是问题的根源！组件未正确加载。" style="color: red;"/>
                            </widgets>
                        </section>
                        
                        <section name="componentError" condition="analysis.componentError">
                            <widgets>
                                <label text="组件检查错误: ${analysis.componentError}" style="color: red;"/>
                            </widgets>
                        </section>
                    </box-body>
                </container-box>

                <!-- Apps.xml 状态 -->
                <container-box type="warning">
                    <box-header title="Apps.xml 菜单状态"/>
                    <box-body>
                        <section name="hasAppsSubscreens" condition="analysis.appsSubscreens">
                            <widgets>
                                <label text="apps.xml中的子屏幕 (${analysis.appsSubscreens.size()}):" type="h6"/>
                                <iterate list="analysis.appsSubscreens" entry="sub">
                                    <container style="padding: 5px; margin: 2px 0; ${groovy: sub.name.toLowerCase().contains('minio') ? 'background: #e8f5e8; border: 2px solid green;' : 'background: #f5f5f5;'}">
                                        <label text="${sub.name}" type="strong"/>
                                        <label text="位置: ${sub.location}" type="small"/>
                                        <label text="菜单标题: ${sub.menuTitle ?: 'N/A'}" type="small"/>
                                        <label text="菜单包含: ${sub.menuInclude}" type="small"/>
                                    </container>
                                </iterate>
                            </widgets>
                        </section>
                        
                        <section name="appsError" condition="analysis.appsError">
                            <widgets>
                                <label text="Apps.xml错误: ${analysis.appsError}" style="color: red;"/>
                            </widgets>
                        </section>
                    </box-body>
                </container-box>

                <!-- Minio屏幕状态 -->
                <container-box type="info">
                    <box-header title="Minio 屏幕可访问性"/>
                    <box-body>
                        <iterate list="analysis.minioScreens" entry="screen" key="path">
                            <container style="padding: 10px; margin: 5px 0; ${groovy: screen.exists ? 'background: #e8f5e8;' : 'background: #ffe8e8;'}">
                                <label text="${screen.exists ? '✅' : '❌'} ${path}" type="strong"/>
                                <section name="screenExists" condition="screen.exists">
                                    <widgets>
                                        <label text="菜单标题: ${screen.menuTitle ?: 'N/A'}" type="p"/>
                                        <label text="子屏幕数: ${screen.subscreens}" type="p"/>
                                    </widgets>
                                </section>
                                <section name="screenError" condition="!screen.exists">
                                    <widgets>
                                        <label text="错误: ${screen.error}" style="color: red;" type="p"/>
                                    </widgets>
                                </section>
                            </container>
                        </iterate>
                    </box-body>
                </container-box>

                <!-- Minio服务状态 -->
                <container-box type="success">
                    <box-header title="Minio 服务状态"/>
                    <box-body>
                        <section name="hasMinioServices" condition="analysis.minioServices">
                            <widgets>
                                <label text="发现 ${analysis.minioServices.size()} 个Minio服务:" type="h6" style="color: green;"/>
                                <iterate list="analysis.minioServices" entry="serviceName">
                                    <label text="• ${serviceName}" type="p"/>
                                </iterate>
                            </widgets>
                        </section>
                        
                        <section name="noMinioServices" condition="!analysis.minioServices || analysis.minioServices.isEmpty()">
                            <widgets>
                                <label text="❌ 未发现Minio服务" style="color: red;"/>
                            </widgets>
                        </section>
                        
                        <section name="servicesError" condition="analysis.servicesError">
                            <widgets>
                                <label text="服务检查错误: ${analysis.servicesError}" style="color: red;"/>
                            </widgets>
                        </section>
                    </box-body>
                </container-box>

                <!-- 解决建议 -->
                <container-box type="primary">
                    <box-header title="解决建议"/>
                    <box-body>
                        <label text="根据以上分析，按优先级执行以下解决步骤：" type="h6"/>
                        
                        <container style="background: #fff3cd; padding: 15px; margin: 10px 0; border-radius: 4px;">
                            <label text="1. 如果未发现Minio组件:" type="strong"/>
                            <label text="   • 检查 runtime/component/moqui-minio/MoquiConf.xml 是否存在" type="p"/>
                            <label text="   • 检查文件权限是否正确" type="p"/>
                            <label text="   • 重启Moqui服务" type="p"/>
                        </container>
                        
                        <container style="background: #d1ecf1; padding: 15px; margin: 10px 0; border-radius: 4px;">
                            <label text="2. 如果组件已加载但屏幕无法访问:" type="strong"/>
                            <label text="   • 检查屏幕文件路径是否正确" type="p"/>
                            <label text="   • 检查apps.xml中的subscreens配置" type="p"/>
                            <label text="   • 清除runtime/classes目录重启" type="p"/>
                        </container>
                        
                        <container style="background: #f8d7da; padding: 15px; margin: 10px 0; border-radius: 4px;">
                            <label text="3. 强制解决方案:" type="strong"/>
                            <label text="   • 直接修改 webroot/screen/webroot/apps.xml" type="p"/>
                            <label text="   • 添加: &lt;subscreens-item name='minio' location='component://moqui-minio/screen/MinioApp/apps.xml' menu-title='对象存储'/&gt;" type="p"/>
                        </container>
                    </box-body>
                </container-box>
                
                <!-- 立即测试链接 -->
                <container-box>
                    <box-header title="立即测试"/>
                    <box-body>
                        <container>
                            <label text="尝试直接访问Minio功能:" type="h6"/>
                            <link url="/apps/minio/dashboard" text="测试仪表板访问" btn-type="primary" target-window="_blank"/>
                            <link url="/apps/minio/Bucket/FindBucket" text="测试存储桶列表" btn-type="info" target-window="_blank"/>
                        </container>
                    </box-body>
                </container-box>
            </box-body>
        </container-box>
    </widgets>
</screen>
```

然后访问: `http://localhost:8080/apps/minio/ForceDebug`

#### 3. 快速修复脚本

创建 `fix-minio-menu.sh`:

```bash
#!/bin/bash
echo "=== Moqui Minio 菜单修复脚本 ==="

# 1. 检查组件目录
echo "1. 检查组件目录结构..."
if [ ! -d "runtime/component/moqui-minio" ]; then
    echo "❌ moqui-minio 组件目录不存在"
    exit 1
fi

# 2. 检查关键文件
echo "2. 检查关键文件..."
files=(
    "runtime/component/moqui-minio/MoquiConf.xml"
    "runtime/component/moqui-minio/screen/MinioApp/apps.xml"
    "runtime/component/webroot/screen/webroot/apps.xml"
)

for file in "${files[@]}"; do
    if [ -f "$file" ]; then
        echo "✅ $file 存在"
    else
        echo "❌ $file 不存在"
    fi
done

# 3. 备份并修改 apps.xml
echo "3. 修复菜单配置..."
APPS_FILE="runtime/component/webroot/screen/webroot/apps.xml"

if [ -f "$APPS_FILE" ]; then
    # 备份
    cp "$APPS_FILE" "$APPS_FILE.backup.$(date +%Y%m%d%H%M%S)"
    echo "✅ 已备份 apps.xml"
    
    # 检查是否已有minio配置
    if grep -q "moqui-minio" "$APPS_FILE"; then
        echo "⚠️  apps.xml 中已存在 minio 配置"
    else
        echo "📝 添加 minio 配置到 apps.xml"
        # 在 </subscreens> 前添加 minio 配置
        sed -i.tmp 's|</subscreens>|    <subscreens-item name="minio" location="component://moqui-minio/screen/MinioApp/apps.xml" menu-title="对象存储" menu-index="50" menu-include="true"/>\n</subscreens>|' "$APPS_FILE"
    fi
else
    echo "❌ 无法找到 apps.xml 文件"
fi

# 4. 检查权限
echo "4. 检查文件权限..."
find runtime/component/moqui-minio -name "*.xml" -exec chmod 644 {} \;
echo "✅ 文件权限已修复"

# 5. 清除缓存
echo "5. 清除缓存..."
if [ -d "runtime/classes" ]; then
    rm -rf runtime/classes/*
    echo "✅ 已清除缓存"
fi

echo "=== 修复完成 ==="
echo "请重启 Moqui 服务器，然后检查菜单是否显示"
echo "如果仍有问题，请访问: http://localhost:8080/apps/minio/ForceDebug"
```

#### 4. 终极解决方案模板

如果以上都不行，创建一个独立的入口页面 `runtime/component/moqui-minio/screen/MinioEntry.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="对象存储"
        default-menu-index="50"
        default-menu-include="true"
        require-authentication="true"
        standalone="true">

    <widgets>
        <container-box>
            <box-header title="Minio 对象存储" icon="fa fa-cloud"/>
            <box-body>
                <container-row>
                    <row-col lg="4">
                        <container-box type="primary">
                            <box-header title="存储桶管理"/>
                            <box-body>
                                <label text="管理您的存储桶，创建、删除和配置存储空间。" type="p"/>
                                <container style="text-align: center; margin-top: 15px;">
                                    <link url="/apps/minio/Bucket/FindBucket" 
                                          text="进入存储桶管理" 
                                          btn-type="primary" btn-size="lg"/>
                                </container>
                            </box-body>
                        </container-box>
                    </row-col>
                    
                    <row-col lg="4">
                        <container-box type="info">
                            <box-header title="文件管理"/>
                            <box-body>
                                <label text="上传、下载和管理您的文件。" type="p"/>
                                <container style="text-align: center; margin-top: 15px;">
                                    <link url="/apps/minio/FileExplorer" 
                                          text="文件管理器" 
                                          btn-type="info" btn-size="lg"/>
                                </container>
                            </box-body>
                        </container-box>
                    </row-col>
                    
                    <row-col lg="4">
                        <container-box type="success">
                            <box-header title="系统状态"/>
                            <box-body>
                                <label text="查看系统状态和统计信息。" type="p"/>
                                <container style="text-align: center; margin-top: 15px;">
                                    <link url="/apps/minio/dashboard" 
                                          text="系统仪表板" 
                                          btn-type="success" btn-size="lg"/>
                                </container>
                            </box-body>
                        </container-box>
                    </row-col>
                </container-row>
            </box-body>
        </container-box>
    </widgets>
</screen>
```

然后在 `webroot/screen/webroot/apps.xml` 中添加:
```xml
<subscreens-item name="minioEntry" 
                location="component://moqui-minio/screen/MinioEntry.xml" 
                menu-title="对象存储" menu-index="50" menu-include="true"/>
```

## 总结和建议

### 立即行动计划

1. **在B机器上立即执行**:
    - 运行上述调试脚本
    - 创建 ForceDebug.xml 页面
    - 访问调试页面获取详细信息

2. **根据调试结果采取相应措施**:
    - 如果组件未加载：检查目录结构和权限
    - 如果屏幕无法访问：检查路径配置
    - 如果菜单未显示：强制修改 apps.xml

3. **建立标准化流程**:
    - 使用提供的组件模板
    - 建立部署检查清单
    - 创建自动化测试脚本

### 长远改进建议

1. **建立 Moqui 开发规范文档**
2. **创建组件生成器工具**
3. **建立问题排查知识库**
4. **提供标准化的开发培训**

这套解决方案不仅能解决您当前的问题，还能为未来的 Moqui 开发工作建立一个坚实的基础。# Moqui 组件开发标准模板集

## 问题现状分析

您准确指出了 Moqui 框架当前面临的两大核心问题:
1. **调试工具不足**: 问题定位困难，错误信息不够直观
2. **开发模板缺失**: 依赖个人经验，缺乏标准化开发指南

## 解决方案：标准化组件模板

### 1. 基础组件结构模板

```
标准组件目录结构:
my-component/
├── MoquiConf.xml                    # 组件配置 [必需]
├── component.xml                    # 组件元数据 [推荐]
├── data/                           # 初始数据
│   ├── MyComponentTypeData.xml     # 基础类型数据
│   ├── MyComponentSecurityData.xml # 权限数据
│   └── MyComponentDemoData.xml     # 演示数据
├── entity/                         # 实体定义
│   └── MyComponentEntities.xml
├── screen/                         # 页面定义 [核心]
│   ├── MyApp/                     # 应用主目录
│   │   ├── apps.xml               # 应用入口 [必需]
│   │   ├── dashboard.xml          # 仪表板
│   │   ├── Entity1/               # 实体1管理
│   │   │   ├── FindEntity1.xml    # 列表页 [标准]
│   │   │   ├── EditEntity1.xml    # 编辑页 [标准]
│   │   │   └── Entity1Detail.xml  # 详情页 [标准]
│   │   ├── Entity2/               # 实体2管理
│   │   └── includes/              # 公共组件
│   │       ├── CommonForms.xml    # 公共表单
│   │       └── CommonWidgets.xml  # 公共控件
│   └── webroot.xml                # Web根 [可选]
├── service/                        # 服务定义
│   └── MyComponentServices.xml
├── template/                       # 模板文件
│   ├── email/                     # 邮件模板
│   └── report/                    # 报表模板
├── webapp/                         # 静态资源
│   ├── css/
│   ├── js/
│   └── images/
└── README.md                       # 组件说明
```

### 2. 组件配置模板 (MoquiConf.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<moqui-conf xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
            xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/moqui-conf-3.xsd">

    <!-- 组件基本信息 -->
    <component name="my-component" version="1.0.0"/>
    
    <!-- 依赖组件 -->
    <component-list>
        <component name="moqui-framework"/>
        <component name="webroot"/>
        <!-- 其他依赖 -->
    </component-list>
    
    <!-- 实体定义加载 -->
    <entity-facade>
        <datasource group-name="transactional">
            <inline-other entity-group="my-component" 
                         entities-location="component://my-component/entity/MyComponentEntities.xml"/>
        </datasource>
    </entity-facade>
    
    <!-- 服务定义加载 -->
    <service-facade>
        <service-location location="component://my-component/service/MyComponentServices.xml"/>
    </service-facade>
    
    <!-- 屏幕路径映射 -->
    <screen-facade>
        <screen-location location="component://my-component/screen"/>
    </screen-facade>
    
    <!-- 安全配置 -->
    <user-facade>
        <password min-length="8" history-limit="5"/>
    </user-facade>
    
    <!-- Web应用配置 -->
    <webapp-list>
        <webapp name="webroot">
            <root-screen location="component://my-component/screen/webroot.xml"/>
            <!-- 静态资源映射 -->
            <webapp-resource location="component://my-component/webapp" url-path="/static/my-component"/>
        </webapp>
    </webapp-list>

</moqui-conf>
```

### 3. 应用入口模板 (apps.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="我的应用"
        default-menu-index="50"
        default-menu-include="true"
        require-authentication="true">

    <!-- 子屏幕定义 - 按菜单顺序 -->
    <subscreens default-item="dashboard">
        <!-- 仪表板 -->
        <subscreens-item name="dashboard" location="dashboard.xml" 
                        menu-title="仪表板" menu-index="1" menu-include="true"/>
                        
        <!-- 核心功能模块 -->
        <subscreens-item name="Entity1" location="Entity1" 
                        menu-title="实体1管理" menu-index="10" menu-include="true"/>
        <subscreens-item name="Entity2" location="Entity2" 
                        menu-title="实体2管理" menu-index="20" menu-include="true"/>
        
        <!-- 系统管理 (管理员权限) -->
        <subscreens-item name="admin" location="admin" 
                        menu-title="系统管理" menu-index="90" menu-include="N">
            <condition>
                <if-has-permission permission="MY_COMPONENT_ADMIN"/>
            </condition>
        </subscreens-item>
        
        <!-- 帮助和关于 -->
        <subscreens-item name="help" location="help.xml" 
                        menu-title="帮助" menu-index="99" menu-include="true"/>
    </subscreens>

    <!-- 页面级权限控制 -->
    <pre-actions>
        <!-- 检查基本访问权限 -->
        <if condition="!ec.user.hasPermission('MY_COMPONENT_VIEW')">
            <then>
                <message error="true">您没有权限访问此应用</message>
                <script>ec.web.sendRedirect('/Login')</script>
                <return/>
            </then>
        </if>
    </pre-actions>

    <!-- 页面内容 -->
    <widgets>
        <!-- 应用标题和描述 -->
        <section name="AppHeader" condition="ec.web.requestParameters._isRootPath">
            <widgets>
                <container-row>
                    <row-col lg="12">
                        <container class="app-header">
                            <label text="我的应用" type="h1"/>
                            <label text="这是一个标准的Moqui应用组件示例" type="p"/>
                        </container>
                    </row-col>
                </container-row>
            </widgets>
        </section>
        
        <!-- 子屏幕内容 -->
        <subscreens-active/>
    </widgets>
</screen>
```

### 4. 标准列表页模板 (FindEntity.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="实体列表"
        require-authentication="true">

    <!-- 页面转换定义 -->
    <transition name="editEntity">
        <default-response url="EditEntity"/>
    </transition>
    
    <transition name="entityDetail">
        <default-response url="EntityDetail"/>
    </transition>
    
    <transition name="deleteEntity">
        <service-call name="my.component.MyServices.delete#Entity"/>
        <default-response url="."/>
    </transition>
    
    <transition name="exportEntities">
        <default-response url="." url-type="plain">
            <parameter name="renderMode" value="csv"/>
            <parameter name="pageNoLimit" value="true"/>
        </default-response>
    </transition>

    <!-- 数据加载和权限检查 -->
    <actions>
        <!-- 权限检查 -->
        <set field="hasCreatePermission" from="ec.user.hasPermission('MY_ENTITY_CREATE')"/>
        <set field="hasUpdatePermission" from="ec.user.hasPermission('MY_ENTITY_UPDATE')"/>
        <set field="hasDeletePermission" from="ec.user.hasPermission('MY_ENTITY_DELETE')"/>
        
        <!-- 搜索参数处理 -->
        <set field="entityName" from="entityName ?: ''"/>
        <set field="statusId" from="statusId ?: ''"/>
        <set field="fromDate" from="fromDate ?: null"/>
        <set field="thruDate" from="thruDate ?: null"/>
        
        <!-- 加载选项数据 -->
        <entity-find entity-name="StatusItem" list="statusList" cache="true">
            <econdition field-name="statusTypeId" value="MyEntityStatus"/>
            <order-by field-name="sequenceNum"/>
        </entity-find>
    </actions>

    <!-- 页面内容 -->
    <widgets>
        <container-box>
            <box-header title="实体管理" icon="fa fa-list"/>
            
            <!-- 工具栏 -->
            <box-toolbar>
                <!-- 新增按钮 -->
                <section name="CreateButton" condition="hasCreatePermission">
                    <widgets>
                        <link url="editEntity" text="新增实体" btn-type="success" 
                              icon="fa fa-plus"/>
                    </widgets>
                </section>
                
                <!-- 导出按钮 -->
                <link url="exportEntities" text="导出Excel" btn-type="info" 
                      icon="fa fa-download"/>
                      
                <!-- 批量操作 -->
                <container-dialog id="BatchOperationDialog" button-text="批量操作" 
                                btn-type="warning">
                    <!-- 批量操作表单 -->
                    <form-single name="BatchOperationForm" transition="batchUpdate">
                        <field name="entityIds"><default-field><hidden/></default-field></field>
                        <field name="operation">
                            <default-field title="操作">
                                <radio>
                                    <option key="activate" text="激活"/>
                                    <option key="deactivate" text="停用"/>
                                    <option key="delete" text="删除"/>
                                </radio>
                            </default-field>
                        </field>
                        <field name="submitButton">
                            <default-field><submit text="执行"/></default-field>
                        </field>
                    </form-single>
                </container-dialog>
            </box-toolbar>

            <box-body>
                <!-- 搜索表单 -->
                <form-single name="SearchForm" transition="." 
                           extends="component://my-component/screen/MyApp/includes/CommonSearchForm.xml#SearchFormBase">
                    <field name="entityName">
                        <default-field title="实体名称">
                            <text-line size="30" ac-transition="suggestEntityNames"/>
                        </default-field>
                    </field>
                    <field name="statusId">
                        <default-field title="状态">
                            <drop-down allow-empty="true">
                                <list-options list="statusList" key="${statusId}" text="${description}"/>
                            </drop-down>
                        </default-field>
                    </field>
                    <field name="dateRange">
                        <default-field title="创建时间">
                            <date-period/>
                        </default-field>
                    </field>
                    <field name="submitButton">
                        <default-field><submit text="搜索" icon="fa fa-search"/></default-field>
                    </field>
                    <field name="resetButton">
                        <default-field><link url="." text="重置"/></default-field>
                    </field>
                </form-single>

                <!-- 数据列表 -->
                <form-list name="EntityList" list="entityList" 
                          skip-form="true" select-columns="true" saved-finds="true"
                          paginate="true">
                    
                    <!-- 数据查询 -->
                    <entity-find entity-name="MyEntity" list="entityList">
                        <search-form-inputs default-order-by=