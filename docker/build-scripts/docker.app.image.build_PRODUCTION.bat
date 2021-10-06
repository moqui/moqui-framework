@echo off

SET _DOCKERFILE=Dockerfile-alpine
SET _IMAGE_VERSION=1.0.4
SET _IMAGE_NAME=moqui/moqui-master
SET _REPO_NAME=dataquest.azurecr.io
REM SET _REPO_NAME=rskdev.azurecr.io
SET _APP_BUILD=1
SET _PUBLISH_REPO_IMAGE=0
SET _DOCKER_BUILD_DIR=..\simple

REM login to azure
REM cmd /c "az acr login -n rskdev"

create.docker.app.image.bat