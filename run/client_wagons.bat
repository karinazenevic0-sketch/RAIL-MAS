@echo off
rem Клиент «Вагоны» (на втором компьютере укажите префикс pc2 и свой IP 4-м аргументом).
set SERVER_IP=127.0.0.1
cd /d %~dp0..
java -cp "out\production;lib\*" railway.ClientMain %SERVER_IP% wagons pc2
