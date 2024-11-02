SET image_name=%~1
SET docker_repo=%~2
SET image_version=%~3
SET dockerfile=%~4

REM allow changed dir
IF NOT DEFINED _DOCKER_BUILD_DIR (SET _DOCKER_BUILD_DIR=..\db)
pushd %_DOCKER_BUILD_DIR%
ECHO Docker build dir %cd%

ECHO Remove existing image
docker rmi %image_name%:%image_version%

REM customized docker build command
ECHO Building Docker image '%image_name%:%image_version%' into '%docker_repo%'
IF DEFINED _APP_BUILD (SET app_build=%_APP_BUILD%) ELSE (SET app_build=0)
IF %app_build%==0 (docker build -t %image_name%:%image_version% -f %dockerfile% .) ELSE (call docker-build.bat ../.. %image_name% %image_version% %dockerfile%)

IF DEFINED _PUBLISH_REPO_IMAGE (SET publish_azure=%_PUBLISH_REPO_IMAGE%) ELSE (SET publish_azure=1)
IF %publish_azure%==1 ( ECHO Publishing Docker image && docker tag %image_name%:%image_version% %docker_repo%/%image_name%:%image_version% && docker push %docker_repo%/%image_name%:%image_version% )

popd