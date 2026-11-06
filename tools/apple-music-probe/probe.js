"use strict";

const status = document.querySelector("#status");
const authorize = document.querySelector("#authorize");
const library = document.querySelector("#library");
const play = document.querySelector("#play");
const tracks = document.querySelector("#tracks");
const pairing = location.hash.slice(1);
history.replaceState(null, "", location.pathname);

let music;
let session;
let connected = true;
let lastSync = performance.now();
let command = { sequence: -1, action: "idle" };
let acknowledged = 0;
let libraryCount = 0;
let accountGeneration = 0;
let loaded = false;
let stopped = true;
let playerTask = Promise.resolve();

async function post(path, key, body = "") {
    const response = await fetch(path, {
        method: "POST",
        headers: { Authorization: "Bearer " + key },
        body,
        cache: "no-store",
        redirect: "error",
        signal: AbortSignal.timeout(2500),
    });
    if (!response.ok) throw new Error("Local bridge rejected request");
    return response.text();
}

function updateButtons() {
    authorize.disabled = !connected || !music || music.isAuthorized;
    library.disabled = !connected || !music?.isAuthorized;
    play.disabled = !connected || !loaded;
}

async function stopPlayback() {
    stopped = true;
    loaded = false;
    updateButtons();
    if (music) await music.stop();
}

function disconnect() {
    if (!connected) return;
    connected = false;
    accountGeneration++;
    status.textContent = "会话已断开。请重新启动验证程序。";
    void stopPlayback().catch(() => {});
}

function serialize(task) {
    playerTask = playerTask.then(task).catch(() => {
        if (connected) status.textContent = "播放命令失败。请检查授权、订阅、地区和浏览器播放权限。";
    });
    return playerTask;
}

function handle(next) {
    const urgent = next.action === "stop" || next.action === "logout";
    if (urgent) {
        if (next.action === "logout") accountGeneration++;
        void stopPlayback().catch(() => {});
    }
    serialize(async () => {
        if (!connected || command !== next) return;
        try {
            if (!music) return;
            switch (next.action) {
                case "load":
                    if (!music.isAuthorized) throw new Error("Authorization required");
                    await stopPlayback();
                    if (!connected || command !== next) return;
                    await music.setQueue({ song: next.argument });
                    if (!connected || command !== next) return;
                    loaded = true;
                    status.textContent = "曲目已加载。点击本页播放，或在终端输入 play。";
                    break;
                case "play":
                    if (!loaded) return;
                    stopped = false;
                    try {
                        await music.play();
                    } catch (error) {
                        stopped = true;
                        throw error;
                    }
                    if (!connected || command.action === "stop" || command.action === "logout") await stopPlayback();
                    break;
                case "pause":
                    await music.pause();
                    break;
                case "seek":
                    if (loaded) await music.seekToTime(Number(next.argument));
                    break;
                case "stop":
                    await stopPlayback();
                    break;
                case "logout":
                    await stopPlayback();
                    await music.unauthorize();
                    libraryCount = 0;
                    tracks.replaceChildren();
                    status.textContent = "Apple Music 已退出。";
                    break;
            }
        } finally {
            if (connected && command === next) acknowledged = next.sequence;
            updateButtons();
        }
    });
}

function milliseconds(seconds) {
    return Number.isFinite(seconds) ? Math.min(999999999, Math.max(0, Math.round(seconds * 1000))) : 0;
}

async function sync() {
    if (!connected) return;
    try {
        const report = [
            acknowledged, music?.isAuthorized ? 1 : 0, libraryCount,
            stopped ? 0 : milliseconds(music?.currentPlaybackTime),
            stopped ? 0 : milliseconds(music?.currentPlaybackDuration),
        ].join(" ");
        const response = await post("/sync", session, report);
        if (!connected) return;
        lastSync = performance.now();
        const [sequence, action, argument] = response.split(" ");
        if (Number(sequence) > command.sequence) {
            command = { sequence: Number(sequence), action, argument };
            handle(command);
        }
    } catch {
        if (performance.now() - lastSync > 6000) disconnect();
    }
    if (connected) setTimeout(sync, 250);
}

async function init() {
    try {
        session = await post("/pair", pairing);
        lastSync = performance.now();
        void sync();
        const developerToken = await post("/token", session);
        await MusicKit.configure({ developerToken, app: { name: "DangoMusic GTNH Probe", build: "0.1.0" } });
        if (!connected) return;
        music = MusicKit.getInstance();
        status.textContent = music.isAuthorized ? "Apple Music 已授权。" : "请通过 Apple 授权。";
        updateButtons();
    } catch {
        if (connected) {
            disconnect();
            status.textContent = "初始化失败。请检查开发者令牌、浏览器网络和 MusicKit 加载。";
        }
    }
}

authorize.onclick = async () => {
    authorize.disabled = true;
    const generation = accountGeneration;
    try {
        await music.authorize();
        if (!connected || generation !== accountGeneration) {
            await music.unauthorize();
            return;
        }
        status.textContent = "Apple Music 已授权。可以读取媒体库。";
    } catch {
        if (connected && generation === accountGeneration) status.textContent = "授权未完成。请检查 Apple 弹窗和令牌。";
    } finally {
        updateButtons();
    }
};

library.onclick = async () => {
    library.disabled = true;
    const generation = accountGeneration;
    try {
        const response = await music.api.music("/v1/me/library/songs", { limit: 10, include: "catalog" });
        if (!connected || generation !== accountGeneration) return;
        const songs = response.data.data;
        tracks.replaceChildren();
        for (const song of songs) {
            const catalogId = song.relationships?.catalog?.data?.[0]?.id;
            const item = document.createElement("li");
            item.textContent = song.attributes.name + (catalogId ? " (目录 ID " + catalogId + ")" : " (无目录 ID)");
            tracks.append(item);
        }
        libraryCount = songs.length;
        status.textContent = "媒体库返回 " + libraryCount + " 首歌曲。";
    } catch {
        if (connected && generation === accountGeneration) status.textContent = "读取失败。请检查授权及账号状态。";
    } finally {
        updateButtons();
    }
};

play.onclick = async () => {
    if (!connected || !loaded) return;
    stopped = false;
    try {
        await music.play();
        if (!connected || command.action === "stop" || command.action === "logout") await stopPlayback();
        else status.textContent = "已发出播放命令。请用终端 status 检查进度。";
    } catch {
        stopped = true;
        if (connected) status.textContent = "浏览器播放失败。请检查订阅和浏览器权限。";
    }
};

window.addEventListener("pagehide", disconnect);
document.addEventListener("musickitloaded", init, { once: true });
