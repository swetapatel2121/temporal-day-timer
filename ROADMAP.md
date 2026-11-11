# Temporal Day Timer Roadmap

Temporal Day Timer is a small web app for tracking time spent on projects during a single day. It's a project for learning Temporal: each project's timer is a Temporal workflow that lives until midnight. The backend is written by hand to learn Temporal, and the web page is written with Claude.

## Requirements

- [x] Users can start a timer for a project they're working on.
- [x] Users can pause and resume a timer at any time.
- [x] Only one timer runs at a time. Starting a timer pauses the one that's running.
- [x] Users can add new projects during the day and work on them.
- [x] Timers only last for the day. At local midnight they end, and the next day starts empty.
- [x] When a timer has been running for a long time, the user gets a "Still running?" nudge.

## Design

- Each project gets its own `projectTimer` workflow for the day, with the ID `project-<name>-<date>`.
- A workflow keeps two numbers: `totalMs`, the time from finished stretches, and `runningSince`, set while the project is running. The page shows `totalMs + (now − runningSince)` and does the ticking itself. Nothing counts inside the workflow, and a stretch never counts past `endsAt`, even when the pause arrives after midnight.
- Temporal timers are only used for things that happen at a set time: the end of the day and the nudge.
- The HTTP server works out when the day ends (`endsAt`, the next local midnight) and passes it to each workflow, so workflow code never deals with time zones. Tests can pass a short day instead.
- The HTTP server keeps one project running at a time. When a project starts, the server signals the other running projects, and the one that was running pauses at the moment the new one started.
- The HTTP server records the time each request arrives and passes it to the workflow, so a delay before the worker handles it is never counted.
- Today's projects are the running `projectTimer` workflows whose ID ends in today's date. Workflows end at midnight, but the midnight timer can fire late, for example when the computer is asleep at midnight, so yesterday's can still be running for a while.

## API

```
GET  /api/projects                 → 200 [Project]   today's projects
POST /api/projects       { name }  → 201 Project     add a project (starts its workflow, paused)
POST /api/projects/{name}/start    → 200 Project     start or resume; pauses any other
POST /api/projects/{name}/pause    → 200 Project

Project = {
  name: "edit readme",
  date: "2026-11-06",
  endsAt: 1762502400000,
  totalMs: 1800000,
  runningSince: 1762450000000,
  nudgedAt: null
}
```

- `{name}` is the project's name, URL-encoded: `/api/projects/edit%20readme/start`. Names are unique for the day, like the workflow IDs.
- `runningSince` and `nudgedAt` are `null` when not set. Every project has the same `date` and `endsAt`, since they all end at midnight.
- Starting a project also pauses the running one. The server does that with a signal, which that project's workflow handles a moment after the start request returns, so fetching the list right away could still show it running. Instead, the page pauses it itself, using the started project's `runningSince` as the time, the same time the server uses.
- After adding a project, the page uses the project it gets back instead of fetching the list. A workflow that has just started can take a moment to show up in the list, so the page keeps a project it just added for 30 seconds even if the list doesn't show it yet.
- The page fetches the list again every 30 seconds while it's in view, and whenever it comes back into view, so a nudge shows up. If the list briefly shows two projects running, the one started last wins, as it does on the server.
- At the end of the day, the page moves to the new day by itself. It checks every second rather than setting a timer for midnight, so it also catches up after the computer wakes from sleep.
- Errors come back as `{ error }`: `404` for a project that doesn't exist today, `409` for a name that's already taken today, and `400` for a bad request.

## Build plan

Each step is its own PR. Backend steps are written by hand, and frontend steps are written with Claude.

- [x] 1. Project setup (backend): folders for the worker, the HTTP server, and `web/`, with the Temporal SDK installed. The README explains how to run the dev server and the worker. An empty `projectTimer` workflow can be started from the Temporal CLI, and the test runner uses the time-skipping test environment, with one passing test.
- [x] 2. The `projectTimer` workflow (backend): start and pause, a query that returns the project's state, and sleeping until `endsAt`, then closing any running stretch and completing. It can be driven from the Temporal CLI, and tests cover the totals and midnight with skipped time.
- [x] 3. HTTP API (backend): the four routes above. It lists today's workflows and queries each one to build the list of projects. Starting a project sends it a `start` update, then signals the other running projects so the running one pauses. It also serves the `web/` folder. A whole day can be run with `curl`.
- [x] 4. Web page (frontend): today's projects with their times ticking, an "Add project" field, a Start/Pause button for each project, and errors from the API.
- [x] 5. "Still running?" nudge (backend): while a project runs, its workflow sets a 2-hour timer (configurable with `NUDGE_AFTER_MS`, passed to each workflow as `nudgeAfterMs`). Pausing cancels it. When it fires, the workflow sets `nudgedAt` and a `notifyUser` activity shows a Mac notification.
- [x] 6. Nudge and midnight on the page (frontend): show when the day ends, move to the new day without a reload, and mark projects that have been nudged.

## Things to try

- Stop the worker while a project is running, then start it again. The time is still right.
- Restart the dev server (started with `--db-filename`) and check that today's projects are still there.
- Look at a workflow's event history in the Web UI after starting, pausing, and switching projects.

## Later

- [ ] Auto-pause: if a nudge isn't answered within 15 minutes, the workflow pauses the project, counting time only up to when the nudge fired. The page gets a "Still working" button.
- [ ] Coordinator workflow: every start goes through one workflow that signals the project workflows, so two quick Start clicks can't leave two projects running.
- [ ] Versioning experiment: change the nudge logic while a project is running, hit the nondeterminism error, and fix it with patching.
