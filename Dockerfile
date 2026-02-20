# Moqui Framework Production Dockerfile
# Multi-stage build for optimized image size
# Uses Java 21 LTS with Eclipse Temurin

# ============================================================================
# Build Stage - Compiles the application and creates the WAR
# ============================================================================
FROM eclipse-temurin:21-jdk-alpine AS builder

# Install build dependencies
RUN apk add --no-cache bash git

WORKDIR /build

# Copy Gradle wrapper and build files first (for better caching)
COPY gradlew gradlew.bat gradle.properties settings.gradle build.gradle ./
COPY gradle/ gradle/

# Copy source code
COPY framework/ framework/
COPY runtime/ runtime/

# Build the WAR file with runtime included
RUN chmod +x gradlew && \
    ./gradlew --no-daemon addRuntime && \
    # Unzip the WAR for faster startup
    mkdir -p /app && \
    cd /app && \
    unzip /build/moqui-plus-runtime.war

# ============================================================================
# Runtime Stage - Minimal production image
# ============================================================================
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Moqui Framework <moqui@googlegroups.com>" \
      version="3.0.0" \
      description="Moqui Framework - Enterprise Application Development" \
      org.opencontainers.image.source="https://github.com/moqui/moqui-framework"

# Install runtime dependencies
RUN apk add --no-cache \
    curl \
    tzdata \
    && rm -rf /var/cache/apk/*

# Create non-root user for security
RUN addgroup -g 1000 -S moqui && \
    adduser -u 1000 -S moqui -G moqui

WORKDIR /opt/moqui

# Copy application from builder
COPY --from=builder --chown=moqui:moqui /app/ .

# Create necessary directories with correct permissions
RUN mkdir -p runtime/log runtime/txlog runtime/sessions runtime/db && \
    chown -R moqui:moqui runtime/

# Switch to non-root user
USER moqui

# Configuration volumes
VOLUME ["/opt/moqui/runtime/conf", "/opt/moqui/runtime/lib", "/opt/moqui/runtime/classes", "/opt/moqui/runtime/ecomponent"]

# Data persistence volumes
VOLUME ["/opt/moqui/runtime/log", "/opt/moqui/runtime/txlog", "/opt/moqui/runtime/sessions", "/opt/moqui/runtime/db"]

# Main application port
EXPOSE 8080

# Environment variables with sensible defaults
ENV JAVA_TOOL_OPTIONS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=100" \
    MOQUI_RUNTIME_CONF="conf/MoquiProductionConf.xml" \
    TZ="UTC"

# Health check using the /health/ready endpoint
# start-period allows for slow startup (loading data, etc.)
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD curl -f http://localhost:8080/health/ready || exit 1

# Start Moqui using the MoquiStart class
ENTRYPOINT ["java", "-cp", ".", "MoquiStart"]

# Default command (can be overridden)
CMD ["port=8080", "conf=conf/MoquiProductionConf.xml"]
