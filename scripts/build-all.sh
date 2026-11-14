#!/usr/bin/env bash
# Builds the Paper plugin and the Fabric mod and copies the jars to dist/.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}"
[ -x "$JAVA_HOME/bin/javac" ] || { echo "JDK 25 not found. Set JAVA_HOME (Arch Linux: sudo pacman -S jdk25-openjdk)."; exit 1; }

rm -rf dist
mkdir -p dist

echo "=== Paper ==="
( cd paper && mvn -q clean package )
cp paper/target/UniversalProtect.jar "dist/UniversalProtect-Paper.jar"

echo "=== Fabric ==="
( cd fabric && ./gradlew build -q )
for f in fabric/build/libs/*.jar; do
  case "$f" in *-sources.jar|*-dev.jar) continue;; esac
  cp "$f" "dist/UniversalProtect-Fabric.jar"
done

echo "Done:"
ls -lh dist
