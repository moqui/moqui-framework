#! /bin/bash

if [[ ! $1 ]]; then
  echo "Usage: ./build-compose-up.sh <docker compose file> [<moqui directory like ..>] [<runtime image like eclipse-temurin:11-jdk>]"
  exit 1
fi

COMP_FILE="${1}"
MOQUI_HOME="${2:-..}"
NAME_TAG=moqui
RUNTIME_IMAGE="${3:-eclipse-temurin:11-jdk}"

if [ -f simple/docker-build.sh ]; then
  cd simple
  ./docker-build.sh ../.. $NAME_TAG $RUNTIME_IMAGE
  # shellcheck disable=SC2103
  cd ..
fi

if [ -f compose-up.sh ]; then
  ./compose-up.sh $COMP_FILE $MOQUI_HOME $RUNTIME_IMAGE
fi
