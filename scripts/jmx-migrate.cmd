@echo off
rem Migrates stock JMeter HTTP Request samplers to the BlazeMeter HTTP sampler in a .jmx file
rem or directory. Run from a real JMeter installation with jmeter-bzm-http2-*.jar installed
rem under lib\ext\ (this script assumes it lives at <jmeter.home>\bin\jmx-migrate.cmd).
rem
rem Usage: see `jmx-migrate.cmd --help`

setlocal
set SCRIPT_DIR=%~dp0
set JMETER_HOME_DIR=%SCRIPT_DIR%..

java -Djava.awt.headless=true ^
  -cp "%JMETER_HOME_DIR%\lib\*;%JMETER_HOME_DIR%\lib\ext\*" ^
  com.blazemeter.jmeter.http2.sampler.JmxBlazeMeterHttpMigratorCli %*
