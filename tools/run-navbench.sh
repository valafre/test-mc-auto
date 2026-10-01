#!/bin/sh
# Compile et lance le banc du moteur de navigation hors Minecraft (JDK 21+).
set -e
cd "$(dirname "$0")/.."
OUT=${TMPDIR:-/tmp}/navbench
mkdir -p "$OUT"
javac -d "$OUT" src/client/java/com/valafre/automod/nav/*.java tools/NavBench.java
java -cp "$OUT" NavBench
