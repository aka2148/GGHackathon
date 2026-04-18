#!/bin/bash
set -e

echo "GridGarrison Person 1 Startup (current)"
echo ""

echo "1) Build"
echo "cd /path/to/GGHackathon && mvn -DskipTests clean compile"
echo "cd /path/to/GGHackathon/ev-simulator/simulator-app && mvn -DskipTests clean compile"
echo ""
echo "2) Run backend (port 8443)"
echo "cd /path/to/GGHackathon && mvn spring-boot:run"
echo ""
echo "3) Run simulator (default 8080; use 8082 if 8080 busy)"
echo "cd /path/to/GGHackathon/ev-simulator/simulator-app && mvn spring-boot:run"
echo ""
echo "4) Smoke test"
echo "curl http://localhost:8080/api/ev/status"
echo "curl -X POST 'http://localhost:8080/api/ev/scenario/run?name=reconnectLoop'"
echo "curl -X POST 'http://localhost:8080/api/ev/scenario/stop'"
