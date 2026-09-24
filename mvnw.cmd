@echo off
setlocal
set MAVEN_VERSION=3.9.9
set DIST_NAME=apache-maven-%MAVEN_VERSION%
set ARCHIVE_NAME=%DIST_NAME%-bin.tar.gz
set MAVEN_USER_HOME=%USERPROFILE%\.m2
set DIST_DIR=%MAVEN_USER_HOME%\wrapper\dists\%DIST_NAME%
set ARCHIVE_PATH=%DIST_DIR%\%ARCHIVE_NAME%
set CHECKSUM=a555254d6b53d267965a3404ecb14e53c3827c09c3b94b5678835887ab404556bfaf78dcfe03ba76fa2508649dca8531c74bca4d5846513522404d48e8c4ac8b
set BASE_DIR=%~dp0
set MAVEN_CMD=%DIST_DIR%\bin\mvn.cmd
if exist "%MAVEN_CMD%" goto run
mkdir "%DIST_DIR%"
curl.exe --fail --location --silent --show-error "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/%ARCHIVE_NAME%" --output "%ARCHIVE_PATH%"
for /f "tokens=1" %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA512 '%ARCHIVE_PATH%').Hash.ToLower()"') do set HASH=%%H
if not "%HASH%"=="%CHECKSUM%" exit /b 1
tar -xzf "%ARCHIVE_PATH%" -C "%DIST_DIR%" --strip-components=1
del "%ARCHIVE_PATH%"
:run
call "%MAVEN_CMD%" -f "%BASE_DIR%pom.xml" %*
exit /b %ERRORLEVEL%
