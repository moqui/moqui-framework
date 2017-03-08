#! /bin/bash

if [[ ! $1 ]]; then
  echo "Usage: ./compose-up.sh <docker compose file> [<moqui directory>]"
  exit 1
fi

COMP_FILE="${1}"
MOQUI_HOME="${2:-..}"

if [ ! -e runtime ]; then mkdir runtime; fi
if [ ! -e runtime/conf ]; then cp -R $MOQUI_HOME/runtime/conf runtime/; fi
if [ ! -e runtime/lib ]; then cp -R $MOQUI_HOME/runtime/lib runtime/; fi
if [ ! -e runtime/classes ]; then cp -R $MOQUI_HOME/runtime/classes runtime/; fi
if [ ! -e runtime/log ]; then cp -R $MOQUI_HOME/runtime/log runtime/; fi
if [ ! -e runtime/txlog ]; then cp -R $MOQUI_HOME/runtime/txlog runtime/; fi
if [ ! -e runtime/db ]; then cp -R $MOQUI_HOME/runtime/db runtime/; fi
if [ ! -e runtime/elasticsearch ]; then cp -R $MOQUI_HOME/runtime/elasticsearch runtime/; fi

# set the project name to 'moqui', network will be called 'moqui_default'
docker-compose -f $COMP_FILE -p moqui up -d
