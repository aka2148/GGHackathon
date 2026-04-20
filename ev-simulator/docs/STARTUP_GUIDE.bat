@echo off
setlocal

echo GridGarrison Person 1 Startup (current)
echo.
echo 1) Build
echo   cd ^<repo-root^>
echo   mvn -DskipTests compile
echo   mvn -f ev-simulator/simulator-app/pom.xml -DskipTests compile
echo.
echo 2) Run backend (demo-mtls, port 8443)
echo   cd ^<repo-root^>
echo   . .\scripts\local-env.ps1
echo   mvn spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"
echo.
echo 3) Run simulator (port 8082)
echo   cd ^<repo-root^>
echo   mvn -f ev-simulator/simulator-app/pom.xml spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"
echo.
echo 4) Smoke test
echo   Invoke-RestMethod -Method Get  -Uri "http://localhost:8082/api/ev/status"
echo   Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/ev/user/flow/reset?clearIntent=true^&resetWallet=false^&resetBattery=false"
echo   Invoke-RestMethod -Method Get  -Uri "http://localhost:8082/api/ev/user/flow/status?refreshTrust=true"
echo.
echo 5) Open dashboards
echo   https://localhost:8443/visualizer.html
echo   https://localhost:8443/panel.html
echo   http://localhost:8082/ev-dashboard.html

endlocal
