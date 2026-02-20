# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Moqui Framework is an enterprise application development framework based on Groovy and Java. It provides a complete runtime environment with built-in database management, service-oriented architecture, web framework, and business logic components.

## Common Development Commands

### Build and Run
- `gradle build` - Build the framework and all components
- `gradle run` - Run Moqui with development configuration
- `gradle runProduction` - Run with production configuration
- `gradle clean` - Clean build artifacts
- `gradle cleanAll` - Clean everything including database, logs, and sessions

### Data Management
- `gradle load` - Load all data types (default)
- `gradle load -Ptypes=seed` - Load only seed data
- `gradle load -Ptypes=seed,seed-initial` - Load seed and seed-initial data
- `gradle loadProduction` - Load production data (seed, seed-initial, install)
- `gradle cleanDb` - Clean database files (Derby, H2, OrientDB, ElasticSearch/OpenSearch)

### Testing
- `gradle test` - Run all tests
- `gradle framework:test` - Run framework tests only
- To run a single test: Use standard JUnit/Spock test runners with system properties from MoquiDefaultConf.xml

### Component Management
- `gradle getComponent -Pcomponent=<name>` - Get a component and its dependencies
- `gradle createComponent -Pcomponent=<name>` - Create new component from template
- Components are located in `runtime/component/`

### Deployment
- `gradle addRuntime` - Create moqui-plus-runtime.war with embedded runtime
- `gradle deployTomcat` - Deploy to Tomcat (requires tomcatHome configuration)

### ElasticSearch/OpenSearch
- `gradle downloadOpenSearch` - Download and install OpenSearch
- `gradle downloadElasticSearch` - Download and install ElasticSearch
- `gradle startElasticSearch` - Start search service
- `gradle stopElasticSearch` - Stop search service

## Architecture and Structure

### Core Framework (`/framework`)
The framework provides the foundational services and APIs:
- **Entity Engine** (`/framework/entity/`) - ORM and database abstraction layer supporting multiple databases
- **Service Engine** (`/framework/service/`) - Service-oriented architecture with synchronous/asynchronous execution
- **Screen/Web** (`/framework/screen/`) - XML-based screen rendering with support for various output formats
- **Resource Facade** - Unified resource access for files, classpath, URLs, and content repositories
- **Security** - Built-in authentication, authorization, and artifact-based permissions
- **L10n/I18n** - Localization and internationalization support
- **Cache** - Distributed caching with Hazelcast support

### Runtime Structure (`/runtime`)
- **base-component/** - Core business logic components (webroot, tools, etc.)
- **component/** - Add-on components (HiveMind, SimpleScreens, PopCommerce, Mantle)
- **conf/** - Configuration files (MoquiDevConf.xml, MoquiProductionConf.xml)
- **db/** - Database files for embedded databases
- **elasticsearch/ or opensearch/** - Search engine installation
- **lib/** - Additional JAR libraries
- **log/** - Application logs
- **sessions/** - Web session data

### Component Architecture
Components are modular units containing:
- **entity/** - Data model definitions (XML)
- **service/** - Service definitions and implementations (XML/Groovy/Java)
- **screen/** - Screen definitions (XML)
- **data/** - Seed and demo data (XML/JSON)
- **template/** - FreeMarker templates
- **build.gradle** - Component-specific build configuration

### Key Design Patterns
1. **Service Facade Pattern** - All business logic exposed through services
2. **Entity-Control-Boundary** - Clear separation between data, logic, and presentation
3. **Convention over Configuration** - Sensible defaults with override capability
4. **Resource Abstraction** - Uniform access to different resource types
5. **Context Management** - ExecutionContext provides access to all framework features

### Configuration System
- **MoquiDefaultConf.xml** - Default framework configuration
- **MoquiDevConf.xml** - Development overrides
- **MoquiProductionConf.xml** - Production settings
- Configuration can be overridden via system properties, environment variables, or external config files

### Transaction Management
- Default: Bitronix Transaction Manager (BTM)
- Alternative: JNDI/JTA from application server
- Automatic transaction boundaries for services
- Support for multiple datasources with XA transactions

### Web Framework
- RESTful service automation from service definitions
- Screen rendering with transitions and actions
- Support for multiple render modes (HTML, JSON, XML, PDF, etc.)
- Built-in CSRF protection and security headers
- WebSocket support for real-time features

## Development Workflow

### Setting Up IDE
- `gradle setupIntellij` - Configure IntelliJ IDEA with XML catalogs for autocomplete

### Database Selection
Default is H2. To use PostgreSQL or MySQL:
1. Configure datasource in Moqui configuration
2. Add JDBC driver to runtime/lib
3. Update entity definitions if needed for database-specific features

### Component Development
1. Create component: `gradle createComponent -Pcomponent=myapp`
2. Define entities in `component/myapp/entity/`
3. Implement services in `component/myapp/service/`
4. Create screens in `component/myapp/screen/`
5. Add seed data in `component/myapp/data/`

### Hot Reload Support
- Groovy scripts and services reload automatically in development mode
- Screen definitions reload on change
- Entity definitions require restart