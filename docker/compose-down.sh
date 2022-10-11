#! /bin/bash

if [[ ! $1 ]]; then
  echo "Usage: ./compose-down.sh <docker compose file>"
  exit 1
fi

COMP_FILE="${1}"

# set the project name to 'moqui', network will be called 'moqui_default'
docker compose -f $COMP_FILE -p moqui down
