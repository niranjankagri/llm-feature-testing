@echo off
rem CLI: llm ask alice How many days do I have left? ^| calibrate ^| eval [--samples N] [--tag T] [--case ID]
call "%~dp0mvnw.cmd" -q -B -f "%~dp0pom.xml" test-compile exec:exec@cli -Dcli="%*"
