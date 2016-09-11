#! /bin/bash

MOQUI_HOME="${1:-..}"

if [ ! -e runtime ]; then mkdir runtime; fi
if [ ! -e runtime/conf ]; then cp -R $MOQUI_HOME/runtime/conf runtime/; fi
if [ ! -e runtime/lib ]; then cp -R $MOQUI_HOME/runtime/lib runtime/; fi
if [ ! -e runtime/classes ]; then cp -R $MOQUI_HOME/runtime/classes runtime/; fi
if [ ! -e runtime/log ]; then cp -R $MOQUI_HOME/runtime/log runtime/; fi
if [ ! -e runtime/txlog ]; then cp -R $MOQUI_HOME/runtime/txlog runtime/; fi
if [ ! -e runtime/db ]; then cp -R $MOQUI_HOME/runtime/db runtime/; fi
if [ ! -e runtime/elasticsearch ]; then cp -R $MOQUI_HOME/runtime/elasticsearch runtime/; fi

docker run --rm -p 8080:8080 -v $PWD/runtime/conf:/opt/moqui/runtime/conf -v $PWD/runtime/lib:/opt/moqui/runtime/lib \
    -v $PWD/runtime/classes:/opt/moqui/runtime/classes -v $PWD/runtime/log:/opt/moqui/runtime/log \
    -v $PWD/runtime/txlog:/opt/moqui/runtime/txlog -v $PWD/runtime/db:/opt/moqui/runtime/db \
    -v $PWD/runtime/elasticsearch:/opt/moqui/runtime/elasticsearch \
    --name=moqui-server moqui
# docker run -d -p 8080:8080 moqui
# docker run --rm -p 8080:8080 moqui
