@echo off
rem Сборка без IDE. Нужны JDK 21+ и lib\jade.jar, lib\gson-2.13.1.jar
dir /s /b src\*.java > sources.txt
if not exist out\production mkdir out\production
javac -encoding UTF-8 -d out\production -cp "lib\*" @sources.txt
