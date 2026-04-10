#!/bin/zsh
# Moqui Framework launcher optimized for Apple M1 Max (ARM64)

export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# M1 Max optimized JVM flags:
#  - ZGC: low-latency garbage collector, excellent on ARM64
#  - 4GB heap (M1 Max has plenty of RAM, adjust as needed)
#  - UseTransparentHugePages not available on macOS, using large pages where possible
#  - Parallel GC threads tuned for M1 Max (10 cores)
exec "$JAVA_HOME/bin/java" \
  -XX:+UseZGC \
  -Xms1g -Xmx4g \
  -XX:+AlwaysPreTouch \
  -XX:ParallelGCThreads=8 \
  -Dfile.encoding=UTF-8 \
  -jar moqui.war
