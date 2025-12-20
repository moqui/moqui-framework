# Moqui Framework - System Evaluation V2

**Evaluation Date**: 2025-12-08
**Previous Evaluation**: 2025-11-25
**Framework Version**: 3.1.0-rc2
**Codebase Size**: ~68,888 lines (Groovy + Java)

---

## Executive Summary

This evaluation documents the significant improvements made since the initial system evaluation on 2025-11-25. The Moqui Framework has undergone major security hardening, a complete Jakarta EE 10 migration, comprehensive dependency updates, and establishment of a CI/CD pipeline.

### Overall Progress

| Area | Previous Rating | Current Rating | Status |
|------|-----------------|----------------|--------|
| Security | **HIGH RISK** (2 Critical, 5 High) | **MODERATE** (0 Critical, 2 High) | Major Improvement |
| Technical Debt | **MODERATE-HIGH** | **MODERATE** | Improved |
| Architecture | **GOOD** | **GOOD** | Stable |
| Testing | **POOR** (<10% coverage, 18 tests) | **IMPROVING** (28 tests, CI added) | In Progress |
| Dependencies | **OUTDATED** | **CURRENT** | Completed |

---

## 1. Security Improvements (Completed)

### Critical Issues - RESOLVED

#### CRITICAL-1: XXE Vulnerability - FIXED
**Location**: `/framework/src/main/java/org/moqui/util/MNode.java:67-94`
**Status**: RESOLVED

A secure SAX parser factory was implemented with comprehensive XXE protections:
- External general entities disabled
- External parameter entities disabled
- External DTD loading disabled
- XInclude processing disabled
- FEATURE_SECURE_PROCESSING enabled

```java
private static SAXParserFactory createSecureSaxParserFactory() {
    SAXParserFactory factory = SAXParserFactory.newInstance();
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setXIncludeAware(false);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    return factory;
}
```

**Test Coverage**: `MNodeSecurityTests.groovy`

#### CRITICAL-2: Weak Password Hashing - FIXED
**Location**: `/framework/src/main/java/org/moqui/util/PasswordHasher.java`
**Status**: RESOLVED

Implemented BCrypt password hashing with:
- Default cost factor of 12 (2^12 = 4,096 iterations)
- Support for cost factor upgrades
- Legacy hash migration support
- SecureRandom for salt generation

```java
public static String hashWithBcrypt(String password, int cost) {
    return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
}
```

**Dependency Added**: `at.favre.lib:bcrypt:0.10.2`
**Test Coverage**: `PasswordHasherTests.groovy` (17 test cases)

### High Severity Issues - Status

| ID | Finding | Status | Evidence |
|----|---------|--------|----------|
| HIGH-1 | Session Fixation | **FIXED** | `UserFacadeImpl.groovy:675-677` - Session regeneration after authentication |
| HIGH-2 | Credentials in Logs | **FIXED** | `UserFacadeImpl.groovy:164,298` - "Don't log credentials" comments with implementation |
| HIGH-3 | Weak CSRF Tokens | **FIXED** | `StringUtilities.java:439` - Uses `SecureRandom` for 32-char tokens |
| HIGH-4 | Missing Cookie SameSite | **FIXED** | `UserFacadeImpl.groovy:228-229` - `WebUtilities.addCookieWithSameSiteLax()` |
| HIGH-5 | API Keys in URLs | **FIXED** | `UserFacadeImpl.groovy:168,302` - Header-only API key acceptance (SEC-008) |

### Security Headers - IMPLEMENTED
**Location**: `/framework/src/main/groovy/org/moqui/impl/webapp/MoquiServlet.groovy:269-283`

```groovy
response.setHeader("X-Content-Type-Options", "nosniff")
response.setHeader("X-Frame-Options", "SAMEORIGIN")
response.setHeader("X-XSS-Protection", "1; mode=block")
```

---

## 2. Jakarta EE 10 Migration - COMPLETED

### Component Updates

| Component | Previous | Current | Status |
|-----------|----------|---------|--------|
| Jetty | 10.0.25 | **12.1.4** | Completed |
| Jakarta Servlet API | 5.0.0 | **6.0.0** | Completed |
| Jakarta WebSocket API | 2.0.0 | **2.1.1** | Completed |
| Apache Shiro | 2.0.6 | **1.13.0:jakarta** | Completed |
| Transaction Manager | Bitronix | **Narayana 7.3.3** | Completed |
| Jakarta Activation | N/A | **angus-activation 2.0.3** | Added |
| Jakarta Mail | javax.mail | **jakarta.mail-api 2.1.3** | Completed |
| Commons FileUpload | 1.x | **2.0.0-M2 (jakarta-servlet6)** | Completed |

### Key Changes

1. **Namespace Migration**: All `javax.*` imports converted to `jakarta.*`
2. **Jetty 12 EE10**: Updated session handling APIs, new WebSocket configuration
3. **Shiro 1.13.0:jakarta**: Using Jakarta classifier for servlet compatibility
4. **Narayana TM**: Replaced incompatible Bitronix with Java 21-compatible Narayana
5. **HikariCP**: Added for connection pooling with Narayana
6. **Jetty 12 ProxyServlet**: Updated from `org.eclipse.jetty.proxy` to `org.eclipse.jetty.ee10.proxy`
7. **H2 Console Servlet**: Updated from `org.h2.server.web.WebServlet` to `org.h2.server.web.JakartaWebServlet`

### Servlet Configuration Updates (Runtime Testing - 2025-12-08)

| Servlet | Previous Class | Current Class | Config File |
|---------|---------------|---------------|-------------|
| ElasticSearchProxy | `o.e.jetty.proxy.ProxyServlet$Transparent` | `o.e.jetty.ee10.proxy.ProxyServlet$Transparent` | MoquiDefaultConf.xml |
| KibanaProxy | `o.e.jetty.proxy.ProxyServlet$Transparent` | `o.e.jetty.ee10.proxy.ProxyServlet$Transparent` | MoquiDefaultConf.xml |
| H2Console | `org.h2.server.web.WebServlet` | `org.h2.server.web.JakartaWebServlet` | MoquiDevConf.xml |

### Files Modified
- `framework/build.gradle` - All dependencies updated
- `MoquiShiroRealm.groovy` - Shiro 1.x import paths
- `MoquiStart.java` - Jetty 12 session handling
- `WebFacadeImpl.groovy`, `WebFacadeStub.groovy` - Jakarta servlet imports
- `RestClient.java`, `WebUtilities.java` - Jakarta servlet imports
- `ElFinderConnector.groovy` - Jakarta servlet imports
- `framework/src/main/resources/MoquiDefaultConf.xml` - Jetty 12 EE10 ProxyServlet classes
- `runtime/conf/MoquiDevConf.xml` - H2 JakartaWebServlet for Jakarta EE 10
- **Removed**: `TransactionInternalBitronix.groovy`
- **Added**: `TransactionInternalNarayana.groovy`

---

## 3. Dependency Updates - COMPLETED

### Security-Critical Updates

| Dependency | Previous | Current | Risk Addressed |
|------------|----------|---------|----------------|
| Jackson Databind | 2.18.3 | **2.20.1** | Deserialization vulnerabilities |
| H2 Database | 2.3.232 | **2.4.240** | Security fixes |
| Log4j 2 | 2.24.3 | **2.25.0** | Security updates |
| Commons IO | 2.17.0 | **2.18.0** | Latest stable |
| Commons Lang3 | 3.17.0 | **3.18.0** | Latest stable |
| Commons Codec | 1.17.0 | **1.18.0** | Latest stable |
| JSoup | 1.18.x | **1.19.1** | Security fixes |

### Build Tool Updates

| Tool | Previous | Current |
|------|----------|---------|
| JUnit Platform | 1.11.x | **1.12.1** |
| JUnit Jupiter | 5.11.x | **5.12.1** |
| gradle-versions-plugin | 0.51.0 | **0.52.0** |
| OWASP dependency-check | N/A | **12.1.0** |
| JaCoCo | N/A | **0.8.12** |

### Java Compatibility

```gradle
java {
    sourceCompatibility = 21
    targetCompatibility = 21
}
```

---

## 4. CI/CD Infrastructure - IMPLEMENTED

### GitHub Actions Workflow
**Location**: `.github/workflows/ci.yml`

```yaml
jobs:
  build:
    - Build framework (Java 21, Temurin)
    - Run tests with artifact upload
    - Upload build artifacts

  security-scan:
    - OWASP Dependency Check
    - Upload security reports
```

### Gradle Enhancements

1. **JaCoCo Integration** (Test Coverage)
   - XML and HTML reports
   - 20% minimum coverage threshold (configurable)

2. **OWASP Dependency-Check**
   - Fails on CVSS >= 7.0 (High severity)
   - HTML and JSON report formats

3. **Compiler Warnings**
   - `-Xlint:unchecked` enabled
   - `-Xlint:deprecation` enabled

---

## 5. Testing Infrastructure - IMPROVED

### Test File Growth

| Metric | Previous | Current | Change |
|--------|----------|---------|--------|
| Test Files | 18 | **28** | +55% |
| Test Configuration | Single-threaded | Configurable parallel | Improved |

### New Test Files Added
- `PasswordHasherTests.groovy` - BCrypt password hashing
- `MNodeSecurityTests.groovy` - XXE prevention
- `NarayanaTransactionTests.groovy` - Transaction manager
- `Jetty12IntegrationTests.groovy` - Jetty 12 compatibility
- `SecurityAuthIntegrationTests.groovy` - Authentication flows
- `EntityFacadeCharacterizationTests.groovy` - Entity facade
- `ServiceFacadeCharacterizationTests.groovy` - Service facade
- `ScreenFacadeCharacterizationTests.groovy` - Screen facade
- `RestApiContractTests.groovy` - REST API contracts
- `UserFacadeTests.groovy` - User authentication

### Parallel Test Configuration
```gradle
def forks = project.hasProperty('maxForks') ? project.property('maxForks').toInteger() :
            System.getenv('MAX_TEST_FORKS') ? System.getenv('MAX_TEST_FORKS').toInteger() : 1
maxParallelForks = Math.min(forks, Runtime.runtime.availableProcessors())
```

---

## 6. Code Quality Metrics

### Current State

| Metric | Previous | Current | Target | Status |
|--------|----------|---------|--------|--------|
| Test Coverage | <10% | ~15% (est.) | 60% | In Progress |
| TODO/FIXME Count | 167 | **162** | <50 | Slight Improvement |
| System.out/err Usage | 128 | **132** | 0 | Needs Work |
| Total Lines of Code | ~77,000 | **68,888** | N/A | Reduced |

### God Classes (Still Large)

| Class | Previous Lines | Current Lines | Target |
|-------|----------------|---------------|--------|
| ScreenForm.groovy | 2,683 | **2,538** | <500 |
| ScreenRenderImpl.groovy | 2,451 | **2,451** | <500 |
| EntityFacadeImpl.groovy | 2,312 | **2,181** | <500 |
| ExecutionContextFactoryImpl.groovy | 1,897 | **1,984** | <500 |

---

## 7. Remaining Work - Prioritized Roadmap

### Phase 1: Security Completion (1-2 weeks)
| Priority | Task | Status | Effort |
|----------|------|--------|--------|
| P1 | Verify credentials not logged anywhere | Verify | 1 day |
| P1 | Add Content-Security-Policy header | Pending | 2 days |
| P1 | Add Strict-Transport-Security header | Pending | 1 day |
| P2 | Security audit of all endpoints | Pending | 1 week |

### Phase 2: Code Quality (2-4 weeks)
| Priority | Task | Status | Effort |
|----------|------|--------|--------|
| P2 | Reduce System.out/err to 0 | Pending | 1 week |
| P2 | Resolve TODO/FIXME to <50 | Pending | 2 weeks |
| P2 | Increase test coverage to 30% | In Progress | 3 weeks |
| P3 | Add API documentation | Pending | 2 weeks |

### Phase 3: Architecture Refactoring (4-8 weeks)
| Priority | Task | Status | Effort |
|----------|------|--------|--------|
| P3 | Refactor ScreenForm.groovy (<500 lines) | Pending | 2 weeks |
| P3 | Refactor ScreenRenderImpl.groovy (<500 lines) | Pending | 2 weeks |
| P3 | Refactor EntityFacadeImpl.groovy (<500 lines) | Pending | 2 weeks |
| P3 | Refactor ExecutionContextFactoryImpl.groovy | Pending | 2 weeks |

### Phase 4: Modernization (8-16 weeks)
| Priority | Task | Status | Effort |
|----------|------|--------|--------|
| P3 | Update Groovy 3.0.19 -> 3.0.25 | Pending | 2 weeks |
| P4 | Evaluate Groovy 4.x migration | Pending | 8 weeks |
| P4 | Achieve 60% test coverage | Pending | 8 weeks |
| P4 | Evaluate Shiro 2.x migration | Pending | 4 weeks |

---

## 8. Risk Assessment - Updated

| Risk | Previous | Current | Mitigation |
|------|----------|---------|------------|
| XXE exploitation | HIGH | **ELIMINATED** | Secure parser implemented |
| Password database breach | MEDIUM | **LOW** | BCrypt with cost 12 |
| Session hijacking | MEDIUM | **LOW** | Session regeneration, SameSite |
| Dependency vulnerabilities | HIGH | **LOW** | Updated dependencies, OWASP scans |
| Test coverage gaps | HIGH | **MEDIUM** | CI pipeline, 55% more tests |
| God class maintenance | MEDIUM | **MEDIUM** | No change yet |

---

## 9. Success Criteria - Progress

### Security
- [x] Zero Critical OWASP findings
- [x] XXE vulnerability fixed
- [x] Modern password hashing (BCrypt)
- [x] Session fixation prevented
- [x] CSRF tokens use SecureRandom
- [x] SameSite cookie attributes
- [x] API keys header-only
- [x] Security headers implemented
- [ ] All dependencies free of known CVEs (ongoing)
- [ ] Security headers score A+ (partial)

### Code Quality
- [ ] Test coverage > 60% (currently ~15%)
- [ ] No classes > 500 lines (4 still exceed)
- [ ] TODO count < 50 (currently 162)
- [ ] All System.out replaced (currently 132)

### Infrastructure
- [x] Java 21 compatibility
- [x] Jakarta EE 10 migration
- [x] CI/CD pipeline (GitHub Actions)
- [x] JaCoCo coverage reporting
- [x] OWASP dependency scanning
- [x] Docker support

---

## 10. Verification Commands

```bash
# Run all tests
./gradlew framework:test

# Generate coverage report
./gradlew framework:test jacocoTestReport
# View: framework/build/reports/jacoco/test/html/index.html

# Run security scan
./gradlew dependencyCheckAnalyze
# View: build/reports/dependency-check-report.html

# Check dependency updates
./gradlew dependencyUpdates

# Start server
./gradlew run
# Access: http://localhost:8080
```

---

## 11. Key Files Reference

### Security
- `/framework/src/main/java/org/moqui/util/MNode.java` - XXE protection
- `/framework/src/main/java/org/moqui/util/PasswordHasher.java` - BCrypt hashing
- `/framework/src/main/groovy/org/moqui/impl/context/UserFacadeImpl.groovy` - Session, credentials
- `/framework/src/main/groovy/org/moqui/impl/context/WebFacadeImpl.groovy` - CSRF tokens
- `/framework/src/main/groovy/org/moqui/impl/webapp/MoquiServlet.groovy` - Security headers

### Transaction Management
- `/framework/src/main/groovy/org/moqui/impl/context/TransactionInternalNarayana.groovy` - Narayana TM

### Build Configuration
- `/framework/build.gradle` - Dependencies, plugins, test config
- `/.github/workflows/ci.yml` - CI/CD pipeline

---

## Appendix: Commit History

Key commits since initial evaluation:
```
ced4986a docs: Update SYSTEM_EVALUATION.md with Jakarta EE 10 migration results
7b9f42c6 Merge pull request #61 from hunterino/jakarta-ee10-migration
7f2921de [JAKARTA-EE10] Complete Jakarta EE 10 migration with Jetty 12 and Shiro 1.13.0
0229e353 [DOCKER] Complete Docker epic with containerization support
4db6b4b8 Merge branch 'p1-security-cicd-dependencies'
409a54e2 [ARCH-005] Decouple Service-Entity circular dependency
```

---

**Document Version**: 2.0
**Last Updated**: 2025-12-08
**Author**: Claude Code Analysis