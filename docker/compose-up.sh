#! /bin/bash

if [[ ! $1 ]]; then
  echo "Usage: ./compose-up.sh <docker compose file> [<moqui directory like ../..>] [<runtime image like eclipse-temurin:11-jdk>]"
  exit 1
fi

COMP_FILE="${1}"
MOQUI_HOME="${2:-../..}"
NAME_TAG=moqui
RUNTIME_IMAGE="${3:-eclipse-temurin:11-jdk}"

if [ -f simple/docker-build.sh ]; then
  cd simple
  ./docker-build.sh $MOQUI_HOME $NAME_TAG $RUNTIME_IMAGE
  # shellcheck disable=SC2103
  cd ..
fi

# TODO: For some reason the changes in the Dockerfile made it so that when the runtime/conf volume gets mounted on not from the image which contains the conf files, but from the filesystem which is empty.

# set the project name to 'moqui', network will be called 'moqui_default'
docker-compose -f $COMP_FILE -p moqui up
