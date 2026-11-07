# Temporal Day Timer Roadmap

Temporal Day Timer is a small web app for tracking time spent on projects during a single day. It's a project for learning Temporal: each project's timer is a Temporal workflow that lives until midnight. The backend is written by hand to learn Temporal, and the web page is written with Claude.

## Requirements

- [ ] Users can start a timer for a project they're working on.
- [ ] Users can pause and resume a timer at any time.
- [ ] Only one timer runs at a time. Starting a timer pauses the one that's running.
- [ ] Users can add new projects during the day and work on them.
- [ ] Timers only last for the day. At local midnight they end, and the next day starts empty.
- [ ] When a timer has been running for a long time, the user gets a "Still running?" nudge.

## Design

- Each project gets its own `projectTimer` workflow for the day, with the ID `project-<name>-<date>`.
- A workflow keeps two numbers: `totalMs`, the time from finished stretches, and `runningSince`, set while the project is running. The page shows `totalMs + (now − runningSince)` and does the ticking itself. Nothing counts inside the workflow.
- Temporal timers are only used for things that happen at a set time: the end of the day and the nudge.
- The HTTP server works out when the day ends (`endsAt`, the next local midnight) and passes it to each workflow, so workflow code never deals with time zones. Tests can pass a short day instead.
- The HTTP server keeps one project running at a time. When a project starts, it pauses the running one first.
- The HTTP server records the time each request arrives and passes it to the workflow, so a delay before the worker handles it is never counted.
- Today's projects are the `projectTimer` workflows that are still running, since they all end at midnight.

## API

```
GET  /api/today                  → Day
POST /api/projects  { name }     → Day   add a project (starts its workflow, paused)
POST /api/start     { project }  → Day   start or resume; pauses any other
POST /api/pause     { project }  → Day

Day = {
  date: "2026-11-06",
  endsAt: 1762502400000,
  projects: [{ name, totalMs, runningSince, nudgedAt }]
}
```

`runningSince` and `nudgedAt` are `null` when not set. Every route returns the whole day, so the page redraws from the response. Errors, like a duplicate project name, come back as `{ error }` with a 4xx status.

## Build plan

Each step is its own PR. Backend steps are written by hand, and frontend steps are written with Claude.

- [ ] 1. Project setup (backend): folders for the worker, the HTTP server, and `web/`, with the Temporal SDK installed. The README explains how to run the dev server and the worker. An empty `projectTimer` workflow can be started from the Temporal CLI, and the test runner uses the time-skipping test environment, with one passing test.
- [ ] 2. The `projectTimer` workflow (backend): start and pause, a query that returns the project's state, and sleeping until `endsAt`, then closing any running stretch and completing. It can be driven from the Temporal CLI, and tests cover the totals and midnight with skipped time.
- [ ] 3. HTTP API (backend): the four routes above. It lists today's workflows and queries each one to build the day. Starting a project pauses the running one, then uses signal-with-start. It also serves the `web/` folder. A whole day can be run with `curl`.
- [ ] 4. Web page (frontend): today's projects with their times ticking, an "Add project" field, a Start/Pause button for each project, and errors from the API. It's built against a fake API while steps 2 and 3 are in progress.
- [ ] 5. "Still running?" nudge (backend): while a project runs, its workflow sets a 2-hour timer (configurable, and 1 minute while testing). Pausing cancels it. When it fires, a `notify` activity shows a Mac notification and the workflow sets `nudgedAt`.
- [ ] 6. Nudge and midnight on the page (frontend): show when the day ends, move to the new day without a reload, and mark projects that have been nudged.

## Things to try

- Stop the worker while a project is running, then start it again. The time is still right.
- Restart the dev server (started with `--db-filename`) and check that today's projects are still there.
- Look at a workflow's event history in the Web UI after starting, pausing, and switching projects.

## Later

- [ ] Auto-pause: if a nudge isn't answered within 15 minutes, the workflow pauses the project, counting time only up to when the nudge fired. The page gets a "Still working" button.
- [ ] Coordinator workflow: every start goes through one workflow that signals the project workflows, so two quick Start clicks can't leave two projects running.
- [ ] Versioning experiment: change the nudge logic while a project is running, hit the nondeterminism error, and fix it with patching.
