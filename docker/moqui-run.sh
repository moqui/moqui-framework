#! /bin/bash

echo "Usage: moqui-run.sh [<moqui directory like ../..>] [<group/name:tag>] [<runtime image like eclipse-temurin:11-jdk>]"
echo

MOQUI_HOME="${1:-../..}"
NAME_TAG="${2:-moqui}"
RUNTIME_IMAGE="${3:-eclipse-temurin:11-jdk}"

if [ -f simple/docker-build.sh ]; then
  cd simple
  ./docker-build.sh $MOQUI_HOME $NAME_TAG $RUNTIME_IMAGE
  # shellcheck disable=SC2103
  cd ..
  docker run --rm -p 80:80 $NAME_TAG
fi
