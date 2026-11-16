@echo off
rem Компьютер 1. Впишите свой IP (ipconfig) или оставьте пустым.
set MY_IP=
cd /d %~dp0..
java -cp "out\production;lib\*" railway.ServerMain %MY_IP% pc1
