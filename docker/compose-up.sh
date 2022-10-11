#! /bin/bash

if [[ ! $1 ]]; then
  echo "Usage: ./compose-up.sh <docker compose file> [<moqui directory like ..>] [<runtime image like eclipse-temurin:11-jdk>]"
  exit 1
fi

COMP_FILE="${1}"
MOQUI_HOME="${2:-..}"
NAME_TAG=moqui
RUNTIME_IMAGE="${3:-eclipse-temurin:11-jdk}"

# Note: If you don't have access to your conf directory while running this:
#   This will make it so that your docker/conf directory no longer has your configuration files in it.
#      This is because when docker-compose provisions a volume on the host it applies the host's data before the image's data.
#   - change docker compose's moqui-server conf volume path from ./runtime/conf to conf
#   - add a top level volumes: tag with conf: below
#   - remove the next block of if statements from this file and you should be good to go
search_name=opensearch
if [ -d runtime/opensearch/bin ]; then search_name=opensearch;
elif [ -d runtime/elasticsearch/bin ]; then search_name=elasticsearch;
fi
if [ ! -e runtime ]; then mkdir runtime; fi
if [ ! -e runtime/conf ]; then cp -R $MOQUI_HOME/runtime/conf runtime/; fi
if [ ! -e runtime/lib ]; then cp -R $MOQUI_HOME/runtime/lib runtime/; fi
if [ ! -e runtime/classes ]; then cp -R $MOQUI_HOME/runtime/classes runtime/; fi
if [ ! -e runtime/log ]; then cp -R $MOQUI_HOME/runtime/log runtime/; fi
if [ ! -e runtime/txlog ]; then cp -R $MOQUI_HOME/runtime/txlog runtime/; fi
if [ ! -e runtime/db ]; then cp -R $MOQUI_HOME/runtime/db runtime/; fi
if [ ! -e runtime/$search_name ]; then cp -R $MOQUI_HOME/runtime/$search_name runtime/; fi

# set the project name to 'moqui', network will be called 'moqui_default'
docker compose -f $COMP_FILE -p moqui up -d
