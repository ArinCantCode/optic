#!/usr/bin/env bash
sbt fullOptJS
OUTPUT_FILE=../workspaces/domain/src/domain.js

mkdir -p $(dirname $OUTPUT_FILE)

cat target/scala-2.12/optic-core-opt.js > $OUTPUT_FILE

echo "domain logic written to $OUTPUT_FILE"
