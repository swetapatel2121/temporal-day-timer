// The day timer page. It talks to the HTTP API described in ROADMAP.md and does the ticking itself:
// a project's time is its totalMs, plus (now − runningSince) while it's running, up to the end of the day.

const state = {
    projects: [],              // today's projects, in the order they were added
    busy: new Set(),           // names of projects with a start or pause request on its way
    loadFailed: false,         // whether the error on show came from fetching the list
    recentlyAdded: new Map(),  // name → when it was added here, for projects the list may not show yet
    dayEndsAt: Infinity,       // when today ends (midnight), worked out by load()
};

// How many project colors style.css has (--project-0 … --project-7).
const PROJECT_COLORS = 8;
// How often the page fetches the projects again while it's in view, so a nudge shows up.
const REFRESH_MS = 30_000;
// How long a project the page just added is kept, even if the list doesn't show it yet.
const LIST_LAG_MS = 30_000;

const page = {
    today: document.getElementById("today"),
    now: document.getElementById("now"),
    nowName: document.getElementById("now-name"),
    nowNudge: document.getElementById("now-nudge"),
    nowTime: document.getElementById("now-time"),
    nowPause: document.getElementById("now-pause"),
    summary: document.getElementById("summary"),
    dayTotal: document.getElementById("day-total"),
    breakdown: document.getElementById("breakdown"),
    dayEnds: document.getElementById("day-ends"),
    form: document.getElementById("add-form"),
    name: document.getElementById("add-name"),
    addButton: document.querySelector("#add-form button"),
    error: document.getElementById("error"),
    list: document.getElementById("projects"),
    empty: document.getElementById("empty"),
    row: document.getElementById("project-row"),
};

// ---- talking to the server ----

// Sends a request and returns the JSON it gets back. Throws an Error with a message to show
// if the server can't be reached or answers with an error.
async function api(method, path, body) {
    let response;
    try {
        response = await fetch(path, {
            method,
            headers: body ? { "Content-Type": "application/json" } : {},
            body: body ? JSON.stringify(body) : undefined,
        });
    } catch {
        throw new Error("Can't reach the server. Is it running?");
    }
    const data = await response.json().catch(() => null);
    if (!response.ok) {
        throw new Error(data?.error ?? `Something went wrong (the server answered ${response.status})`);
    }
    return data;
}

async function load() {
    try {
        const listed = await api("GET", "/api/projects");
        state.projects = withRecentlyAdded(listed).filter(project => project.endsAt > Date.now());
        onlyLatestRunning(state.projects);
        // Clears an error from an earlier fetch, but not one from something the user just did.
        if (state.loadFailed) showError(null);
    } catch (error) {
        showError(error.message);
        state.loadFailed = true;
    }
    state.dayEndsAt = state.projects[0]?.endsAt ?? nextMidnight();
    render();
    // Shows the page once the first load has put everything in place (see "ready" in style.css).
    document.body.classList.add("ready");
}

// A workflow that has just started can take a moment to show up in the list. So a project added here in
// the last LIST_LAG_MS is kept even if the list doesn't have it yet.
function withRecentlyAdded(listed) {
    const now = Date.now();
    const projects = [...listed];
    for (const [name, addedAt] of state.recentlyAdded) {
        const local = state.projects.find(project => project.name === name);
        if (now - addedAt > LIST_LAG_MS) {
            state.recentlyAdded.delete(name);
        } else if (local && !projects.some(project => project.name === name)) {
            projects.push(local);
        }
    }
    return projects;
}

async function add(name) {
    showError(null);
    page.addButton.disabled = true;
    try {
        // Uses the project the server sends back instead of fetching the list, which may not have it yet.
        state.projects.push(await api("POST", "/api/projects", { name }));
        state.recentlyAdded.set(name, Date.now());
        page.name.value = "";
        render();
    } catch (error) {
        showError(error.message);
    } finally {
        page.addButton.disabled = false;
        page.name.focus();
    }
}

async function startOrPause(project) {
    const action = isRunning(project) ? "pause" : "start";
    showError(null);
    state.busy.add(project.name);
    render();
    try {
        const updated = await api("POST", `/api/projects/${encodeURIComponent(project.name)}/${action}`);
        if (action === "start") {
            pauseOthers(updated, state.projects);
        }
        replace(updated);
    } catch (error) {
        showError(error.message);
    } finally {
        state.busy.delete(project.name);
        render();
    }
}

// The server pauses the project that was running with a signal, which its workflow handles a moment
// after the start request returns. Fetching the list right away could still show it running, so the
// page pauses it here instead, at the same time the server does: when the started project began running.
function pauseOthers(started, projects) {
    for (const other of projects) {
        if (other.name !== started.name && isRunning(other)) {
            const pausedAt = Math.min(started.runningSince, other.endsAt);
            other.totalMs += Math.max(0, pausedAt - other.runningSince);
            other.runningSince = null;
        }
    }
}

// Only one project runs at a time. Right after a start, the list can still show the one before it
// running, for the same reason as above, so the project started last wins.
function onlyLatestRunning(projects) {
    const running = projects.filter(isRunning).sort((a, b) => b.runningSince - a.runningSince);
    if (running.length > 1) pauseOthers(running[0], projects);
}

function replace(updated) {
    const index = state.projects.findIndex(project => project.name === updated.name);
    if (index === -1) {
        state.projects.push(updated);
    } else {
        state.projects[index] = updated;
    }
}

// ---- the end of the day ----

function nextMidnight() {
    const midnight = new Date();
    midnight.setHours(24, 0, 0, 0);
    return midnight.getTime();
}

// At the end of the day, today's workflows finish and the page moves to the new day: a new date, and the
// new day's projects, none until one is added. This runs from tick() once it notices the day has ended,
// rather than from a timer set for midnight, so it still happens if the computer was asleep at midnight.
function startNewDay() {
    state.projects = state.projects.filter(project => project.endsAt > Date.now());
    state.dayEndsAt = nextMidnight();
    showDate();
    render();
    load();
}

function showDate() {
    page.today.textContent = new Intl.DateTimeFormat(undefined, { weekday: "long", month: "long", day: "numeric" })
        .format(new Date());
}

// ---- showing it ----

function isRunning(project) {
    return project.runningSince != null;
}

// Time stops counting at the end of the day, like in the workflow.
function elapsedMs(project, now) {
    const runningMs = isRunning(project) ? Math.max(0, Math.min(now, project.endsAt) - project.runningSince) : 0;
    return project.totalMs + runningMs;
}

// 3723000 → "1:02:03"
function formatDuration(ms) {
    const seconds = Math.floor(ms / 1000);
    const pad = n => String(n).padStart(2, "0");
    return `${Math.floor(seconds / 3600)}:${pad(Math.floor(seconds / 60) % 60)}:${pad(seconds % 60)}`;
}

// 18720000 → "5 h 12 m"
function formatTimeLeft(ms) {
    const minutes = Math.floor(ms / 60_000);
    if (minutes < 1) return "less than a minute";
    const hours = Math.floor(minutes / 60);
    return hours > 0 ? `${hours} h ${minutes % 60} m` : `${minutes} m`;
}

function buttonLabel(project) {
    if (isRunning(project)) return "Pause";
    return project.totalMs > 0 ? "Resume" : "Start";
}

// Whether the running project was nudged during the stretch it's in now.
function nudgedWhileRunning(project) {
    return isRunning(project) && project.nudgedAt != null && project.nudgedAt >= project.runningSince;
}

// Each project gets a color in the order it was added, wrapping around after the last one.
function colorAt(index) {
    return `var(--project-${index % PROJECT_COLORS})`;
}

function rowFor(name) {
    return [...page.list.children].find(row => row.dataset.name === name);
}

// Shows an error until the next thing the user does, or hides it when the message is null.
function showError(message) {
    page.error.textContent = message ?? "";
    page.error.hidden = message == null;
    state.loadFailed = false;
}

// Brings the page in line with state.projects. Rows are kept and updated rather than rebuilt,
// so a button keeps keyboard focus when its project changes.
function render() {
    const rows = new Map([...page.list.children].map(row => [row.dataset.name, row]));
    state.projects.forEach((project, index) => {
        const row = rows.get(project.name) ?? newRow(project.name);
        rows.delete(project.name);

        row.style.setProperty("--c", colorAt(index));
        row.classList.toggle("running", isRunning(project));

        // The button only shows a play or pause icon, so its label says what it does.
        const button = row.querySelector(".toggle");
        const label = `${buttonLabel(project)} ${project.name}`;
        button.classList.toggle("pause", isRunning(project));
        button.setAttribute("aria-label", label);
        button.title = label;
        button.disabled = state.busy.has(project.name);

        // A bell only while the nudge is current: the project is running and was nudged during this stretch.
        row.querySelector(".nudged").hidden = !nudgedWhileRunning(project);

        if (page.list.children[index] !== row) {
            page.list.insertBefore(row, page.list.children[index] ?? null);
        }
    });
    rows.forEach(row => row.remove());

    const hasProjects = state.projects.length > 0;
    page.empty.hidden = hasProjects;
    page.now.hidden = !hasProjects;
    page.summary.hidden = !hasProjects;

    const runningIndex = state.projects.findIndex(isRunning);
    const running = state.projects[runningIndex];
    page.now.classList.toggle("running", running != null);
    page.nowNudge.hidden = !(running && nudgedWhileRunning(running));
    if (running) {
        page.now.style.setProperty("--c", colorAt(runningIndex));
        page.nowName.textContent = running.name;
        page.nowPause.disabled = state.busy.has(running.name);
    }
    // The glow at the top of the page takes the running project's color.
    document.body.style.setProperty("--glow", running ? colorAt(runningIndex) : "var(--accent)");

    tick();
}

function newRow(name) {
    const row = page.row.content.firstElementChild.cloneNode(true);
    row.dataset.name = name;
    row.querySelector(".name-text").textContent = name;
    row.querySelector(".toggle").addEventListener("click", () => {
        const project = state.projects.find(p => p.name === name);
        if (project) startOrPause(project);
    });
    return row;
}

// Updates the times, the breakdown bar and the time left in the day, and moves to the new day once
// today has ended. Only text and widths change here, so it's cheap to run every second.
function tick() {
    const now = Date.now();
    if (now >= state.dayEndsAt) {
        startNewDay();
        return;
    }

    const times = state.projects.map(project => elapsedMs(project, now));
    const total = times.reduce((sum, ms) => sum + ms, 0);

    state.projects.forEach((project, index) => {
        const row = rowFor(project.name);
        if (row) row.querySelector(".time").textContent = formatDuration(times[index]);
    });
    page.dayTotal.textContent = formatDuration(total);
    renderBreakdown(times);
    if (Number.isFinite(state.dayEndsAt)) {
        page.dayEnds.textContent = `The day ends at midnight, in ${formatTimeLeft(state.dayEndsAt - now)}.`;
    }

    const running = state.projects.find(isRunning);
    if (running) page.nowTime.textContent = formatDuration(elapsedMs(running, now));
    document.title = running ? `${formatDuration(elapsedMs(running, now))} · ${running.name}` : "Day Timer";
}

// One segment per project, as wide as its share of the day's time. Projects with no time yet are hidden.
function renderBreakdown(times) {
    const bar = page.breakdown;
    while (bar.children.length < times.length) bar.append(document.createElement("span"));
    while (bar.children.length > times.length) bar.lastElementChild.remove();
    times.forEach((ms, index) => {
        const segment = bar.children[index];
        segment.style.setProperty("--c", colorAt(index));
        segment.style.flexGrow = ms;
        segment.hidden = ms === 0;
    });
}

// Ticks just after each whole second of the running project's time, so the seconds change evenly.
function scheduleTick() {
    tick();
    const running = state.projects.find(isRunning);
    const untilNextSecond = running ? 1000 - (elapsedMs(running, Date.now()) % 1000) : 1000;
    setTimeout(scheduleTick, untilNextSecond + 10);
}

// ---- wiring it up ----

page.form.addEventListener("submit", event => {
    event.preventDefault();
    const name = page.name.value.trim();
    if (name === "") {
        page.name.value = "";
        showError("A project needs a name");
        return;
    }
    add(name);
});

page.nowPause.addEventListener("click", () => {
    const running = state.projects.find(isRunning);
    if (running) startOrPause(running);
});

// Projects can also change from somewhere else, like a nudge from the workflow, curl or another tab,
// so the page fetches them again when it comes back into view, and every REFRESH_MS while it's in view.
document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") load();
});
setInterval(() => {
    if (document.visibilityState === "visible" && state.busy.size === 0) load();
}, REFRESH_MS);

showDate();
load();
scheduleTick();
