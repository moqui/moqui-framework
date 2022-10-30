#! /bin/bash

echo "Usage: docker-build.sh [<group/name:tag>] [<component set like ecosystem>] [<search name like opensearch or "">] [<build image like gradle:7.4.1-jdk11>] [<runtime image like eclipse-temurin:11-jdk>]"

NAME_TAG="${1:-moqui}"
component_set="${2:-}"
search_name="${3:-opensearch}"
BUILD_IMAGE="${4:-gradle:7.4.1-jdk11}"
RUNTIME_IMAGE="${5:-eclipse-temurin:11-jdk}"

echo; echo "Running: docker-build.sh $NAME_TAG $component_set $search_name $BUILD_IMAGE $RUNTIME_IMAGE"

docker build -t $NAME_TAG --build-arg component_set="$component_set" --build-arg search_name="$search_name" --build-arg BUILD_IMAGE="$BUILD_IMAGE" --build-arg RUNTIME_IMAGE="$RUNTIME_IMAGE" .
