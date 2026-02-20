# JETTY-002 Migration Errors Documentation

This document captures the compilation errors from upgrading to Jetty 12.1.4, which requires migrating from `javax.*` to `jakarta.*` namespaces.

## Summary

- **Total Errors**: 92 compilation errors
- **Root Cause**: Jetty 12 uses Jakarta EE 10 (jakarta namespace) instead of Java EE (javax namespace)

## Error Categories

### 1. javax.servlet -> jakarta.servlet

**Files Affected**:
- `org/moqui/context/ExecutionContextFactory.java`
- `org/moqui/context/ExecutionContext.java`
- `org/moqui/context/WebFacade.java`
- `org/moqui/screen/ScreenRender.java`
- `org/moqui/util/WebUtilities.java`
- `org/moqui/Moqui.java`

**Imports to Change**:
```java
// OLD
import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

// NEW
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
```

### 2. javax.websocket -> jakarta.websocket

**Files Affected**:
- `org/moqui/context/ExecutionContextFactory.java`

**Imports to Change**:
```java
// OLD
import javax.websocket.server.ServerContainer;

// NEW
import jakarta.websocket.server.ServerContainer;
```

### 3. javax.activation -> jakarta.activation

**Files Affected**:
- `org/moqui/context/ResourceFacade.java`
- `org/moqui/resource/ResourceReference.java`

**Imports to Change**:
```java
// OLD
import javax.activation.DataSource;
import javax.activation.MimetypesFileTypeMap;

// NEW
import jakarta.activation.DataSource;
import jakarta.activation.MimetypesFileTypeMap;
```

### 4. Jetty Client API Changes

**Files Affected**:
- `org/moqui/util/RestClient.java`
- `org/moqui/util/WebUtilities.java`

**Package Restructuring in Jetty 12**:
```java
// OLD (Jetty 10)
import org.eclipse.jetty.client.api.*;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.*;
import org.eclipse.jetty.client.util.StringContentProvider;

// NEW (Jetty 12) - API restructured
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.client.StringRequestContent;
```

**Notable API Changes**:
- `StringContentProvider` -> `StringRequestContent`
- `HttpClientTransportDynamic` moved to `org.eclipse.jetty.client.transport`
- `Response.CompleteListener` interface changes
- `HttpCookieStore.Empty` location changed

## Groovy Files Also Affected

The same namespace changes apply to Groovy files in:
- `framework/src/main/groovy/org/moqui/impl/webapp/`
- `framework/src/main/groovy/org/moqui/impl/context/`
- `framework/src/main/groovy/org/moqui/impl/screen/`

## Migration Strategy for JETTY-002

1. **Bulk Replace** - Use find/replace across all files:
   - `javax.servlet` -> `jakarta.servlet`
   - `javax.websocket` -> `jakarta.websocket`
   - `javax.activation` -> `jakarta.activation`
   - `javax.mail` -> `jakarta.mail`

2. **Jetty Client Refactoring** - Manual updates needed for:
   - `RestClient.java` - Update to new Jetty 12 client API
   - `WebUtilities.java` - Update HTTP client usage

3. **Testing** - Run full test suite after migration

## Dependencies Updated in JETTY-001

```gradle
// API Dependencies
jakarta.servlet:jakarta.servlet-api:6.0.0
jakarta.websocket:jakarta.websocket-api:2.1.1
jakarta.activation:jakarta.activation-api:2.1.3

// Jetty Core
org.eclipse.jetty:jetty-server:12.1.4
org.eclipse.jetty:jetty-client:12.1.4
org.eclipse.jetty:jetty-jndi:12.1.4

// Jetty EE10 (Jakarta EE 10)
org.eclipse.jetty.ee10:jetty-ee10-webapp:12.1.4
org.eclipse.jetty.ee10:jetty-ee10-proxy:12.1.4
org.eclipse.jetty.ee10.websocket:jetty-ee10-websocket-jakarta-server:12.1.4
org.eclipse.jetty.ee10.websocket:jetty-ee10-websocket-jakarta-client:12.1.4
org.eclipse.jetty.ee10.websocket:jetty-ee10-websocket-jetty-server:12.1.4

// Mail
org.eclipse.angus:angus-mail:2.0.3
```
