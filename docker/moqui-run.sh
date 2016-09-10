#! /bin/bash

# TODO maybe accept a parameter for the location of the moqui directory (where to find stock runtime), which runtime directory to use, etc...
# overall make more useful for running from an arbitrary location... of course can always be edited for specific use (and generally should be)

if [ ! -e runtime ]; then mkdir runtime; fi
if [ ! -e runtime/conf ]; then cp -R ../runtime/conf runtime/; fi
if [ ! -e runtime/lib ]; then cp -R ../runtime/lib runtime/; fi
if [ ! -e runtime/classes ]; then cp -R ../runtime/classes runtime/; fi
if [ ! -e runtime/log ]; then cp -R ../runtime/log runtime/; fi
if [ ! -e runtime/txlog ]; then cp -R ../runtime/txlog runtime/; fi
if [ ! -e runtime/db ]; then cp -R ../runtime/db runtime/; fi
if [ ! -e runtime/elasticsearch ]; then cp -R ../runtime/elasticsearch runtime/; fi

docker run --rm -p 8080:8080 -v $PWD/runtime/conf:/opt/moqui/runtime/conf -v $PWD/runtime/lib:/opt/moqui/runtime/lib \
    -v $PWD/runtime/classes:/opt/moqui/runtime/classes -v $PWD/runtime/log:/opt/moqui/runtime/log \
    -v $PWD/runtime/txlog:/opt/moqui/runtime/txlog -v $PWD/runtime/db:/opt/moqui/runtime/db \
    -v $PWD/runtime/elasticsearch:/opt/moqui/runtime/elasticsearch \
    moqui
# docker run -d -p 8080:8080 moqui
# docker run --rm -p 8080:8080 moqui
