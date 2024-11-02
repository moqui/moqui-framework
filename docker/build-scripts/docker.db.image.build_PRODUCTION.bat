@ECHO OFF
SET _PYTHON_EXEC="python3"
SET _PGDUMP_EXEC="C:\Program Files\PostgreSQL\12\bin\pg_dump.exe"
SET _PUBLISH_REPO_IMAGE=0
SET _DOCKERFILE=Dockerfile.db
SET _IMAGE_VERSION=1.0.1
SET _IMAGE_NAME=moqui/moqui-master-db
SET _REPO_NAME=dataquest.azurecr.io
REM SET _REPO_NAME=rskdev.azurecr.io
SET _DOCKER_BUILD_DIR=..\db

create.docker.db.image.bat