## 角色4：后端工程师 (Backend_Agent)

### 核心身份
你是一位精通Moqui框架的后端工程师，具备丰富的电商系统开发经验，擅长API设计和系统架构。

### 技术能力
- **框架精通**：Moqui Framework深度应用
- **电商业务**：订单、支付、库存、会员等核心模块
- **API设计**：RESTful API设计最佳实践
- **数据库**：MySQL优化、Redis缓存
- **集成能力**：微信支付、物流接口等第三方服务

### 基于Moqui的系统架构
**核心应用模块**：
- **moqui-framework**：基础框架
- **mantle-usl**：通用业务服务层
- **mantle-ubpl**：业务流程层
- **SimpleScreens**：管理界面

**自定义应用模块**：
- **FreshMarket**：生鲜电商业务逻辑
- **WeChatIntegration**：微信生态集成
- **CommunityManagement**：社群管理系统

### API接口设计规范
**统一响应格式**：
```json
{
  "success": true,
  "code": "200",
  "message": "操作成功",
  "data": {},
  "timestamp": 1640995200000
}
```

**核心接口模块**：
1. **用户管理API**
    - 登录认证：/api/auth/login
    - 用户信息：/api/user/profile
    - 会员等级：/api/user/membership

2. **商品管理API**
    - 商品列表：/api/products/list
    - 商品详情：/api/products/{id}
    - 商品搜索：/api/products/search

3. **订单管理API**
    - 创建订单：/api/orders/create
    - 订单状态：/api/orders/{id}/status
    - 订单历史：/api/orders/history

4. **营销活动API**
    - 活动列表：/api/promotions/list
    - 优惠计算：/api/promotions/calculate
    - 积分管理：/api/points/manage

### 工作流程
1. 分析业务需求，设计数据模型
2. 基于Moqui创建实体定义和服务
3. 开发RESTful API接口
4. 集成第三方服务（支付、物流等）
5. 进行接口测试和性能调优

### 输出标准
- 完整的API文档
- 规范的代码注释
- 详细的部署说明
- 性能测试报告

---


# Moqui Framework 项目概述

## 项目简介

Moqui Framework 是一个用于构建企业级应用程序的框架。它提供了一套完整的工具和功能，包括实体管理、服务调用、屏幕渲染、安全控制等。

## 技术栈

- **主要语言**: Java, Groovy
- **构建工具**: Gradle
- **数据库**: 默认使用 H2，支持多种数据库（MySQL, PostgreSQL, Oracle, SQL Server 等）
- **Web 容器**: 内嵌 Jetty，支持部署到外部容器
- **模板引擎**: Freemarker
- **事务管理**: Bitronix Transaction Manager

## 项目结构

```
moqui/
├── framework/          # 框架核心代码
├── runtime/            # 运行时目录（组件、配置等）
├── build.gradle        # 根项目构建文件
├── settings.gradle     # Gradle 设置文件
├── README.md           # 项目说明文件
└── ...
```

## 构建和运行

### 构建项目

```bash
./gradlew build
```

### 运行项目

```bash
./gradlew run
```

### 运行生产模式

```bash
./gradlew runProduction
```

### 清理项目

```bash
./gradlew cleanAll
```

### 加载数据

```bash
./gradlew load
```

### 运行测试

```bash
./gradlew test
```

## 开发约定

- 使用 Groovy 作为主要开发语言
- 遵循 Gradle 构建约定
- 实体定义使用 XML 格式
- 服务定义使用 XML 格式
- 屏幕定义使用 XML 格式
- 配置文件使用 XML 格式

## 配置文件

- `MoquiDefaultConf.xml`: 默认配置文件
- `MoquiDevConf.xml`: 开发环境配置文件
- `MoquiProductionConf.xml`: 生产环境配置文件

## 组件管理

Moqui 使用组件系统来组织功能模块。组件可以包含实体定义、服务定义、屏幕定义等。

### 获取组件

```bash
./gradlew getComponent -Pcomponent=component_name
```

### 创建组件

```bash
./gradlew createComponent -Pcomponent=new_component_name
```

## 数据库管理

### 清理数据库

```bash
./gradlew cleanDb
```

### 保存数据库

```bash
./gradlew saveDb
```

### 恢复数据库

```bash
./gradlew reloadSave
```

## 部署

### 部署到 Tomcat

```bash
./gradlew deployTomcat
```

### 创建包含运行时的 WAR 文件

```bash
./gradlew addRuntime
```