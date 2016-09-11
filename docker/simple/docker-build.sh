#! /bin/bash

if [ -f ../../moqui-plus-runtime.war ]; then
    echo "Building docker image from moqui-plus-runtime.war"
    unzip -q ../../moqui-plus-runtime.war
  elif [ -f ../../moqui.war ]; then
    echo "Building docker image from moqui.war and runtime directory"
    echo "NOTE: this includes everything in the runtime directory, it is better to run 'gradle addRuntime' first and use the moqui-plus-runtime.war file for the docker image"
    unzip -q ../../moqui.war
    cp -R ../../runtime .
  else
    echo "Could not find moqui-plus-runtime.war or moqui.war"
    exit 1
fi

docker build -t moqui .

rm -Rf META-INF WEB-INF execlib
rm *.class
rm -Rf runtime
