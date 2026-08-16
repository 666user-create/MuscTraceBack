@echo off
chcp 65001 >nul
setlocal

echo.
echo  ============================================
echo   MusicTraceBack 歌单身份追溯工具
echo  ============================================
echo.

cd /d "%~dp0"

rem --- 检查 Java ---
where java >nul 2>nul
if errorlevel 1 (
    echo  [错误] 未检测到 Java。请先安装 JDK 17 或更高版本:
    echo         https://adoptium.net/
    echo.
    pause
    exit /b 1
)

rem --- 检查 jar(优先使用已构建产物) ---
set JAR=
for %%f in (target\MusicTraceBack-*.jar) do set JAR=%%f

if "%JAR%"=="" (
    echo  [提示] 未找到构建产物,正在使用 Maven 构建(首次较慢)...
    echo.
    if exist mvnw.cmd (
        call mvnw.cmd -q -DskipTests package
    ) else (
        call mvn -q -DskipTests package
    )
    if errorlevel 1 (
        echo.
        echo  [错误] 构建失败,请检查上方输出。
        pause
        exit /b 1
    )
    for %%f in (target\MusicTraceBack-*.jar) do set JAR=%%f
)

echo  [启动] 正在启动 MusicTraceBack ...
echo  [地址] http://localhost:8080
echo  [数据] 保存在 .\data 目录(删除该目录可重置所有数据)
echo  [停止] 关闭本窗口或按 Ctrl+C
echo.

java -jar "%JAR%"

pause
