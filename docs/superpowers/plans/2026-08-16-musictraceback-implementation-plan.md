# MusicTraceBack 实现计划

- 日期:2026-08-16
- 依赖设计文档:`docs/superpowers/specs/2026-08-16-musictraceback-design.md`
- 技术基线:Java 17 / Spring Boot 4.1 / Maven / H2(file 模式)/ 原生 HTML+CSS+JS+Ajax
- 面向读者:入门 Java 开发者(熟悉 CRUD 与中间件使用)

---

## 0. 计划总览

| 里程碑 | 内容 | 预计投入 |
|---|---|---|
| M1 | 项目骨架 + 数据层(5 张表) | 约 1 周 |
| M2 | 网易云单平台闭环(核心价值验证) | 约 2-3 周 |
| M3 | 三平台适配 + 置信度兜底 + 用户确认 | 约 2-3 周 |
| M4 | 体检报告 + 缓存 + 部署打磨 + 测试 | 约 1-2 周 |

每个任务按「做什么 → 怎么做 → 怎么验证」组织,验收可自行勾选。

---

## M1 项目骨架与数据层

### M1.1 建立分层包结构
**做什么**:按设计文档第 8.1 节建立包结构。

**怎么做**:在 `src/main/java/org/example/musictraceback/` 下建包:
```
web/          — Controller + 静态页面
application/  — 用例服务(注入/解析/发现/报告/确认)
domain/       — 实体模型 + 打分规则引擎(纯 Java,不依赖框架)
adapter/      — PlatformAdapter 接口 + 三个平台实现
infrastructure/ — Repository、缓存、HTTP 客户端、限流器
config/       — 配置类
```

**验证**:包结构建立完成,`mvn compile` 通过。

### M1.2 添加依赖
**做什么**:在 `pom.xml` 增加前端 Web 与数据库依赖。

**怎么做**:添加
- `spring-boot-starter-web`(REST + 静态资源托管)
- `com.h2database:h2`(内嵌数据库)
- `spring-boot-starter-data-jpa`(ORM,CRUD 最顺手)
- `spring-boot-starter-validation`(参数校验)

**验证**:`mvn spring-boot:run` 能启动,访问 `http://localhost:8080` 有响应。

### M1.3 配置 H2 文件模式
**做什么**:数据库落到本地文件,重启不丢数据。

**怎么做**:`application.properties` 配置:
```properties
spring.datasource.url=jdbc:h2:file:./data/musictraceback;AUTO_SERVER=TRUE
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=update
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console
```
`ddl-auto=update` 让 Hibernate 自动建表,入门期省去手写建表脚本。

**验证**:启动后 `./data/musictraceback.mv.db` 文件生成;`/h2-console` 可登录查看表。

### M1.4 定义实体与 Repository
**做什么**:按设计文档第 10 节的 5 张表建立 JPA 实体。

**怎么做**:实体类(用 Lombok 简化 getter/setter,若不想用 Lombok 就手写):
- `Snapshot`(歌单快照):id、platform、sourceUrl、title、createdAt、status
- `PlaylistEntry`(快照条目):id、snapshot 关联、platformTrackId、rawTitle、rawArtist、canonicalTrack 关联、entryStatus(枚举)、confidence(枚举)、judgedVersionType(枚举)、rawMetadataJson(用 `@Lob` 存 JSON 字符串)
- `CanonicalTrack`(真实身份):id、normalizedTitle、normalizedArtist、isrc、mbid、createdAt
- `PlatformVersion`(平台版本):id、canonicalTrack 关联、platform、platformTrackId、title、artist、album、durationSec、versionType、popularityRaw、popularityPercentile、playUrl、score、confidence、fetchedAt
- `UserConfirmation`(用户确认):id、playlistEntry 关联、canonicalTrack 关联、chosenPlatformTrackId、chosenVersionType、confirmedAt

枚举统一放 `domain/enums/`:`EntryStatus`(NORMAL/DELISTED/REPLACED/OTHER_PLATFORM)、`Confidence`(HIGH/MEDIUM/LOW)、`VersionType`(ORIGINAL/LIVE/COVER/REMIX/INSTRUMENTAL/OTHER)。

每个实体配一个 `XxxRepository extends JpaRepository<Xxx, Long>`。

**验证**:启动应用,H2 控制台能看到 5 张表及字段。

### M1.5 数据层测试
**做什么**:验证 CRUD 可用。

**怎么做**:写一个 `@DataJpaTest` 或简单集成测试:保存一个 Snapshot + 几条 PlaylistEntry,再查出来断言。

**验证**:`mvn test` 通过。

---

## M2 网易云单平台闭环(核心价值验证)

> 目标:输入网易云歌单链接 → 解析出曲目 → 识别身份 → 找出 Top 3 版本 → 页面展示。

### M2.1 定义 PlatformAdapter 接口
**做什么**:按设计文档 8.2 节定义接口。

**怎么做**:新建接口:
```java
public interface PlatformAdapter {
    String platformId();
    List<PlaylistEntryRaw> parsePlaylist(String shareUrlOrId);
    List<TrackCandidate> search(String title, String artist);
    TrackDetail detail(String trackId);
    String playUrl(String trackId);
}
```
配套定义简单 DTO:`PlaylistEntryRaw`(title/artist/platformTrackId)、`TrackCandidate`(title/artist/album/durationSec/platformTrackId/popularity)、`TrackDetail`。

**验证**:接口编译通过。

### M2.2 NetEaseAdapter:歌单解析
**做什么**:实现网易云歌单链接解析。

**怎么做**:
1. 先从分享链接提取歌单 ID(链接形如 `https://music.163.com/#/playlist?id=xxx` 或短链,写正则提取数字 ID);
2. 用 HTTP 客户端(RestClient)请求网易云接口获取歌单详情;
3. 解析 JSON 得到曲目列表。

**关键点(入门开发者注意)**:
- 网易云无官方 API,需要调用社区维护的接口(如 `NeteaseCloudMusicApi` 自托管项目提供的接口),或直接调用 `music.163.com` 的公开接口(如 `/api/v6/playlist/detail?id=xxx`)。**建议第一版对接自托管的 NeteaseCloudMusicApi,它封装了加密与登录逻辑,你只做 HTTP 调用。**
- 把"接口地址"做成配置项(application.properties),方便切换。
- 响应 JSON 结构多变,建议先用 Postman/浏览器调通拿到样例,再写对应 DTO 解析。

**验证**:输入真实歌单链接,能解析出歌名/歌手列表(可先写一个临时 REST 接口调试,或写单元测试用保存的样例 JSON)。

### M2.3 NetEaseAdapter:搜索与详情
**做什么**:实现按歌名+歌手搜索候选版本、获取歌曲详情(时长/专辑/热度)。

**怎么做**:对接 NeteaseCloudMusicApi 的搜索接口(`/search?keywords=...`)与歌曲详情接口(`/song/detail`);把返回结果映射为 `TrackCandidate` / `TrackDetail`。

**验证**:搜索"周杰伦 晴天"能返回多条候选,包含歌手、专辑、时长、播放热度。

### M2.4 字符串规范化工具
**做什么**:实现第 1 层身份解析的规范化函数(设计文档 9 节)。

**怎么做**:新建 `domain/identity/TextNormalizer.java`,提供:
- `normalizeTitle(String)`:`feat.` 及括号内容(如 `(Live)` `(伴奏)`)剥离、全角转半角、去多余空格、统一大小写;
- `normalizeArtist(String)`:多个歌手只保留主歌手、去 `feat.` 部分;
- `normalize(String)`:繁转简(可引入 `opencc4j` 库或先做常用字映射,入门期先用简单映射表)。

**验证**:对一组典型输入写单元测试断言输出(如 `"晴天 (Live)"` → `"晴天"`)。

### M2.5 打分规则引擎(VersionScorer)
**做什么**:实现第 2 层版本识别(设计文档 9 节证据表)。

**怎么做**:新建 `domain/identity/VersionScorer.java`,对每个 `TrackCandidate` 与目标 `CanonicalTrack` 计算分数:
- 歌手一致 +40;标题含翻唱关键词(Live/现场/翻唱/cover/remix/伴奏/纯音乐…)-30;
- 录音室专辑 +10 / 现场专辑 -10;发行时间早 +5;时长差 <10s +5;热度最高 +5;
- 总分映射置信度:≥80 HIGH、40-79 MEDIUM、<40 LOW。
- 分数阈值与权重抽成常量(或配置文件),便于调参。

**验证**:用真实搜索结果的样例写单元测试,断言常见情况(原版 HIGH、Live MEDIUM、翻唱 LOW)符合预期;用真实歌单人工抽查准确率。

### M2.6 IdentityResolutionService(身份解析)
**做什么**:串起「规范化 → 搜索 → 打分 → 生成真实身份」,把歌单条目变成 `CanonicalTrack`。

**怎么做**:`application/IdentityResolutionService.java`:
1. 对条目做规范化;
2. 调 `NetEaseAdapter.search` 拿候选;
3. 逐个 `VersionScorer` 打分;
4. 最高分候选生成/复用 `CanonicalTrack`,记录置信度与 `judgedVersionType`;
5. 结果写回 `PlaylistEntry`。

**验证**:解析一个真实歌单,大部分条目能关联到真实身份,置信度分布合理。

### M2.7 VersionDiscoveryService(版本发现与 Top 3)
**做什么**:用真实身份在网易云(本阶段)搜索全部候选,打分判类型,按热度取 Top 3 存 `PlatformVersion`。

**怎么做**:`application/VersionDiscoveryService.java`:对每个 `CanonicalTrack` 调搜索 → 打分 → 排序 → 取前 3 → 落库;计算平台内热度百分位(`popularityPercentile`)。

**验证**:同一首歌的 Top 3 是合理版本(原版在前),页面能查到数据。

### M2.8 REST API
**做什么**:暴露后端能力给前端 Ajax 调用。

**怎么做**:`web/PlaylistController.java`:
- `POST /api/playlists/import` — 入参歌单链接,触发注入+解析+发现(可先同步执行,进度功能 M2.9 做)
- `GET /api/playlists/{id}` — 歌单详情(条目列表+状态+置信度)
- `GET /api/playlists/{id}/tracks/{entryId}` — 单曲详情(Top 3 版本)

**验证**:用 curl/Postman 调接口返回正确 JSON。

### M2.9 静态前端页面(HTML/CSS/JS + Ajax)
**做什么**:做最简页面:首页输入框 → 提交 → 展示结果列表 → 单曲详情。

**怎么做**:
1. 静态文件放 `src/main/resources/static/`:`index.html`、`css/style.css`、`js/app.js`(Spring Boot 自动托管,无需配置);
2. `app.js` 用 `fetch`(Ajax)调 M2.8 的接口,渲染结果;
3. 页面要素:输入框 + 按钮、歌单总览(每首歌:原始信息/状态/置信度标记)、点击歌曲展开 Top 3 版本卡片(平台/类型/热度/链接)。

**验证**:浏览器输入链接 → 看到解析结果与 Top 3 版本列表。

### M2.10 单平台闭环验收(里程碑)
**验收清单**:
- [ ] 真实网易云歌单链接能解析出完整曲目
- [ ] 大部分条目完成身份解析(有置信度标记)
- [ ] 每首歌展示 Top 3 版本(含类型/热度/链接)
- [ ] 已下架歌曲能显示"原来是什么"
- [ ] 页面交互流畅,错误有提示

---

## M3 三平台适配 + 置信度兜底 + 用户确认

### M3.1 QQMusicAdapter
**做什么**:实现 QQ 音乐适配器(歌单解析 + 搜索 + 详情)。

**怎么做**:参照 M2.2/M2.3 模式。QQ 音乐同样无官方开放 API,对接社区接口(可同样走 NeteaseCloudMusicApi 的 QQ 模块,或独立逆向接口);歌单分享链接/ID 解析方式不同,单独实现正则与接口调用。

**验证**:输入 QQ 歌单链接能解析;搜索"晴天 周杰伦"能返回候选。

### M3.2 KugouAdapter
**做什么**:实现酷狗适配器(歌单解析 + 搜索 + 详情)。

**怎么做**:酷狗接口与网易/QQ 不同(如 `http://mobilecdn.kugou.com/api/v3/search/song` 类公开接口),单独实现;歌单解析接口需调研,若稳定度低,允许先实现"搜索"能力、歌单解析后置(在 UI 上标注"酷狗歌单解析暂不可用,可直接搜索"——降级策略)。

**验证**:酷狗搜索能返回候选;歌单解析尽力而为。

### M3.3 适配层注册与按平台路由
**做什么**:让系统按平台选择对应 Adapter。

**怎么做**:`config/PlatformAdapterRegistry.java`:注入三个 Adapter,提供 `getAdapter(String platformId)`;`parsePlaylist` 时根据链接来源(域名/特征)自动判断平台。

**验证**:贴入三种平台链接都能路由到正确 Adapter。

### M3.4 跨平台版本发现
**做什么**:版本发现从"仅网易云"扩展到三平台。

**怎么做**:`VersionDiscoveryService` 遍历全部已注册平台搜索;聚合排序时按平台内百分位归一化后比较(设计文档 NFR/热度口径)。

**验证**:同一首歌能看到网易/QQ/酷狗三平台候选与 Top 3。

### M3.5 置信度分级 UI
**做什么**:把高/中/低置信度可视化,中/低可展开查看依据。

**怎么做**:前端按 `confidence` 渲染 🟢/🟡/🔴 标记;中/低置信卡片展示打分依据摘要(如"歌手一致但标题含 Live,疑似现场版");后端在版本详情的 API 响应里带上判断依据文本。

**验证**:人为构造一个疑似翻唱场景,页面能展示依据并引导确认。

### M3.6 用户确认功能
**做什么**:实现第 4 层兜底——用户确认/纠正版本,结果落库。

**怎么做**:
- 前端:中/低置信卡片加"确认原版""选择其他版本"(弹出候选列表)操作;
- 后端:`POST /api/confirmations` 写入 `UserConfirmation`,并同步更新对应 `PlaylistEntry` 的 `confidence`/`judgedVersionType`;
- 规则加权参考历史确认(简单做法:用户确认过的 `(title, artist)` 组合,下次打分时给对应候选 +20 权重)。

**验证**:确认一条记录后,再次体检同歌单,该条目直接显示用户选择且置信度为 HIGH。

### M3.7 缓存
**做什么**:身份解析与版本发现结果缓存,二次体检秒出。

**怎么做**:`infrastructure/` 用 Caffeine(或简单 `ConcurrentHashMap`)按 `(normalizedTitle, normalizedArtist)` 缓存 `CanonicalTrack` 与 Top 版本列表,TTL 7 天(配置化);命中缓存时跳过平台搜索。

**验证**:同一歌单第二次体检不再发起平台请求(日志可见),响应明显加快。

### M3.8 下架/替换检测与追查
**做什么**:实现 FR-5——已下架/被替换条目的追查展示。

**怎么做**:
- 检测:歌单条目在源平台搜索不到匹配(或搜索到的是翻唱而真实身份存在他平台版本)→ 标记 `DELISTED` / `REPLACED` / `OTHER_PLATFORM`;
- 追查:对下架条目,展示 `CanonicalTrack` 的原始信息 + 三平台 Top 版本链接("原来是什么 + 去哪听");
- 前端在单曲卡片上突出展示该状态与替代链接。

**验证**:用含已下架歌曲的真实歌单验收,能展示"原来是 X,现在可在 QQ 听原版"。

---

## M4 打磨与部署

### M4.1 歌单体检报告页
**做什么**:实现 FR-6——健康报告。

**怎么做**:后端 `GET /api/playlists/{id}/report` 返回各状态计数与建议迁移平台;前端一个简单报告页(卡片/计数即可,不必上图表库)。

**验证**:报告数字与列表状态一致。

### M4.2 限流与错误降级
**做什么**:落实 NFR-1/NFR-3——平台请求限流、失败降级。

**怎么做**:
- 限流:每个 Adapter 的 HTTP 调用加简单令牌桶(如每平台 2 req/s,配置化),防止触发风控;
- 降级:平台请求失败时捕获异常,该平台数据标记"暂不可用",其余平台照常,页面明示;
- 超时:HTTP 客户端统一 5s 超时。

**验证**:人为断网/改错接口地址,页面显示降级提示而非整体报错。

### M4.3 一键启动脚本
**做什么**:落实部署档位 A。

**怎么做**:写 `start.bat`(Windows)与 `start.sh`(Linux/macOS):检测 java 命令 → `mvn package` 或直接用打包好的 jar → `java -jar target/*.jar` → 提示打开 `http://localhost:8080`。另写 `stop.bat` 可选。

**验证**:在干净环境双击/运行脚本能启动应用。

### M4.4 Docker 支持
**做什么**:落实部署档位 B。

**怎么做**:写 `Dockerfile`(多阶段:maven 构建 → eclipse-temurin:17-jre 运行)+ `docker-compose.yml`(数据卷挂载 `./data`);README 写清 `docker compose up -d` 用法。

**验证**:`docker build` 成功,容器内数据持久化(重启容器数据还在)。

### M4.5 文档与发布准备
**做什么**:README、部署文档、.gitignore 补全。

**怎么做**:README 含:项目简介、功能清单、快速开始(一键脚本/Docker)、配置说明(接口地址、限流参数)、常见问题(接口失效怎么办、如何调参);`.gitignore` 增加 `data/`(本地数据库文件不入库)。

**验证**:按 README 从零到跑通一遍。

### M4.6 测试补全与最终验收
**做什么**:补齐测试,跑最终验收清单。

**怎么做**:
- 单元测试:TextNormalizer、VersionScorer(各证据组合)、状态判定;
- 适配层测试:用 MockWebServer 模拟平台响应,离线验证解析逻辑;
- 最终验收:按 M2.10 清单 + 三平台真实歌单 + 下架/替换场景 + 二次体检缓存命中 + 部署脚本各跑一遍。

**验证**:`mvn test` 全绿;验收清单全勾。

---

## 关键风险与应对(实现阶段)

| 风险 | 应对 |
|---|---|
| 网易云/QQ/酷狗接口地址失效 | 接口地址全部配置化;适配层隔离,单平台失效不影响整体;README 写明如何更换接口服务 |
| 搜索返回结构变化导致解析崩 | 解析集中在一个方法,JSON 用宽松解析(缺失字段给默认值),不因单个字段异常整体失败 |
| 匹配准确率不达标 | 阈值/关键词/权重全部集中配置,便于调参;依赖置信度分级与用户确认兜底(产品设计已内置) |
| 请求触发风控 | 限流 + 低频 + 只读元数据;本地部署天然分散请求 |

---

## 建议开发节奏

- 每个 M 结束做一次可运行演示(哪怕只是 curl 通);
- M2 是"生死关":先确保网易云闭环真实可用,再谈扩展;
- 调参阶段拿 3-5 个真实歌单(含下架/翻唱场景)做固定验收集,每次改规则跑一遍对比准确率。
