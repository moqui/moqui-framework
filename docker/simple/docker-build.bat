ECHO OFF
REM "Usage: docker-build.sh [<moqui directory like ../..>] [<group/name:tag>]"
SET MOQUI_HOME=%~1
SET NAME_TAG=%~2
SET VERSION=%~3
SET DOCKERFILE=%~4

IF "%VERSION%"=="" SET VERSION=latest

REM "Building docker image from moqui-plus-runtime.war"
7z x %MOQUI_HOME%\moqui-plus-runtime.war

REM docker build - < Dockerfile -t %NAME_TAG%
IF NOT DEFINED DOCKERFILE ( ECHO Building the standard way && docker build -t %NAME_TAG%:%VERSION% . ) ELSE ( ECHO Building using specified docker file '%DOCKERFILE%' && docker build -t %NAME_TAG%:%VERSION% -f %DOCKERFILE% . )

REM delete all remains
rmdir /Q /S META-INF 
rmdir /Q /S WEB-INF 
rmdir /Q /S execlib
rmdir /Q /S runtime
del *.class
del Procfile
