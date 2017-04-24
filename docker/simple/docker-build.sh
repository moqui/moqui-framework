#! /bin/bash

echo "Usage: docker-build.sh [<moqui directory like ../..>] [<group/name:tag>]"

MOQUI_HOME="${1:-../..}"
NAME_TAG="${2:-moqui}"

if [ -f $MOQUI_HOME/moqui-plus-runtime.war ]
then
  echo "Building docker image from moqui-plus-runtime.war"
  echo
  unzip -q $MOQUI_HOME/moqui-plus-runtime.war
elif [ -f $MOQUI_HOME/moqui.war ]
then
  echo "Building docker image from moqui.war and runtime directory"
  echo "NOTE: this includes everything in the runtime directory, it is better to run 'gradle addRuntime' first and use the moqui-plus-runtime.war file for the docker image"
  echo
  unzip -q $MOQUI_HOME/moqui.war
  cp -R $MOQUI_HOME/runtime .
else
    echo "Could not find $MOQUI_HOME/moqui-plus-runtime.war or $MOQUI_HOME/moqui.war"
    echo "Build moqui first, for example 'gradle build addRuntime' or 'gradle load addRuntime'"
    echo
    exit 1
fi

docker build -t $NAME_TAG .

rm -Rf META-INF WEB-INF execlib
rm *.class
rm -Rf runtime
