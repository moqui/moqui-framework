# Moqui 组件开发标准模板集 (实战版)

## 重要更新说明

**基于moqui-minio组件实战经验完全重构** (2025-09-27)

本模板集已基于真实的moqui-minio组件开发过程中遇到的所有问题和最佳实践进行了全面更新。包含：
- 六轮IDE标红错误修复的完整经验
- 从功能错误到语法规范的全流程解决方案
- 经过生产验证的表单结构和服务定义
- 避免所有已知陷阱的最佳实践

**配套文档**：
- `Moqui组件开发实战规范.md` - 详细的开发规范和质量标准
- `Moqui表单字段问题诊断与修复实战指南.md` - 问题诊断和修复指南

---

## 经过实战验证的标准模板

### 1. 实战验证的表单模板

#### A. 列表页面模板 (Find[Entity].xml)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="Entity Management"
        default-menu-index="10"
        default-menu-include="true"
        require-authentication="true">

    <!-- Transitions -->
    <transition name="createEntity">
        <service-call name="myapp.MyServices.create#Entity"/>
        <default-response url="."/>
    </transition>
    <transition name="updateEntity">
        <service-call name="myapp.MyServices.update#Entity"/>
        <default-response url="."/>
    </transition>
    <transition name="deleteEntity">
        <service-call name="myapp.MyServices.delete#Entity"/>
        <default-response url="."/>
    </transition>

    <!-- Actions -->
    <actions>
        <set field="userId" from="ec.user.userId"/>
        <service-call name="myapp.MyServices.list#Entity"
                      in-map="[userId:userId, pageIndex:pageIndex, pageSize:pageSize]"
                      out-map="context"/>
    </actions>

    <!-- UI -->
    <widgets>
        <container>
            <!-- Header -->
            <label text="${ec.l10n.localize('Entity Management')}" type="h2"/>

            <!-- Toolbar -->
            <container>
                <container-dialog id="CreateEntityDialog" button-text="${ec.l10n.localize('Create Entity')}" type="primary">
                    <form-single name="CreateEntityForm" transition="createEntity">
                        <!-- Hidden fields -->
                        <field name="userId">
                            <default-field><hidden default-value="${ec.user.userId}"/></default-field>
                        </field>

                        <!-- Visible fields -->
                        <field name="entityId">
                            <default-field title="${ec.l10n.localize('Entity ID')}">
                                <text-line size="30"/>
                            </default-field>
                        </field>

                        <field name="entityName">
                            <default-field title="${ec.l10n.localize('Entity Name')}">
                                <text-line size="30"/>
                            </default-field>
                        </field>

                        <field name="description">
                            <default-field title="${ec.l10n.localize('Description')}">
                                <text-area rows="3"/>
                            </default-field>
                        </field>

                        <field name="submitButton">
                            <default-field title="">
                                <submit text="${ec.l10n.localize('Create')}"/>
                            </default-field>
                        </field>
                    </form-single>
                </container-dialog>
            </container>

            <!-- Content -->
            <container>
                <!-- Search Form -->
                <form-single name="SearchForm" transition="." map="ec.web.parameters">
                    <field name="entityName">
                        <default-field title="${ec.l10n.localize('Entity Name')}">
                            <text-line size="30"/>
                        </default-field>
                    </field>
                    <field name="submitButton">
                        <default-field title="${ec.l10n.localize('Search')}">
                            <submit/>
                        </default-field>
                    </field>

                    <field-layout>
                        <field-row>
                            <field-ref name="entityName"/>
                            <field-ref name="submitButton"/>
                        </field-row>
                    </field-layout>
                </form-single>

                <!-- Entity List -->
                <form-list name="ListEntities" list="entityList" paginate="true" skip-form="true">
                    <field name="entityId">
                        <header-field title="${ec.l10n.localize('Entity ID')}"/>
                        <default-field><display/></default-field>
                    </field>
                    <field name="entityName">
                        <header-field title="${ec.l10n.localize('Entity Name')}"/>
                        <default-field><display/></default-field>
                    </field>
                    <field name="description">
                        <header-field title="${ec.l10n.localize('Description')}"/>
                        <default-field><display/></default-field>
                    </field>
                    <field name="createdDate">
                        <header-field title="${ec.l10n.localize('Created Date')}"/>
                        <default-field><display format="yyyy-MM-dd HH:mm:ss"/></default-field>
                    </field>
                    <field name="actions">
                        <header-field title="${ec.l10n.localize('Actions')}"/>
                        <default-field>
                            <link url="deleteEntity" text="${ec.l10n.localize('Delete')}" confirmation="Are you sure?">
                                <parameter name="entityId" from="entityId"/>
                            </link>
                            <container-dialog id="EditEntityDialog_${entityId}" button-text="${ec.l10n.localize('Edit')}">
                                <form-single name="EditEntityForm" transition="updateEntity" map="entityList_entry">
                                    <!-- Hidden fields -->
                                    <field name="entityId">
                                        <default-field><hidden/></default-field>
                                    </field>
                                    <field name="userId">
                                        <default-field><hidden default-value="${ec.user.userId}"/></default-field>
                                    </field>

                                    <!-- Editable fields -->
                                    <field name="entityName">
                                        <default-field title="${ec.l10n.localize('Entity Name')}">
                                            <text-line size="30"/>
                                        </default-field>
                                    </field>

                                    <field name="description">
                                        <default-field title="${ec.l10n.localize('Description')}">
                                            <text-area rows="3"/>
                                        </default-field>
                                    </field>

                                    <field name="submitButton">
                                        <default-field title="">
                                            <submit text="${ec.l10n.localize('Update')}"/>
                                        </default-field>
                                    </field>
                                </form-single>
                            </container-dialog>
                        </default-field>
                    </field>
                </form-list>
            </container>
        </container>
    </widgets>
</screen>
```

#### B. 动态表单组件模板 (includes/EntityForm.xml)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd">
    <widgets>
        <form-single name="EntityForm">
            <!-- Hidden fields -->
            <field name="userId">
                <default-field><hidden default-value="${ec.user.userId}"/></default-field>
            </field>

            <!-- Entity ID - conditional display -->
            <field name="entityId">
                <conditional-field condition="isCreate == 'true'" title="${ec.l10n.localize('Entity ID')}">
                    <text-line size="30" maxlength="63"/>
                </conditional-field>
                <default-field>
                    <hidden/>
                </default-field>
            </field>

            <!-- Entity Name - always visible -->
            <field name="entityName">
                <default-field title="${ec.l10n.localize('Entity Name')}">
                    <text-line size="30"/>
                </default-field>
            </field>

            <!-- Description - always visible -->
            <field name="description">
                <default-field title="${ec.l10n.localize('Description')}">
                    <text-area rows="3" cols="50"/>
                </default-field>
            </field>

            <!-- Create-only fields -->
            <field name="category">
                <conditional-field condition="isCreate == 'true'" title="${ec.l10n.localize('Category')}">
                    <drop-down>
                        <option key="TYPE1" text="${ec.l10n.localize('Type 1')}"/>
                        <option key="TYPE2" text="${ec.l10n.localize('Type 2')}"/>
                    </drop-down>
                </conditional-field>
                <default-field><ignored/></default-field>
            </field>

            <!-- Submit Button -->
            <field name="submitButton">
                <default-field title="">
                    <submit text="${isCreate == 'true' ? ec.l10n.localize('Create') : ec.l10n.localize('Update')}"/>
                </default-field>
            </field>

            <!-- Layout - after all field definitions -->
            <field-layout>
                <field-row>
                    <field-ref name="entityId"/>
                    <field-ref name="entityName"/>
                </field-row>
                <field-row>
                    <field-ref name="category"/>
                </field-row>
                <field-row>
                    <field-ref name="description"/>
                </field-row>
                <field-row>
                    <field-ref name="submitButton"/>
                </field-row>
            </field-layout>
        </form-single>
    </widgets>
</screen>
```

### 2. 实战验证的服务模板

#### A. 标准CRUD服务模板
```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">

    <!-- Create Service -->
    <service verb="create" noun="Entity">
        <description>Create a new entity</description>
        <in-parameters>
            <parameter name="entityId" required="true"/>
            <parameter name="entityName" required="true"/>
            <parameter name="userId" required="true"/>
            <parameter name="description"/>
            <parameter name="category"/>
        </in-parameters>
        <out-parameters>
            <parameter name="entityId"/>
        </out-parameters>
        <actions>
            <!-- Input validation -->
            <if condition="!entityId">
                <return error="true" message="Entity ID is required"/>
            </if>
            <if condition="!entityName">
                <return error="true" message="Entity Name is required"/>
            </if>

            <!-- Business rule validation -->
            <entity-find-one entity-name="Entity" value-field="existingEntity">
                <field-map field-name="entityId"/>
            </entity-find-one>
            <if condition="existingEntity">
                <return error="true" message="Entity with ID ${entityId} already exists"/>
            </if>

            <!-- Create entity -->
            <entity-make-value entity-name="Entity" value-field="entity"/>
            <entity-set-field-defaults entity="entity"/>
            <entity-set-nonpk-fields entity="entity" map="context"/>
            <entity-create value-field="entity"/>

            <!-- Return result -->
            <set field="entityId" from="entity.entityId"/>
        </actions>
    </service>

    <!-- Read/List Service -->
    <service verb="list" noun="Entity">
        <description>List entities with optional filtering</description>
        <in-parameters>
            <parameter name="userId"/>
            <parameter name="entityName"/>
            <parameter name="category"/>
            <parameter name="pageIndex" type="Integer" default="0"/>
            <parameter name="pageSize" type="Integer" default="20"/>
        </in-parameters>
        <out-parameters>
            <parameter name="entityList" type="List"/>
            <parameter name="entityListCount" type="Integer"/>
            <parameter name="entityListPageIndex" type="Integer"/>
            <parameter name="entityListPageSize" type="Integer"/>
            <parameter name="entityListPageMaxIndex" type="Integer"/>
            <parameter name="entityListPageRangeLow" type="Integer"/>
            <parameter name="entityListPageRangeHigh" type="Integer"/>
        </out-parameters>
        <actions>
            <!-- Build find conditions -->
            <entity-find entity-name="Entity" list="entityList">
                <search-form-inputs default-order-by="entityName"/>
                <econdition field-name="userId" operator="equals" from="userId" ignore-if-empty="true"/>
                <econdition field-name="entityName" operator="like" from="entityName" ignore-case="true" ignore-if-empty="true"/>
                <econdition field-name="category" operator="equals" from="category" ignore-if-empty="true"/>
            </entity-find>

            <!-- Set pagination info -->
            <set field="entityListCount" from="entityList.size()"/>
            <set field="entityListPageIndex" from="pageIndex"/>
            <set field="entityListPageSize" from="pageSize"/>
        </actions>
    </service>

    <!-- Update Service -->
    <service verb="update" noun="Entity">
        <description>Update an existing entity</description>
        <in-parameters>
            <parameter name="entityId" required="true"/>
            <parameter name="entityName"/>
            <parameter name="userId" required="true"/>
            <parameter name="description"/>
        </in-parameters>
        <actions>
            <!-- Validation -->
            <entity-find-one entity-name="Entity" value-field="entity">
                <field-map field-name="entityId"/>
            </entity-find-one>
            <if condition="!entity">
                <return error="true" message="Entity with ID ${entityId} not found"/>
            </if>

            <!-- Update entity -->
            <entity-set-nonpk-fields entity="entity" map="context"/>
            <entity-update value-field="entity"/>
        </actions>
    </service>

    <!-- Delete Service -->
    <service verb="delete" noun="Entity">
        <description>Delete an entity</description>
        <in-parameters>
            <parameter name="entityId" required="true"/>
            <parameter name="userId" required="true"/>
        </in-parameters>
        <actions>
            <!-- Validation -->
            <entity-find-one entity-name="Entity" value-field="entity">
                <field-map field-name="entityId"/>
            </entity-find-one>
            <if condition="!entity">
                <return error="true" message="Entity with ID ${entityId} not found"/>
            </if>

            <!-- Business rule check -->
            <entity-find entity-name="RelatedEntity" list="relatedList">
                <econdition field-name="entityId" from="entityId"/>
            </entity-find>
            <if condition="relatedList">
                <return error="true" message="Cannot delete entity: has related records"/>
            </if>

            <!-- Delete entity -->
            <entity-delete value-field="entity"/>
        </actions>
    </service>
</services>
```

### 3. 关键注意事项

#### A. 避免的常见错误
```xml
<!-- ❌ 错误：set在form内部 -->
<form-single>
    <set field="isCreate" value="true"/>
</form-single>

<!-- ❌ 错误：field支持condition -->
<field name="field1" condition="isCreate == 'true'">

<!-- ❌ 错误：required在控件级别 -->
<text-line required="true"/>

<!-- ❌ 错误：field-layout在field定义前 -->
<form-single>
    <field-layout>...</field-layout>
    <field name="field1">...</field>
</form-single>
```

#### B. 正确的模式
```xml
<!-- ✅ 正确：使用hidden字段传递参数 -->
<form-single>
    <field name="isCreate">
        <default-field><hidden default-value="true"/></default-field>
    </field>
</form-single>

<!-- ✅ 正确：使用conditional-field -->
<field name="field1">
    <conditional-field condition="isCreate == 'true'">
        <text-line/>
    </conditional-field>
    <default-field><hidden/></default-field>
</field>

<!-- ✅ 正确：required在service层验证 -->
<service>
    <in-parameters>
        <parameter name="entityId" required="true"/>
    </in-parameters>
</service>

<!-- ✅ 正确：field-layout在最后 -->
<form-single>
    <field name="field1">...</field>
    <field-layout>...</field-layout>
</form-single>
```

### 4. 实战开发流程

#### A. 开发检查清单
- [ ] 遵循XML语法规范，无IDE标红
- [ ] 使用标准表单结构
- [ ] 服务层完整验证
- [ ] 错误处理完善
- [ ] 功能测试通过

#### B. 质量保证
- [ ] 所有必填字段正确验证
- [ ] 用户权限检查
- [ ] 错误信息用户友好
- [ ] 性能测试通过
- [ ] 文档完整

---

**版本**: 2.0 (实战版)
**更新日期**: 2025-09-27
**基于**: moqui-minio组件开发实战经验
**状态**: 生产就绪