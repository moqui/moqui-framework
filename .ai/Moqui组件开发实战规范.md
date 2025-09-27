# Moqui组件开发实战规范

## 概述

本规范基于moqui-minio组件开发的完整实战经验，涵盖从初始开发到生产就绪的全流程最佳实践。

## 一、开发环境规范

### 1.1 IDE配置要求
- **必须**：配置Moqui XML schema验证
- **必须**：开启实时语法检查和错误提示
- **推荐**：使用支持XML格式化和自动完成的IDE
- **原则**：零容忍IDE标红，所有语法错误必须立即修复

### 1.2 项目结构标准
```
component/
├── MoquiConf.xml              # 组件配置
├── service/                   # 服务定义
│   └── [ModuleName]Services.xml
├── screen/                    # 界面定义
│   └── [AppName]/
│       ├── apps.xml          # 应用入口
│       └── [Entity]/
│           ├── Find[Entity].xml    # 列表页
│           ├── [Entity]Detail.xml  # 详情页
│           └── includes/
│               └── [Entity]Form.xml # 表单组件
├── entity/                    # 实体定义
└── data/                     # 基础数据
```

## 二、XML语法规范

### 2.1 标签使用规范

#### A. 禁止使用的模式
```xml
<!-- ❌ 错误：set标签在表单内部 -->
<form-single>
    <set field="isCreate" value="true"/>
</form-single>

<!-- ❌ 错误：actions在表单内部 -->
<form-single>
    <actions>
        <log message="debug"/>
    </actions>
</form-single>

<!-- ❌ 错误：field支持condition属性 -->
<field name="field1" condition="isCreate == 'true'">

<!-- ❌ 错误：field-ref支持condition属性 -->
<field-ref name="field1" condition="isCreate == 'true'"/>

<!-- ❌ 错误：required属性在控件级别 -->
<text-line required="true"/>
<conditional-field required="true">
<default-field required="true">
```

#### B. 正确使用模式
```xml
<!-- ✅ 正确：set标签在actions内部 -->
<screen>
    <actions>
        <set field="isCreate" value="true"/>
    </actions>
</screen>

<!-- ✅ 正确：使用hidden字段传递参数 -->
<form-single>
    <field name="isCreate">
        <default-field><hidden default-value="true"/></default-field>
    </field>
</form-single>

<!-- ✅ 正确：使用conditional-field处理条件 -->
<field name="field1">
    <conditional-field condition="isCreate == 'true'">
        <text-line/>
    </conditional-field>
    <default-field><hidden/></default-field>
</field>

<!-- ✅ 正确：required验证在service层 -->
<service name="create#Entity">
    <in-parameters>
        <parameter name="entityId" required="true"/>
    </in-parameters>
</service>
```

### 2.2 结构层级规范

#### A. 表单结构标准
```xml
<form-single name="EntityForm">
    <!-- 1. 隐藏字段优先 -->
    <field name="hiddenField">
        <default-field><hidden default-value="..."/></default-field>
    </field>

    <!-- 2. 所有字段定义 -->
    <field name="field1">
        <conditional-field condition="...">
            <text-line/>
        </conditional-field>
        <default-field><hidden/></default-field>
    </field>

    <!-- 3. field-layout在最后 -->
    <field-layout>
        <field-row>
            <field-ref name="field1"/>
        </field-row>
    </field-layout>
</form-single>
```

#### B. Screen结构标准
```xml
<screen>
    <!-- 1. transitions定义 -->
    <transition name="action">
        <service-call name="..."/>
        <default-response url="..."/>
    </transition>

    <!-- 2. actions执行 -->
    <actions>
        <set field="..." value="..."/>
        <service-call name="..."/>
    </actions>

    <!-- 3. widgets界面 -->
    <widgets>
        <container>
            <!-- 标题 -->
            <label text="..." type="h2"/>

            <!-- 工具栏 -->
            <container>
                <container-dialog>
                    <form-single>...</form-single>
                </container-dialog>
            </container>

            <!-- 内容区域 -->
            <container>
                <form-single>...</form-single>
                <form-list>...</form-list>
            </container>
        </container>
    </widgets>
</screen>
```

### 2.3 UPA错误避免规范

#### A. 避免多个同类型元素并列
```xml
<!-- ❌ 可能引起UPA冲突 -->
<widgets>
    <container>...</container>
    <container>...</container>
    <container>...</container>
</widgets>

<!-- ✅ 使用主容器包装 -->
<widgets>
    <container>
        <container>...</container>
        <container>...</container>
        <container>...</container>
    </container>
</widgets>
```

#### B. 避免内容与属性冲突
```xml
<!-- ❌ 可能引起冲突 -->
<box-header title="Title">
    <label text="Title"/>
</box-header>

<!-- ✅ 选择一种方式 -->
<box-header title="Title"/>
<!-- 或者 -->
<box-header>
    <label text="Title"/>
</box-header>
```

## 三、表单开发规范

### 3.1 表单设计原则
1. **单一职责**：每个表单只负责一个业务操作
2. **条件明确**：使用conditional-field而非extends冲突
3. **字段复用**：通过includes实现组件化
4. **验证分离**：前端显示，后端验证

### 3.2 CRUD表单模板

#### A. 创建表单模板
```xml
<form-single name="Create[Entity]Form" transition="create[Entity]">
    <!-- 隐藏字段 -->
    <field name="userId">
        <default-field><hidden default-value="${ec.user.userId}"/></default-field>
    </field>

    <!-- 必填字段 -->
    <field name="entityId">
        <default-field title="Entity ID">
            <text-line size="30"/>
        </default-field>
    </field>

    <!-- 可选字段 -->
    <field name="description">
        <default-field title="Description">
            <text-area rows="3"/>
        </default-field>
    </field>

    <!-- 提交按钮 -->
    <field name="submitButton">
        <default-field title="">
            <submit text="Create"/>
        </default-field>
    </field>

    <!-- 布局 -->
    <field-layout>
        <field-row>
            <field-ref name="entityId"/>
        </field-row>
        <field-row>
            <field-ref name="description"/>
        </field-row>
        <field-row>
            <field-ref name="submitButton"/>
        </field-row>
    </field-layout>
</form-single>
```

#### B. 编辑表单模板
```xml
<form-single name="Edit[Entity]Form" transition="update[Entity]" map="entity">
    <!-- 隐藏主键 -->
    <field name="entityId">
        <default-field><hidden/></default-field>
    </field>

    <!-- 可编辑字段 -->
    <field name="description">
        <default-field title="Description">
            <text-area rows="3"/>
        </default-field>
    </field>

    <!-- 提交按钮 -->
    <field name="submitButton">
        <default-field title="">
            <submit text="Update"/>
        </default-field>
    </field>
</form-single>
```

### 3.3 动态表单处理
```xml
<!-- 统一表单，根据模式动态调整 -->
<form-single name="[Entity]Form">
    <field name="entityId">
        <conditional-field condition="isCreate == 'true'" title="Entity ID">
            <text-line size="30"/>
        </conditional-field>
        <default-field><hidden/></default-field>
    </field>

    <field name="submitButton">
        <default-field title="">
            <submit text="${isCreate == 'true' ? 'Create' : 'Update'}"/>
        </default-field>
    </field>
</form-single>
```

## 四、服务开发规范

### 4.1 服务定义标准
```xml
<service verb="create" noun="Entity">
    <description>创建实体</description>
    <in-parameters>
        <parameter name="entityId" required="true"/>
        <parameter name="userId" required="true"/>
        <parameter name="description"/>
    </in-parameters>
    <out-parameters>
        <parameter name="entityId"/>
    </out-parameters>
    <actions>
        <!-- 参数验证 -->
        <if condition="!entityId">
            <return error="true" message="Entity ID is required"/>
        </if>

        <!-- 业务逻辑 -->
        <entity-make-value entity-name="Entity" value-field="entity"/>
        <entity-set-field-defaults entity="entity"/>
        <entity-set-nonpk-fields entity="entity" map="context"/>
        <entity-create value-field="entity"/>

        <!-- 返回结果 -->
        <set field="entityId" from="entity.entityId"/>
    </actions>
</service>
```

### 4.2 错误处理规范
```xml
<actions>
    <!-- 输入验证 -->
    <if condition="!entityId">
        <return error="true" message="Entity ID is required"/>
    </if>

    <!-- 业务规则验证 -->
    <entity-find-one entity-name="Entity" value-field="existingEntity">
        <field-map field-name="entityId"/>
    </entity-find-one>
    <if condition="existingEntity">
        <return error="true" message="Entity already exists"/>
    </if>

    <!-- 异常处理 -->
    <try>
        <entity-create value-field="entity"/>
        <catch>
            <log level="error" message="Failed to create entity: ${ec.message.getErrors()}"/>
            <return error="true" message="Failed to create entity"/>
        </catch>
    </try>
</actions>
```

## 五、开发流程规范

### 5.1 开发检查清单

#### A. 编码阶段
- [ ] 遵循XML语法规范
- [ ] 使用标准表单模板
- [ ] 实现完整的CRUD操作
- [ ] 添加适当的日志和错误处理
- [ ] 无IDE标红错误

#### B. 测试阶段
- [ ] 功能测试：创建、读取、更新、删除
- [ ] 边界测试：必填字段、字符长度、特殊字符
- [ ] 错误测试：无效输入、权限验证、并发操作
- [ ] 性能测试：大数据量、复杂查询

#### C. 发布阶段
- [ ] 代码审查通过
- [ ] 文档完整更新
- [ ] 配置正确部署
- [ ] 生产环境验证

### 5.2 问题诊断流程

#### A. IDE标红处理
1. **立即修复**：发现标红立即处理，不允许积累
2. **查阅规范**：参考本文档和官方schema定义
3. **测试验证**：修复后进行功能测试
4. **经验记录**：将新问题和解决方案记录到文档

#### B. 功能错误处理
1. **日志分析**：查看moqui.log获取详细错误信息
2. **参数验证**：确认所有必填参数正确传递
3. **服务测试**：单独测试相关服务是否正常
4. **逐步调试**：从最简单的情况开始逐步复杂化

## 六、质量保证规范

### 6.1 代码质量标准
- **可读性**：清晰的命名、适当的注释、良好的结构
- **可维护性**：模块化设计、低耦合、高内聚
- **可扩展性**：标准化接口、灵活的配置、良好的抽象
- **健壮性**：完善的错误处理、输入验证、异常恢复

### 6.2 文档要求
- **API文档**：详细的服务接口说明
- **用户手册**：完整的功能使用指南
- **开发指南**：组件扩展和定制说明
- **故障排除**：常见问题和解决方案

### 6.3 持续改进
- **定期审查**：代码质量和规范遵循情况
- **经验总结**：将实战经验融入规范更新
- **工具支持**：开发辅助工具和自动化检查
- **知识分享**：团队内部的经验交流和培训

## 七、附录

### 7.1 常见错误速查表
| 错误类型 | 错误表现 | 解决方案 |
|---------|---------|---------|
| set标签位置 | 表单内使用set | 移到actions或使用hidden字段 |
| required属性 | text-line等控件使用required | 移除，在service中验证 |
| field条件 | field标签使用condition | 使用conditional-field |
| UPA冲突 | schema解析歧义 | 简化结构，避免同类元素并列 |
| field-layout位置 | field-layout在field定义前 | 移到所有field定义后 |

### 7.2 参考资源
- [Moqui官方文档](https://www.moqui.org/docs.html)
- [XML Schema定义](http://moqui.org/xsd/)
- [标准组件示例](https://github.com/moqui/moqui-framework)

---

**版本**: 1.0
**更新日期**: 2025-09-27
**基于**: moqui-minio组件开发实战经验