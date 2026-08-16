/* ============================================================
   MusicTraceBack — 前端逻辑
   M1:健康检查(系统状态卡片 + 顶栏状态徽章)+ 表单交互
   ============================================================ */

(function () {
    "use strict";

    const $ = (sel) => document.querySelector(sel);

    const els = {
        pill: $("#serverPill"),
        form: $("#traceForm"),
        input: $("#playlistUrl"),
        btn: $("#traceBtn"),
        toast: $("#toast"),
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
        toastTimer = setTimeout(() => els.toast.classList.remove("show"), 3600);
    }

    /* ---------- 格式化时间:ISO → 本地可读 ---------- */
    function formatTime(iso) {
        try {
            const d = new Date(iso);
            if (isNaN(d.getTime())) return iso;
            return d.toLocaleString("zh-CN", { hour12: false });
        } catch (e) {
            return iso;
        }
    }

    /* ---------- 健康检查 ---------- */
    async function checkHealth() {
        try {
            const res = await fetch("/api/health", { headers: { Accept: "application/json" } });
            if (!res.ok) throw new Error("HTTP " + res.status);
            const data = await res.json();

            // 顶栏状态徽章
            els.pill.dataset.state = "up";
            els.pill.querySelector(".label").textContent = "服务在线 · " + data.app;

            // 状态卡片
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

    /* ---------- 表单提交(M1 占位:歌单解析在 M2 实现) ---------- */
    function handleSubmit(e) {
        e.preventDefault();
        const url = els.input.value.trim();
        if (!url) {
            showToast("请先粘贴歌单分享链接", "error");
            els.input.focus();
            return;
        }
        els.btn.classList.add("loading");
        els.btn.querySelector(".btn-text").textContent = "解析功能开发中";
        showToast("歌单解析与跨平台追溯将在下一阶段上线,当前版本已完成基础架构与系统自检", "info");
        setTimeout(() => {
            els.btn.classList.remove("loading");
            els.btn.querySelector(".btn-text").textContent = "开始追溯";
        }, 1800);
    }

    /* ---------- 初始化 ---------- */
    function init() {
        els.form.addEventListener("submit", handleSubmit);
        checkHealth();
        // 每 60s 自动刷新一次健康状态
        setInterval(checkHealth, 60000);
    }

    document.addEventListener("DOMContentLoaded", init);
})();
