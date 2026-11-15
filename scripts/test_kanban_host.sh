#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"
DEPS="${KANBAN_TEST_DEPS:-$HOME/.hermes/tmp/elcton-jvm-test}"
CP="$DEPS/json.jar:$DEPS/junit.jar:$DEPS/hamcrest.jar"
OUT="$HOME/.hermes/tmp/kanban-v2-host.jar"
kotlinc app/src/main/java/com/litewidget/app/core/data/KanbanData.kt app/src/main/java/com/litewidget/app/core/spec/SpecValidator.kt app/src/test/java/com/litewidget/app/KanbanDataTest.kt app/src/test/java/com/litewidget/app/KanbanSpecTest.kt app/src/test/java/com/litewidget/app/KanbanDisplayTest.kt -cp "$CP" -include-runtime -d "$OUT"
java -cp "$OUT:$CP" org.junit.runner.JUnitCore com.litewidget.app.KanbanDataTest com.litewidget.app.KanbanSpecTest com.litewidget.app.KanbanDisplayTest
