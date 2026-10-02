#!/data/data/com.termux/files/usr/bin/bash
# Lite Widget Kotlin 语法预检（移植自 ai-diary/scripts/precheck.sh）
# 依赖类错误（unresolved reference）无害化过滤——只有 CI 全依赖环境能查
set -u
cd "$(dirname "$0")/.."

SRCS=$(find app/src/main/java -name "*.kt")
if [ -z "$SRCS" ]; then
    echo "没有找到 .kt 文件"
    exit 1
fi

OUT=$(kotlinc $SRCS -d "${TMPDIR:-$HOME/.hermes/tmp}/kotlinc-lw" -nowarn 2>&1)

# 1) 真语法错误
SYNTAX=$(echo "$OUT" | grep -E "error:" | \
    grep -viE "unresolved reference|cannot access|cannot infer|cannot find|delegate|too many arguments|no value passed|none of the following|argument type mismatch|override nothing|nothing to override|illegal annotation|type mismatch|type argument|not a function|too many elements|val cannot be reassigned" | \
    grep -iE "expecting|unexpected|expected|missing|unterminated|syntax|not a valid|illegal")

# 2) 结构级冲突（kotlinc 不依赖外部库也能查出来）
CLASH=$(echo "$OUT" | grep -E "error:" | \
    grep -iE "platform declaration clash|accidental override|conflicting overloads|name clash|redeclaration")

RC=0
if [ -n "$SYNTAX" ]; then
    echo "❌ 语法错误（CI 必挂）："
    echo "$SYNTAX" | head -30
    RC=1
fi
if [ -n "$CLASH" ]; then
    echo "❌ 声明冲突："
    echo "$CLASH" | head -30
    RC=1
fi

TOTAL=$(echo "$OUT" | grep -c "error:")
echo "kotlinc 报错总数: $TOTAL（含 Android 依赖缺失噪音）"
if [ "$RC" -eq 0 ]; then
    echo "✓ 语法/冲突预检通过（类型与依赖错误需 CI 验证）"
fi
exit $RC
