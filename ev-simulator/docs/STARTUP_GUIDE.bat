@echo off
setlocal

echo GridGarrison Person 1 Startup (current)
echo.
echo 1) Build
echo   cd c:\Users\jujhar\Videos\GGHackathon
echo   mvn -DskipTests clean compile
echo   cd ev-simulator\simulator-app
echo   mvn -DskipTests clean compile
echo.
echo 2) Run backend (port 8443)
echo   cd c:\Users\jujhar\Videos\GGHackathon
echo   mvn spring-boot:run
echo.
echo 3) Run simulator (default 8080; use 8082 if 8080 busy)
echo   cd c:\Users\jujhar\Videos\GGHackathon\ev-simulator\simulator-app
echo   mvn spring-boot:run
echo.
echo 4) Smoke test
echo   Invoke-RestMethod -Uri "http://localhost:8080/api/ev/status" -Method Get
echo   Invoke-RestMethod -Uri "http://localhost:8080/api/ev/scenario/run?name=reconnectLoop" -Method Post
echo   Invoke-RestMethod -Uri "http://localhost:8080/api/ev/scenario/stop" -Method Post

endlocal
