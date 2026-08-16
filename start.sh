#!/usr/bin/env bash
# MusicTraceBack 一键启动脚本(Linux/macOS)
set -e
cd "$(dirname "$0")"

echo ""
echo " ============================================"
echo "  MusicTraceBack 歌单身份追溯工具"
echo " ============================================"
echo ""

# 检查 Java
if ! command -v java >/dev/null 2>&1; then
    echo " [错误] 未检测到 Java。请先安装 JDK 17 或更高版本。"
    exit 1
fi

# 优先使用已构建产物
JAR=$(ls target/MusicTraceBack-*.jar 2>/dev/null | head -n 1)

if [ -z "$JAR" ]; then
    echo " [提示] 未找到构建产物,正在使用 Maven 构建(首次较慢)..."
    if [ -x ./mvnw ]; then
        ./mvnw -q -DskipTests package
    else
        mvn -q -DskipTests package
    fi
    JAR=$(ls target/MusicTraceBack-*.jar | head -n 1)
fi

echo " [启动] 正在启动 MusicTraceBack ..."
echo " [地址] http://localhost:8080"
echo " [数据] 保存在 ./data 目录(删除该目录可重置所有数据)"
echo ""

exec java -jar "$JAR"
