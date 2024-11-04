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
call gradlew test --tests MoquiSuite
if %errorlevel% neq 0 (
    echo "Error: gradlew test --tests MoquiSuite failed"
    exit /b %errorlevel%
)

REM BulkEntityTester
call gradlew test --tests ars.rockycube.BulkEntityTester
if %errorlevel% neq 0 (
    echo "Error: gradlew test --tests ars.rockycube.BulkEntityTester failed"
    exit /b %errorlevel%
)

REM ComplexEntitiesTester
call gradlew test --tests ars.rockycube.ComplexEntitiesTester
if %errorlevel% neq 0 (
    echo "Error: gradlew test --tests ars.rockycube.ComplexEntitiesTester failed"
    exit /b %errorlevel%
)

REM ComplexEntitiesTester
call gradlew test --tests ars.rockycube.DynamicRelationshipTester
if %errorlevel% neq 0 (
    echo "Error: gradlew test --tests ars.rockycube.DynamicRelationshipTester failed"
    exit /b %errorlevel%
)

REM ComplexEntitiesTester
call gradlew test --tests ars.rockycube.PersonEntityTester
if %errorlevel% neq 0 (
    echo "Error: gradlew test --tests ars.rockycube.PersonEntityTester failed"
    exit /b %errorlevel%
)

REM ComplexEntitiesTester
call gradlew test --tests ars.rockycube.SmartFindTester
if %errorlevel% neq 0 (
    echo "Error: gradlew test --tests ars.rockycube.SmartFindTester failed"
    exit /b %errorlevel%
)

echo "All tasks executed successfully"
pause