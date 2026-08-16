/* ============================================================
   MusicTraceBack — 前端逻辑
   M2:歌单注入(Ajax 调后端)+ 结果渲染 + 健康检查
   ============================================================ */

(function () {
    "use strict";

    const $ = (sel) => document.querySelector(sel);

    const els = {
        pill: $("#serverPill"),
        form: $("#traceForm"),
        input: $("#playlistUrl"),
        btn: $("#traceBtn"),
        btnText: $("#traceBtn .btn-text"),
        toast: $("#toast"),
        result: $("#resultSection"),
        resultTitle: $("#resultTitle"),
        resultList: $("#resultList"),
        loading: $("#loadingSection"),
        report: $("#reportSection"),
        reportBody: $("#reportBody"),
        cards: {
            server: { pill: $('[data-pill="server"]'), detail: $('[data-detail="server"]') },
            db: { pill: $('[data-pill="db"]'), detail: $('[data-detail="db"]') },
            time: { pill: $('[data-pill="time"]'), detail: $('[data-detail="time"]') },
        },
    };

    let toastTimer = null;

    /* ---------- Toast 提示 ---------- */
    function showToast(message, type) {
        els.toast.textContent = message;
        els.toast.className = "toast show " + (type || "info");
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => els.toast.classList.remove("show"), 4200);
    }

    /* ---------- 格式化时间 ---------- */
    function formatTime(iso) {
        try {
            const d = new Date(iso);
            if (isNaN(d.getTime())) return iso;
            return d.toLocaleString("zh-CN", { hour12: false });
        } catch (e) {
            return iso;
        }
    }

    /* ---------- 状态标签 ---------- */
    const CONFIDENCE_LABEL = { HIGH: "高置信", MEDIUM: "疑似", LOW: "待确认" };
    const STATUS_LABEL = {
        NORMAL: "正常", DELISTED: "已下架", REPLACED: "被替换", OTHER_PLATFORM: "他平台可听",
    };
    const VERSION_LABEL = {
        ORIGINAL: "原版", LIVE: "现场版", COVER: "翻唱", REMIX: "Remix", INSTRUMENTAL: "伴奏", OTHER: "其他",
    };
    const PLATFORM_LABEL = { netease: "网易云", qq: "QQ音乐", kugou: "酷狗" };

    function esc(s) {
        return String(s == null ? "" : s)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    /* ---------- 健康检查(同 M1) ---------- */
    async function checkHealth() {
        try {
            const res = await fetch("/api/health", { headers: { Accept: "application/json" } });
            if (!res.ok) throw new Error("HTTP " + res.status);
            const data = await res.json();

            els.pill.dataset.state = "up";
            els.pill.querySelector(".label").textContent = "服务在线 · " + data.app;

            els.cards.server.pill.dataset.state = "up";
            els.cards.server.pill.textContent = "UP";
            els.cards.server.detail.textContent = data.app + " · " + data.status;

            els.cards.db.pill.dataset.state = "up";
            els.cards.db.pill.textContent = "OK";
            els.cards.db.detail.textContent = data.database;

            els.cards.time.pill.dataset.state = "up";
            els.cards.time.pill.textContent = "运行中";
            els.cards.time.detail.textContent = formatTime(data.time);
        } catch (err) {
            els.pill.dataset.state = "down";
            els.pill.querySelector(".label").textContent = "服务未响应";
            els.cards.server.pill.dataset.state = "down";
            els.cards.server.pill.textContent = "DOWN";
            els.cards.server.detail.textContent = "无法连接后端";
            els.cards.db.pill.dataset.state = "down";
            els.cards.db.pill.textContent = "—";
            els.cards.db.detail.textContent = "等待后端恢复";
            els.cards.time.pill.dataset.state = "down";
            els.cards.time.pill.textContent = "—";
            els.cards.time.detail.textContent = "—";
        }
    }

    /* ---------- 歌单注入(异步:提交后轮询进度) ---------- */
    let progressTimer = null;
    let currentPlaylistId = null;

    async function importPlaylist(url) {
        setLoading(true, "正在解析歌单…");
        try {
            const res = await fetch("/api/playlists/import", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ url }),
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) {
                throw new Error(data.error || ("请求失败 HTTP " + res.status));
            }
            currentPlaylistId = data.id;
            showToast("歌单已接收(共 " + (data.entryCount || 0) + " 首),后台追溯中…", "info");
            showProgress(data);
            startPolling(data.id);
        } catch (err) {
            showToast(err.message || "注入失败,请检查链接", "error");
        } finally {
            setLoading(false, "");
        }
    }

    /* ---------- 进度展示 + 轮询 ---------- */
    function showProgress(data) {
        els.result.hidden = false;
        els.resultTitle.textContent =
            (data.title || "歌单") + " · " + (PLATFORM_LABEL[data.platform] || data.platform);
        els.resultList.innerHTML = "";
        els.report.hidden = true;

        const bar = document.createElement("div");
        bar.className = "progress-wrap";
        bar.innerHTML =
            '<div class="progress-info">' +
            '  <span class="progress-label">正在后台追溯歌曲身份与版本…</span>' +
            '  <span class="progress-num" id="progressNum">0%</span>' +
            "</div>" +
            '<div class="progress-bar"><div class="progress-fill" id="progressFill" style="width:0%"></div></div>' +
            '<p class="progress-hint">后台处理中,可离开本页;应用重启后自动续跑。' +
            '遇平台限流会自动暂停并恢复,请耐心等待。</p>';
        els.resultList.appendChild(bar);
    }

    function updateProgress(p) {
        const num = document.getElementById("progressNum");
        const fill = document.getElementById("progressFill");
        if (num) num.textContent = p.percent + "% (" + p.processedCount + "/" + p.entryCount + ")";
        if (fill) fill.style.width = p.percent + "%";
    }

    function startPolling(playlistId) {
        clearInterval(progressTimer);
        progressTimer = setInterval(async () => {
            try {
                const res = await fetch("/api/playlists/" + playlistId + "/progress");
                if (!res.ok) {
                    clearInterval(progressTimer);
                    showToast("进度查询失败,请刷新页面", "error");
                    return;
                }
                const p = await res.json();
                updateProgress(p);
                if (p.status === "COMPLETED") {
                    clearInterval(progressTimer);
                    showToast("追溯完成!", "info");
                    loadPlaylistDetail(playlistId);
                }
            } catch (err) {
                // 网络抖动忽略,下轮继续
            }
        }, 1500);
    }

    /** 完成后加载完整歌单详情并渲染 */
    async function loadPlaylistDetail(playlistId) {
        try {
            const res = await fetch("/api/playlists/" + playlistId);
            if (!res.ok) throw new Error("HTTP " + res.status);
            const data = await res.json();
            renderResult(data);
        } catch (err) {
            showToast("歌单详情加载失败: " + err.message, "error");
        }
    }

    /* ---------- 渲染结果(完整详情) ---------- */
    function renderResult(summary) {
        els.result.hidden = false;
        els.resultTitle.textContent =
            (summary.title || "歌单") + " · " + (PLATFORM_LABEL[summary.platform] || summary.platform);
        els.resultList.innerHTML = "";
        els.report.hidden = false;
        els.reportBody.innerHTML = '<p class="versions-loading">正在生成体检报告…</p>';
        loadReport(summary.id);

        // 统计
        const entries = summary.entries || [];
        const stats = { NORMAL: 0, DELISTED: 0, REPLACED: 0, OTHER_PLATFORM: 0 };
        entries.forEach((e) => { stats[e.status] = (stats[e.status] || 0) + 1; });
        els.resultTitle.innerHTML +=
            ' <span class="result-stats">正常 ' + stats.NORMAL +
            ' · 下架 ' + stats.DELISTED +
            ' · 替换 ' + stats.REPLACED + "</span>";

        if (entries.length === 0) {
            els.resultList.innerHTML = '<p class="empty-tip">歌单为空或解析失败。</p>';
            return;
        }

        // 渲染条目(前 50 首,避免页面过大)
        entries.slice(0, 50).forEach((e) => {
            const card = document.createElement("article");
            card.className = "track-card";

            const confClass = e.confidence === "HIGH" ? "conf-high"
                : e.confidence === "MEDIUM" ? "conf-medium" : "conf-low";
            const statusClass = "st-" + e.status;

            card.innerHTML =
                '<div class="track-main">' +
                '  <div class="track-text">' +
                '    <h4>' + esc(e.title) + "</h4>" +
                '    <p class="track-artist">' + esc(e.artist || "未知歌手") + "</p>" +
                "  </div>" +
                '  <div class="track-tags">' +
                '    <span class="tag ' + statusClass + '">' + (STATUS_LABEL[e.status] || e.status) + "</span>" +
                '    <span class="tag ' + confClass + '">' + (CONFIDENCE_LABEL[e.confidence] || e.confidence) + "</span>" +
                '    <span class="tag tag-version">' + (VERSION_LABEL[e.versionType] || e.versionType || "其他") + "</span>" +
                "  </div>" +
                "</div>" +
                '<button class="track-detail-btn" data-entry="' + e.id + '" data-playlist="' + summary.id + '">查看版本</button>';

            card.querySelector(".track-detail-btn").addEventListener("click", () => {
                loadTrackDetail(summary.id, e.id, card);
            });
            els.resultList.appendChild(card);
        });

        if (entries.length > 50) {
            const more = document.createElement("p");
            more.className = "empty-tip";
            more.textContent = "…仅展示前 50 首,其余可稍后查看完整歌单";
            els.resultList.appendChild(more);
        }
    }

    /* ---------- 单曲详情(Top 版本 + 确认) ---------- */
    async function loadTrackDetail(playlistId, entryId, card) {
        const detailBox = card.querySelector(".track-versions");
        if (detailBox) {
            detailBox.remove();
            card.classList.remove("expanded");
            return;
        }
        card.classList.add("expanded");
        const box = document.createElement("div");
        box.className = "track-versions";
        box.innerHTML = '<p class="versions-loading">正在加载各平台版本…</p>';
        card.appendChild(box);

        try {
            const res = await fetch("/api/playlists/" + playlistId + "/tracks/" + entryId);
            if (!res.ok) throw new Error("HTTP " + res.status);
            const data = await res.json();
            const versions = data.versions || [];

            // 状态提示区
            const statusNote = document.createElement("p");
            statusNote.className = "versions-loading";
            if (data.status === "DELISTED") {
                statusNote.textContent = "⚠ 该曲目在歌单源平台疑似已下架。下面是从其他平台找到的版本:";
            } else if (data.status === "REPLACED") {
                statusNote.textContent = "⚠ 歌单中的版本疑似被替换(" + (VERSION_LABEL[data.versionType] || data.versionType) + ")。" +
                    "以下是各平台的原版候选:";
            }
            if (statusNote.textContent) box.appendChild(statusNote);

            if (versions.length === 0) {
                box.innerHTML += '<p class="versions-loading">该歌曲暂未找到其他平台版本。</p>';
            } else {
                versions.forEach((v, i) => {
                    const row = document.createElement("div");
                    row.className = "version-row";
                    row.innerHTML =
                        '  <span class="version-rank">' + (i + 1) + "</span>" +
                        '  <div class="version-info">' +
                        '    <span class="version-type">' + (VERSION_LABEL[v.versionType] || v.versionType) + "</span>" +
                        '    <span class="version-platform">' + (PLATFORM_LABEL[v.platform] || v.platform) + "</span>" +
                        '    <span class="version-title">' + esc(v.title) + "</span>" +
                        '    <span class="version-artist">' + esc(v.artist) + "</span>" +
                        '    <span class="version-hot">热度 ' + (v.popularityRaw != null ? v.popularityRaw : "?") + "</span>" +
                        "  </div>" +
                        '  <a class="version-link" href="' + esc(v.playUrl) + '" target="_blank" rel="noopener">去听 →</a>';
                    box.appendChild(row);
                });
            }

            // 确认按钮(中/低置信时提示用户兜底)
            if (data.confidence === "MEDIUM" || data.confidence === "LOW") {
                const confirmBar = document.createElement("div");
                confirmBar.className = "confirm-bar";
                confirmBar.innerHTML =
                    '<span class="confirm-tip">判断不确定?确认这首歌的真实身份:</span>' +
                    '<button class="btn-confirm" data-entry="' + entryId + '" data-version="ORIGINAL">确认是原版</button>';
                confirmBar.querySelector(".btn-confirm").addEventListener("click", (e) => {
                    confirmVersion(entryId, e.target.dataset.version, box);
                });
                box.appendChild(confirmBar);
            }
        } catch (err) {
            box.innerHTML = '<p class="versions-loading">加载失败:' + esc(err.message) + "</p>";
        }
    }

    /* ---------- 体检报告 ---------- */
    async function loadReport(playlistId) {
        try {
            const res = await fetch("/api/playlists/" + playlistId + "/report");
            if (!res.ok) throw new Error("HTTP " + res.status);
            const r = await res.json();
            const sc = r.statusCounts || {};
            const health = r.healthScore != null ? r.healthScore : 0;

            let html =
                '<div class="report-health">' +
                '  <div class="health-bar"><div class="health-fill" style="width:' + health + '%"></div></div>' +
                '  <span class="health-num">健康度 ' + health + '%</span>' +
                "</div>" +
                '<div class="report-stats">' +
                '  <span class="rstat ok">正常 ' + (sc.NORMAL || 0) + "</span>" +
                '  <span class="rstat warn">被替换 ' + (sc.REPLACED || 0) + "</span>" +
                '  <span class="rstat bad">已下架 ' + (sc.DELISTED || 0) + "</span>" +
                '  <span class="rstat other">他平台可听 ' + (sc.OTHER_PLATFORM || 0) + "</span>" +
                "</div>";

            const issues = r.issues || [];
            if (issues.length > 0) {
                html += '<p class="report-sub">问题曲目(' + issues.length + "):</p>";
                issues.forEach((i) => {
                    const stLabel = i.status === "DELISTED" ? "已下架" : "被替换";
                    html +=
                        '<div class="report-issue">' +
                        '  <span class="tag st-' + i.status + '">' + stLabel + "</span>" +
                        '  <span class="issue-title">' + esc(i.title) + " - " + esc(i.artist || "未知歌手") + "</span>" +
                        "</div>";
                });
            } else {
                html += '<p class="report-sub">🎉 未发现问题曲目,歌单状态良好。</p>';
            }
            els.reportBody.innerHTML = html;
        } catch (err) {
            els.reportBody.innerHTML =
                '<p class="versions-loading">报告生成失败:' + esc(err.message) + "</p>";
        }
    }

    /* ---------- 用户确认 ---------- */
    async function confirmVersion(entryId, versionType, box) {
        try {
            const res = await fetch("/api/playlists/tracks/" + entryId + "/confirm", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ versionType }),
            });
            const data = await res.json();
            if (!res.ok) throw new Error(data.error || "确认失败");
            showToast("已确认:该版本为" + (VERSION_LABEL[versionType] || versionType), "info");
            // 刷新整个歌单列表,更新置信度标签
            location.reload();
        } catch (err) {
            showToast(err.message || "确认失败", "error");
        }
    }

    /* ---------- 提交处理 ---------- */
    function setLoading(on, text) {
        els.btn.classList.toggle("loading", on);
        els.btnText.textContent = on ? (text || "处理中…") : "开始追溯";
    }

    function handleSubmit(e) {
        e.preventDefault();
        const url = els.input.value.trim();
        if (!url) {
            showToast("请先粘贴歌单分享链接", "error");
            els.input.focus();
            return;
        }
        importPlaylist(url);
    }

    /* ---------- 初始化 ---------- */
    function init() {
        els.form.addEventListener("submit", handleSubmit);
        els.result.hidden = true;
        els.report.hidden = true;
        checkHealth();
        setInterval(checkHealth, 60000);
    }

    document.addEventListener("DOMContentLoaded", init);
})();
