# Moqui 菜单入口问题系统化调试指南

## 问题现象分析
- A机器：菜单正常显示，功能可用
- B机器：服务正常，但菜单入口消失
- 排除了权限问题和基本配置问题

## 系统化调试步骤

### 1. 组件加载状态检查

#### 1.1 检查组件是否正确加载
```groovy
// 在 Moqui 控制台执行或在 actions 中添加
<actions>
    <log level="warn" message="=== 组件加载状态检查 ==="/>
    
    <!-- 检查组件是否在运行时注册 -->
    <script><![CDATA[
        def componentRegistry = ec.ecfi.componentRegistry
        def allComponents = componentRegistry.getAllComponents()
        
        ec.logger.warn("总共加载的组件数量: ${allComponents.size()}")
        
        allComponents.each { componentName, componentInfo ->
            if (componentName.contains('minio') || componentName.contains('Minio')) {
                ec.logger.warn("发现 Minio 相关组件: ${componentName}")
                ec.logger.warn("  - 位置: ${componentInfo.location}")
                ec.logger.warn("  - 状态: ${componentInfo.loadState}")
            }
        }
        
        // 检查特定组件
        def minioComponent = componentRegistry.getComponentNode('moqui-minio')
        if (minioComponent) {
            ec.logger.warn("moqui-minio 组件存在")
            ec.logger.warn("组件配置: ${minioComponent}")
        } else {
            ec.logger.warn("moqui-minio 组件未找到!")
        }
    ]]></script>
    
    <log level="warn" message="=== 组件检查完成 ==="/>
</actions>
```

#### 1.2 检查 MoquiConf.xml 配置
```bash
# 检查组件配置文件是否存在且格式正确
find . -name "MoquiConf.xml" -path "*/moqui-minio/*" -exec echo "找到配置文件: {}" \; -exec cat {} \;
```

#### 1.3 验证组件目录结构
```bash
# 检查目录结构是否完整
ls -la runtime/component/moqui-minio/
ls -la runtime/component/moqui-minio/screen/
ls -la runtime/component/moqui-minio/screen/MinioApp/
```

### 2. 屏幕注册和路由检查

#### 2.1 检查屏幕是否正确注册
```groovy
<actions>
    <log level="warn" message="=== 屏幕注册检查 ==="/>
    
    <script><![CDATA[
        def screenFacade = ec.screen
        def rootScreenDef = screenFacade.rootScreenDef
        
        // 检查当前可用的屏幕路径
        def webroot = ec.ecfi.getScreenFacade().getScreenDefinition("webroot")
        if (webroot) {
            ec.logger.warn("webroot 屏幕存在")
            
            // 检查 subscreens
            def subscreens = webroot.getSubscreensDefList()
            subscreens.each { subscreen ->
                ec.logger.warn("webroot 子屏幕: ${subscreen.name} -> ${subscreen.location}")
                if (subscreen.name.contains('minio') || subscreen.name.contains('Minio')) {
                    ec.logger.warn("*** 发现 Minio 相关子屏幕: ${subscreen.name}")
                }
            }
        }
        
        // 尝试直接访问 Minio 屏幕
        try {
            def minioScreen = ec.ecfi.getScreenFacade().getScreenDefinition("component://moqui-minio/screen/MinioApp/apps.xml")
            if (minioScreen) {
                ec.logger.warn("Minio apps.xml 屏幕可以访问")
            } else {
                ec.logger.warn("Minio apps.xml 屏幕无法访问")
            }
        } catch (Exception e) {
            ec.logger.warn("访问 Minio 屏幕时出错: ${e.message}")
        }
    ]]></script>
    
    <log level="warn" message="=== 屏幕检查完成 ==="/>
</actions>
```

#### 2.2 检查菜单生成逻辑
```groovy
<actions>
    <log level="warn" message="=== 菜单生成检查 ==="/>
    
    <script><![CDATA[
        // 检查菜单项生成
        def menuItems = []
        def currentScreen = ec.screen.rootScreenDef
        
        // 递归检查所有子屏幕的菜单配置
        def checkMenuItems
        checkMenuItems = { screenDef, prefix = "" ->
            def subscreens = screenDef.getSubscreensDefList()
            subscreens.each { subscreen ->
                def menuTitle = subscreen.menuTitle
                def menuInclude = subscreen.menuInclude
                def menuIndex = subscreen.menuIndex
                
                ec.logger.warn("${prefix}屏幕: ${subscreen.name}")
                ec.logger.warn("${prefix}  菜单标题: ${menuTitle}")
                ec.logger.warn("${prefix}  菜单包含: ${menuInclude}")
                ec.logger.warn("${prefix}  菜单索引: ${menuIndex}")
                ec.logger.warn("${prefix}  位置: ${subscreen.location}")
                
                if (subscreen.name.contains('minio') || subscreen.name.contains('Minio')) {
                    ec.logger.warn("${prefix}*** MINIO 相关菜单项 ***")
                }
                
                // 递归检查子屏幕
                try {
                    def childScreen = ec.ecfi.getScreenFacade().getScreenDefinition(subscreen.location)
                    if (childScreen && childScreen.getSubscreensDefList().size() > 0) {
                        checkMenuItems(childScreen, prefix + "  ")
                    }
                } catch (Exception e) {
                    ec.logger.warn("${prefix}无法加载子屏幕: ${e.message}")
                }
            }
        }
        
        // 从 apps 屏幕开始检查
        try {
            def appsScreen = ec.ecfi.getScreenFacade().getScreenDefinition("component://webroot/screen/webroot/apps.xml")
            if (appsScreen) {
                ec.logger.warn("开始检查 apps.xml 的菜单结构:")
                checkMenuItems(appsScreen)
            } else {
                ec.logger.warn("无法找到 apps.xml 屏幕")
            }
        } catch (Exception e) {
            ec.logger.warn("检查菜单时出错: ${e.message}")
        }
    ]]></script>
    
    <log level="warn" message="=== 菜单检查完成 ==="/>
</actions>
```

### 3. 环境差异对比检查

#### 3.1 Java 环境对比
```bash
# A机器和B机器分别执行
java -version
echo $JAVA_HOME
echo $MOQUI_HOME
```

#### 3.2 文件权限检查
```bash
# 检查关键文件的权限
ls -la runtime/component/moqui-minio/MoquiConf.xml
ls -la runtime/component/moqui-minio/screen/MinioApp/apps.xml
find runtime/component/moqui-minio -name "*.xml" -exec ls -la {} \;
```

#### 3.3 配置文件对比
```bash
# 对比两台机器的关键配置文件
diff <machine-A>/runtime/conf/MoquiDevConf.xml <machine-B>/runtime/conf/MoquiDevConf.xml
diff <machine-A>/runtime/component/moqui-minio/MoquiConf.xml <machine-B>/runtime/component/moqui-minio/MoquiConf.xml
```

### 4. 运行时状态检查

#### 4.1 启动日志分析
```bash
# 查找组件加载相关的日志
grep -i "component.*minio" runtime/log/moqui.log
grep -i "screen.*minio" runtime/log/moqui.log
grep -i "error\|exception" runtime/log/moqui.log | grep -i minio
```

#### 4.2 实时调试页面创建
创建一个专门的调试页面 `runtime/component/moqui-minio/screen/MinioApp/DebugInfo.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        require-authentication="true"
        standalone="true">

    <actions>
        <!-- 所有上述的调试代码都可以放在这里 -->
        <log level="warn" message="=== Minio 调试信息页面 ==="/>
        
        <!-- 组件检查 -->
        <script><![CDATA[
            def componentRegistry = ec.ecfi.componentRegistry
            def allComponents = componentRegistry.getAllComponents()
            
            context.totalComponents = allComponents.size()
            context.minioComponents = []
            
            allComponents.each { componentName, componentInfo ->
                if (componentName.toLowerCase().contains('minio')) {
                    context.minioComponents.add([
                        name: componentName,
                        location: componentInfo.location,
                        state: componentInfo.loadState?.toString()
                    ])
                }
            }
            
            // 屏幕检查
            context.screenInfo = [:]
            try {
                def minioAppsScreen = ec.ecfi.getScreenFacade().getScreenDefinition("component://moqui-minio/screen/MinioApp/apps.xml")
                context.screenInfo.minioAppsExists = minioAppsScreen != null
                if (minioAppsScreen) {
                    context.screenInfo.minioAppsSubscreens = minioAppsScreen.getSubscreensDefList().size()
                }
            } catch (Exception e) {
                context.screenInfo.minioAppsError = e.message
            }
            
            // 当前用户信息
            context.userInfo = [
                userId: ec.user.userId,
                username: ec.user.username,
                groups: ec.user.userGroupIdSet.join(', '),
                permissions: ec.user.userPermissions.keySet().join(', ')
            ]
        ]]></script>
    </actions>

    <widgets>
        <container-box>
            <box-header title="Moqui Minio 组件调试信息"/>
            <box-body>
                <container-row>
                    <row-col lg="6">
                        <container-box type="info">
                            <box-header title="组件加载状态"/>
                            <box-body>
                                <label text="总组件数: ${totalComponents}" type="p"/>
                                <label text="Minio相关组件:" type="strong"/>
                                <iterate list="minioComponents" entry="comp">
                                    <container>
                                        <label text="- 名称: ${comp.name}" type="p"/>
                                        <label text="  位置: ${comp.location}" type="small"/>
                                        <label text="  状态: ${comp.state}" type="small"/>
                                    </container>
                                </iterate>
                                <section name="noMinioComponents" condition="minioComponents.isEmpty()">
                                    <widgets>
                                        <label text="❌ 未发现任何Minio组件!" style="color: red;" type="strong"/>
                                    </widgets>
                                </section>
                            </box-body>
                        </container-box>
                    </row-col>
                    
                    <row-col lg="6">
                        <container-box type="warning">
                            <box-header title="屏幕状态"/>
                            <box-body>
                                <label text="Minio Apps屏幕存在: ${screenInfo.minioAppsExists ?: false}" type="p"/>
                                <section name="screenExists" condition="screenInfo.minioAppsExists">
                                    <widgets>
                                        <label text="子屏幕数量: ${screenInfo.minioAppsSubscreens}" type="p"/>
                                    </widgets>
                                </section>
                                <section name="screenError" condition="screenInfo.minioAppsError">
                                    <widgets>
                                        <label text="错误: ${screenInfo.minioAppsError}" style="color: red;" type="p"/>
                                    </widgets>
                                </section>
                            </box-body>
                        </container-box>
                        
                        <container-box type="success">
                            <box-header title="用户信息"/>
                            <box-body>
                                <label text="用户ID: ${userInfo.userId}" type="p"/>
                                <label text="用户名: ${userInfo.username}" type="p"/>
                                <label text="用户组: ${userInfo.groups}" type="p"/>
                                <label text="权限: ${userInfo.permissions}" type="small"/>
                            </box-body>
                        </container-box>
                    </row-col>
                </container-row>
            </box-body>
        </container-box>
    </widgets>
</screen>
```

然后访问: `http://localhost:8080/apps/minio/DebugInfo`

### 5. 终极解决方案

如果以上都无法定位问题，使用强制注册方式:

#### 5.1 直接修改 webroot/screen/webroot/apps.xml
```xml
<!-- 在 apps.xml 中强制添加 -->
<subscreens default-item="dashboard">
    <!-- 现有的其他子屏幕 -->
    
    <!-- 强制添加 Minio 入口 -->
    <subscreens-item name="minio" location="component://moqui-minio/screen/MinioApp/apps.xml" 
                    menu-title="对象存储" menu-index="99" menu-include="true"/>
</subscreens>
```

#### 5.2 创建独立的调试入口
在 `webroot/screen/webroot/apps.xml` 中添加:
```xml
<subscreens-item name="minioDebug" 
                location="component://moqui-minio/screen/MinioApp/DebugInfo.xml" 
                menu-title="Minio调试" menu-index="98" menu-include="true"/>
```

## 常见问题和解决方案

### 问题1: 组件未正确加载
**症状**: 服务可用但菜单不显示
**解决**: 检查 MoquiConf.xml 中的依赖声明

### 问题2: 屏幕路径错误
**症状**: 控制台有路径错误但不明显
**解决**: 使用绝对路径而非相对路径

### 问题3: 缓存问题
**症状**: A机器正常B机器异常
**解决**: 清除 runtime/classes 目录并重启

### 问题4: 文件系统权限
**症状**: Linux环境下特有问题
**解决**: 检查并修正文件权限

## 预防措施

1. **标准化部署脚本**: 确保两台机器的部署过程完全一致
2. **配置管理**: 使用版本控制管理所有配置文件
3. **环境检查清单**: 部署后必须执行的检查步骤
4. **自动化测试**: 包含菜单显示的集成测试