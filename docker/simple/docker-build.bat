ECHO OFF
REM "Usage: docker-build.sh [<moqui directory like ../..>] [<group/name:tag>]"
SET MOQUI_HOME=%~1
SET NAME_TAG=%~2
SET REPO_NAME=%~3
SET VERSION=%~4

IF "%VERSION%"=="" (SET VERSION=latest)

REM "Building docker image from moqui-plus-runtime.war"
7z x %MOQUI_HOME%\moqui-plus-runtime.war

REM docker build - < Dockerfile -t %NAME_TAG%
docker build -t %NAME_TAG%:%VERSION%  .
IF NOT "%REPO_NAME%"=="" (docker tag %NAME_TAG%:%VERSION% %REPO_NAME%/%NAME_TAG%:%VERSION% && docker push %REPO_NAME%/%NAME_TAG%:%VERSION%)

REM delete all remains
rmdir /Q /S META-INF 
rmdir /Q /S WEB-INF 
rmdir /Q /S execlib
rmdir /Q /S runtime
del *.class
del Procfile