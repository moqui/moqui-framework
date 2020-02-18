# Builds a minimal docker image with openjdk and moqui with various volumes for configuration and persisted data outside the container
# NOTE: add components, build and if needed load data before building a docker image with this

FROM openjdk:8-jdk
MAINTAINER Moqui Framework <moqui@googlegroups.com>

WORKDIR /opt/moqui

# for running from the war directly, preffered approach unzips war in advance (see docker-build.sh that does this)
#COPY moqui.war .
# copy files from unzipped moqui.war file
COPY WEB-INF WEB-INF
COPY META-INF META-INF
COPY *.class ./
COPY execlib execlib

# always want the runtime directory
COPY runtime runtime

# exposed as volumes for configuration purposes
VOLUME ["/opt/moqui/runtime/conf", "/opt/moqui/runtime/lib", "/opt/moqui/runtime/classes", "/opt/moqui/runtime/ecomponent"]
# exposed as volumes to persist data outside the container, recommended
VOLUME ["/opt/moqui/runtime/log", "/opt/moqui/runtime/txlog", "/opt/moqui/runtime/sessions", "/opt/moqui/runtime/db", "/opt/moqui/runtime/elasticsearch"]

# Main Servlet Container Port
EXPOSE 80
# ElasticSearch HTTP Port
EXPOSE 9200
# ElasticSearch Cluster (TCP Transport) Port
EXPOSE 9300
# Hazelcast Cluster Port
EXPOSE 5701

# this is to run from the war file directly, preferred approach unzips war file in advance
# ENTRYPOINT ["java", "-jar", "moqui.war"]
ENTRYPOINT ["java", "-cp", ".", "MoquiStart", "port=80"]

HEALTHCHECK --interval=30s --timeout=600ms --start-period=120s CMD curl -f -H "X-Forwarded-Proto: https" -H "X-Forwarded-Ssl: on" http://localhost/status || exit 1
# specify this as a default parameter if none are specified with docker exec/run, ie run production by default
CMD ["conf=conf/MoquiProductionConf.xml"]
