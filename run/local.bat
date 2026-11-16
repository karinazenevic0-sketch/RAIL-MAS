@echo off
cd /d %~dp0..
java -cp "out\production;lib\*" railway.LocalMain
