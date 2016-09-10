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

docker-compose -f moqui-mysql-compose.yml up
