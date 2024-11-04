@echo off

REM Set JAVA_HOME to JAVA 11
call config.bat

REM move one directory up
pushd ..

REM Clean database
call gradlew cleanDb
if %errorlevel% neq 0 (
    echo "Error: gradlew cleanDb failed"
    exit /b %errorlevel%
)

REM Load
call gradlew load
if %errorlevel% neq 0 (
    echo "Error: gradlew load failed"
    exit /b %errorlevel%
)

REM MoquiSuite
call gradlew test --tests MoquiSuite -Pmoqui.log.directory=log/moqui-suite-logs
if %errorlevel% neq 0 (
    echo "Error: gradlew test --tests MoquiSuite failed"
    popd
    exit /b %errorlevel%
)

REM BulkEntityTester
call gradlew test --tests ars.rockycube.BulkEntityTester -Pmoqui.log.directory=log/bulk-entity
if %errorlevel% neq 0 (
    echo "Error: gradlew test --tests ars.rockycube.BulkEntityTester failed"
    popd
    exit /b %errorlevel%
)

REM ComplexEntitiesTester
call gradlew test --tests ars.rockycube.ComplexEntitiesTester -Pmoqui.log.directory=log/complex-entities
if %errorlevel% neq 0 (
    echo "Error: gradlew test --tests ars.rockycube.ComplexEntitiesTester failed"
    popd
    exit /b %errorlevel%
)

REM DynamicRelationshipTester
call gradlew test -Pmoqui.log.directory=log/dynamic-relationships --tests ars.rockycube.DynamicRelationshipTester
if %errorlevel% neq 0 (
    echo "Error: gradlew test --tests ars.rockycube.DynamicRelationshipTester failed"
    popd
    exit /b %errorlevel%
)

REM PersonEntityTester
call gradlew test --tests ars.rockycube.PersonEntityTester -Pmoqui.log.directory=log/person-entity
if %errorlevel% neq 0 (
    echo "Error: gradlew test --tests ars.rockycube.PersonEntityTester failed"
    popd
    exit /b %errorlevel%
)

REM SmartFindTester
call gradlew test --tests ars.rockycube.SmartFindTester -Pmoqui.log.directory=log/smart-find
if %errorlevel% neq 0 (
    echo "Error: gradlew test --tests ars.rockycube.SmartFindTester failed"
    popd
    exit /b %errorlevel%
)

echo "All tasks executed successfully"
popd
pause