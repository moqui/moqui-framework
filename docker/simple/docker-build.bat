ECHO OFF
REM "Usage: docker-build.sh [<moqui directory like ../..>] [<group/name:tag>]"
SET MOQUI_HOME=%~1
SET NAME_TAG=%~2

REM "Building docker image from moqui-plus-runtime.war"
7z x %MOQUI_HOME%\moqui-plus-runtime.war

REM docker build - < Dockerfile -t %NAME_TAG%
REM cd docker\simple
docker build -t %NAME_TAG% .

REM delete all remains
rmdir /Q /S META-INF 
rmdir /Q /S WEB-INF 
rmdir /Q /S execlib
rmdir /Q /S runtime
del *.class
del Procfile

REM delete runtime
cd ..
rmdir /Q /S runtime

REM prepare database
cd ..
gradlew build -x test