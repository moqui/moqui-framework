#! /bin/bash

echo "Usage: docker-build.sh [<moqui directory like ../..>] [<group/name:tag>] [<runtime image like eclipse-temurin:21-jdk>]"

MOQUI_HOME="${1:-../..}"
NAME_TAG="${2:-moqui}"
RUNTIME_IMAGE="${3:-eclipse-temurin:21-jdk}"

if [ ! "$1" ]; then
  echo "Usage: docker-build.sh [<moqui directory like ../..>] [<group/name:tag>] [<runtime image like eclipse-temurin:21-jdk>]"
else
  echo "Running: docker-build.sh $MOQUI_HOME $NAME_TAG $RUNTIME_IMAGE"
fi
echo

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

# delete test Python venvs from the copied runtime
rm -rf runtime/python_venv runtime/**/venv runtime/**/.venv

# Auto-detect moqui-jep component: if present, enable Python/JEP in the image
WITH_JEP=false
if [ -d runtime/component/moqui-jep ]; then
  echo "moqui-jep component detected: Python/JEP support will be included in the image"
  WITH_JEP=true
fi

docker build -t $NAME_TAG --build-arg RUNTIME_IMAGE=$RUNTIME_IMAGE --build-arg WITH_JEP=$WITH_JEP .

if [ -d META-INF ]; then rm -Rf META-INF; fi
if [ -d WEB-INF ]; then rm -Rf WEB-INF; fi
if [ -d execlib ]; then rm -Rf execlib; fi
rm *.class
if [ -d runtime ]; then rm -Rf runtime; fi
if [ -f Procfile ]; then rm Procfile; fi
