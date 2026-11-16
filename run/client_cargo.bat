@echo off
rem Клиент «Грузы». SERVER_IP – IP компьютера с сервером.
set SERVER_IP=127.0.0.1
cd /d %~dp0..
java -cp "out\production;lib\*" railway.ClientMain %SERVER_IP% cargo pc1
