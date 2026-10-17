#!/usr/bin/env sh
# CLI: ./llm.sh ask alice How many days do I have left? | calibrate | eval [--samples N] [--tag T] [--case ID]
cd "$(dirname "$0")" && exec ./mvnw -q -B test-compile exec:exec@cli -Dcli="$*"
