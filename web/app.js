// The day timer page. It talks to the HTTP API described in ROADMAP.md and does the ticking itself:
// a project's time is its totalMs, plus (now − runningSince) while it's running.

const state = {
    projects: [],      // today's projects, in the order they were added
    busy: new Set(),   // names of projects with a start or pause request on its way
    loadFailed: false, // whether the error on show came from fetching the list
};

// How many project colors style.css has (--project-0 … --project-7).
const PROJECT_COLORS = 8;

const page = {
    today: document.getElementById("today"),
    now: document.getElementById("now"),
    nowName: document.getElementById("now-name"),
    nowTime: document.getElementById("now-time"),
    nowPause: document.getElementById("now-pause"),
    summary: document.getElementById("summary"),
    dayTotal: document.getElementById("day-total"),
    breakdown: document.getElementById("breakdown"),
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
        state.projects = await api("GET", "/api/projects");
        // Clears an error from an earlier fetch, but not one from something the user just did.
        if (state.loadFailed) showError(null);
    } catch (error) {
        showError(error.message);
        state.loadFailed = true;
    }
    render();
    // Shows the page once the first load has put everything in place (see "ready" in style.css).
    document.body.classList.add("ready");
}

async function add(name) {
    showError(null);
    page.addButton.disabled = true;
    try {
        // A workflow that has just started can take a moment to show up in the list,
        // so this uses the project the server sends back instead of fetching the list again.
        state.projects.push(await api("POST", "/api/projects", { name }));
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
            pauseOthers(updated);
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
function pauseOthers(started) {
    for (const other of state.projects) {
        if (other.name !== started.name && isRunning(other)) {
            other.totalMs += Math.max(0, started.runningSince - other.runningSince);
            other.runningSince = null;
        }
    }
}

function replace(updated) {
    const index = state.projects.findIndex(project => project.name === updated.name);
    if (index === -1) {
        state.projects.push(updated);
    } else {
        state.projects[index] = updated;
    }
}

// ---- showing it ----

function isRunning(project) {
    return project.runningSince != null;
}

function elapsedMs(project, now) {
    return project.totalMs + (isRunning(project) ? Math.max(0, now - project.runningSince) : 0);
}

// 3723000 → "1:02:03"
function formatDuration(ms) {
    const seconds = Math.floor(ms / 1000);
    const pad = n => String(n).padStart(2, "0");
    return `${Math.floor(seconds / 3600)}:${pad(Math.floor(seconds / 60) % 60)}:${pad(seconds % 60)}`;
}

function buttonLabel(project) {
    if (isRunning(project)) return "Pause";
    return project.totalMs > 0 ? "Resume" : "Start";
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
    row.querySelector(".name").textContent = name;
    row.querySelector(".toggle").addEventListener("click", () => {
        const project = state.projects.find(p => p.name === name);
        if (project) startOrPause(project);
    });
    return row;
}

// Updates the times and the breakdown bar. Only numbers and widths change here,
// so it's cheap to run every second.
function tick() {
    const now = Date.now();
    const times = state.projects.map(project => elapsedMs(project, now));
    const total = times.reduce((sum, ms) => sum + ms, 0);

    state.projects.forEach((project, index) => {
        const row = rowFor(project.name);
        if (row) row.querySelector(".time").textContent = formatDuration(times[index]);
    });
    page.dayTotal.textContent = formatDuration(total);
    renderBreakdown(times);

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

page.today.textContent = new Intl.DateTimeFormat(undefined, { weekday: "long", month: "long", day: "numeric" })
    .format(new Date());

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

// Projects can also change from somewhere else, like curl or another tab, so the page fetches
// them again whenever it comes back into view.
document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") load();
});

load();
scheduleTick();
