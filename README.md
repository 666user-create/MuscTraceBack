# MusicTraceBack · 歌单身份追溯工具

> 给音乐歌单做"身份追溯"的本地 Web 工具:粘贴歌单链接,系统识别每首歌的真实身份,
> 跨**网易云 / QQ 音乐 / 酷狗**找出所有版本并按热度展示 Top 3;
> 当歌曲在歌单所在平台下架或被替换成奇怪版本时,告诉你**它原来是什么、现在还能在哪听**。

**本地部署 · 数据不出本机 · 免费开源**

---

## 功能一览

| 功能 | 说明 |
|---|---|
| 🎵 三平台歌单注入 | 粘贴网易云 / QQ 音乐 / 酷狗歌单分享链接,自动解析曲目 |
| 🔍 身份解析 | 每首歌识别为"真实身份",标注置信度(高/中/低) |
| 📊 Top 3 版本 | 跨平台聚合所有候选版本,按置信度+热度排序展示 |
| 🚨 下架追查 | 下架曲目标记"已下架",展示其他平台可用版本 |
| 🔄 替换检测 | 歌单里被换成翻唱/Live 时提示"被替换" |
| ✅ 用户确认 | 对不确定的曲目一键确认,越用越准 |
| 📋 体检报告 | 健康度评分 + 问题曲目清单 |
| 🖥️ 本地部署 | 数据保存在本机,隐私无忧 |

## 快速开始

### 方式一:一键脚本(推荐)

**Windows**(双击 `start.bat`)或 **Linux/macOS**(`./start.sh`):
- 自动检测 Java(需 JDK 17+)
- 首次运行自动构建,之后直接启动
- 浏览器打开 **http://localhost:8080**

### 方式二:Docker

```bash
docker compose up -d
# 打开 http://localhost:8080
# 数据保存在 docker volume "musictraceback-data"
```

### 方式三:手动构建

```bash
mvn -DskipTests package
java -jar target/MusicTraceBack-*.jar
```

## 使用说明

1. 打开 http://localhost:8080
2. 粘贴歌单分享链接,例如:
   - 网易云:`https://music.163.com/#/playlist?id=3778678`
   - QQ 音乐:`https://y.qq.com/n/ryqq/playlist/3506837410`
   - 酷狗:`https://www.kugou.com/special/single/137775.html`
3. 点击"开始追溯"——**立即返回**,后台异步处理,页面显示实时进度条(可离开页面,重启自动续跑)
4. 处理完成后查看体检报告与曲目列表;点击"查看版本"展开各平台 Top 3
5. 对"疑似/待确认"曲目点击"确认是原版"完成兜底

> 200 首大歌单全程约 2-3 分钟后台跑完(单线程串行 + 限流),遇平台风控自动暂停等待恢复。

## 配置说明(`application.properties`)

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `music.http.min-interval-ms` | 800 | 同平台连续请求最小间隔,降低风控概率 |
| `music.http.cooldown-ms` | 600000 | 平台风控(406)后冷却期,期间跳过该平台,自动恢复 |
| `music.http.failure-threshold` | 3 | 断路器阈值:同主机连续失败该次数后进入短暂不可用期 |
| `music.http.failure-cooldown-ms` | 120000 | 断路器不可用期,期间该平台请求快速跳过(不发网络调用) |
| `music.cache.search-ttl-ms` | 600000 | 搜索缓存有效期(10 分钟) |
| `music.netease.base-url` | `http://music.163.com` | 网易云接口地址 |
| `music.qq.playlist-url` | `https://i.y.qq.com/qzone-music/...fcg_ucc_getcdinfo_byids_cp.fcg` | QQ 音乐歌单接口 |
| `music.qq.musicu-url` | `https://u.y.qq.com/cgi-bin/musicu.fcg` | QQ 音乐搜索接口(官方桌面端) |
| `music.kugou.base-url` | `http://mobilecdn.kugou.com` | 酷狗接口地址 |

> 网络受限环境下接口地址可替换为自托管的第三方 API 服务
> (如 NeteaseCloudMusicApi 等),只需改 base-url。

## 常见问题

**Q: 解析到一半报"平台风控"?**
A: 平台对高频请求有限流。本工具已内置三重防护:单线程串行处理 + 请求最小间隔 + 风控后自动冷却恢复。若仍频繁触发(如网络环境特殊),调大 `min-interval-ms`(如 1500)或 `cooldown-ms`(如 900000)。注入是异步的,触发风控会自动暂停等待,不会失败。

**Q: 大量曲目标"待确认(低置信)"?**
A: 当前版本身份解析直接采用歌单接口数据(零请求本地建档),正常歌单应全部高置信。若出现低置信多为条目本身异常;风控导致的暂停会自动恢复,无需人工介入。

**Q: 数据存在哪里?**
A: `./data/` 目录(H2 数据库文件)。删除该目录即重置全部数据。Docker 部署在数据卷 `musictraceback-data`。

**Q: 注入进行到一半关闭了应用怎么办?**
A: 无需担心——处理进度逐条持久化,重启应用后**自动续跑**未完成的歌单(见"断点续跑")。

**Q: 平台接口失效了怎么办?**
A: 平台逆向接口随时可能变更。各平台 Adapter 独立隔离(`adapter/` 包),单平台失效不影响其他平台;接口地址全部可配置,见上表。

**Q: 这合法吗?**
A: 工具只读取公开的曲目元数据(歌名/歌手/时长),不下载、不播放、不破解任何音频或 DRM;跳转链接均指向平台官方页面。本地部署模式下,请求由用户自己发出。

## 技术架构

```
Web 层    静态前端(HTML/CSS/JS + Ajax)+ REST API
应用层    PlaylistInjectionService / IdentityResolutionService
          VersionDiscoveryService / UserConfirmationService
领域层    实体模型 + TextNormalizer(规范化)+ VersionScorer(打分引擎)
适配层    PlatformAdapter 接口 → NetEase / QQ / Kugou 三实现
基础设施  H2(file 模式)+ SearchCache + HttpFetcher(限流/退避)
```

- Java 17 + Spring Boot 4.1 + JPA + H2
- 前端零构建工具链,原生 JS + fetch
- 新增平台 = 新增一个 `PlatformAdapter` 实现类

## 开发

```bash
# 运行测试(66 个用例,含 MockWebServer 离线模拟)
mvn test

# 本机 Maven 环境受限时(依赖缓存重定向)
mvn -s settings-local.xml test
```

## 文档

- 产品设计:`docs/superpowers/specs/2026-08-16-musictraceback-design.md`
- 实现计划:`docs/superpowers/plans/2026-08-16-musictraceback-implementation-plan.md`

## 免责声明

本项目仅用于学习与个人歌单管理,请遵守各音乐平台服务条款,勿用于商业用途。
