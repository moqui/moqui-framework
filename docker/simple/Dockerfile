# Builds a minimal docker image with openjdk and moqui with various volumes for configuration and persisted data outside the container
# NOTE: add components, build and if needed load data before building a docker image with this
ARG RUNTIME_IMAGE=eclipse-temurin:21-jdk
FROM ${RUNTIME_IMAGE}
MAINTAINER Moqui Framework <moqui@googlegroups.com>

WORKDIR /opt/moqui

# for running from the war directly, preferred approach unzips war in advance (see docker-build.sh that does this)
# COPY moqui.war .
# copy files from unzipped moqui.war file
COPY WEB-INF WEB-INF
COPY META-INF META-INF
COPY *.class ./
COPY execlib execlib

# always want the runtime directory
COPY runtime runtime
RUN find /opt/moqui/runtime -type d \( -name 'python_venv' -o -name 'venv' -o -name '.venv' \) -prune -exec rm -rf {} +

# curl and ca-certificates are always needed (HEALTHCHECK + HTTPS calls)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Optional: Python + JEP (Java Embedded Python) support.
# Enabled automatically when the moqui-jep component is present in the build
# (docker-build.sh detects runtime/component/moqui-jep and passes WITH_JEP=true).
# Can also be forced manually: docker build --build-arg WITH_JEP=true ...
ARG WITH_JEP=false
RUN if [ "$WITH_JEP" = "true" ]; then \
      apt-get update \
      && apt-get install -y --no-install-recommends \
         python3 python3-venv python3-pip python3-dev build-essential \
      && rm -rf /var/lib/apt/lists/* \
      && python3 -m venv /opt/moqui/runtime/python_venv \
      && /opt/moqui/runtime/python_venv/bin/python -m pip install --upgrade pip \
      && /opt/moqui/runtime/python_venv/bin/pip install --no-cache-dir numpy \
      && /opt/moqui/runtime/python_venv/bin/pip install --no-cache-dir jep==4.2.2; \
    fi

ENV PYTHONNOUSERSITE=1
ENV PYTHONPATH=

# create user for search and chown corresponding files
ARG search_name=opensearch

RUN if [ -d runtime/opensearch/bin ]; then echo "Installing OpenSearch User"; \
      search_name=opensearch; \
      groupadd -g 1000 opensearch 2>/dev/null || echo "group 1000 already exists" && \
      useradd -u 1000 -g 1000 -G 0 -d /opt/moqui/runtime/opensearch opensearch 2>/dev/null || echo "user 1000 already exists" && \
      chmod 0775 /opt/moqui/runtime/opensearch && \
      chown -R 1000:0 /opt/moqui/runtime/opensearch; \
    elif [ -d runtime/elasticsearch/bin ]; then echo "Installing ElasticSearch User"; \
      search_name=elasticsearch; \
      groupadd -r elasticsearch && \
      useradd --no-log-init -r -g elasticsearch -d /opt/moqui/runtime/elasticsearch elasticsearch && \
      chown -R elasticsearch:elasticsearch runtime/elasticsearch; \
    fi

# exposed as volumes for configuration purposes
VOLUME ["/opt/moqui/runtime/conf", "/opt/moqui/runtime/lib", "/opt/moqui/runtime/classes", "/opt/moqui/runtime/ecomponent"]
# exposed as volumes to persist data outside the container, recommended
VOLUME ["/opt/moqui/runtime/log", "/opt/moqui/runtime/txlog", "/opt/moqui/runtime/sessions", "/opt/moqui/runtime/db", "/opt/moqui/runtime/$search_name"]

# Main Servlet Container Port
EXPOSE 80
# Search HTTP Port
EXPOSE 9200
# Search Cluster (TCP Transport) Port
EXPOSE 9300
# Hazelcast Cluster Port
EXPOSE 5701

# Copy the entrypoint script
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]

HEALTHCHECK --interval=30s --timeout=2s --start-period=120s CMD curl -f -H "X-Forwarded-Proto: https" -H "X-Forwarded-Ssl: on" http://localhost/status || exit 1
# specify this as a default parameter if none are specified with docker exec/run, ie run production by default
CMD ["conf=conf/MoquiProductionConf.xml", "port=80"]
