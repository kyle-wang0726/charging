@echo off
chcp 65001 >nul
echo 正在释放端口 8080 ...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080 " ^| findstr LISTENING') do (
    taskkill -f -pid %%a >nul 2>&1
)
echo 启动 Spring Boot ...
mvn spring-boot:run
