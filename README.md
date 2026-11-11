# temporal-day-timer

A small web app for tracking time spent on projects during a single day, built to learn Temporal. Each project's timer is a Temporal workflow that lives until midnight. See [ROADMAP.md](ROADMAP.md) for the plan.

## Prerequisites

- JDK 25 or later
- Maven
- [Temporal CLI](https://docs.temporal.io/cli) (`brew install temporal`)
- Optional: [terminal-notifier](https://github.com/julienXX/terminal-notifier) (`brew install terminal-notifier`), so clicking a nudge opens the web page

## Running it

Run each command in its own terminal. Run `make` to list all the commands.

Start the Temporal dev server. Workflows are saved to `temporal.db`, and the Web UI is at http://localhost:8233.

```bash
make temporal
```

Start the project timer worker:

```bash
make project-timer-worker
```

Start the HTTP server, then open the web page at http://localhost:7070:

```bash
make server
```

The page is plain HTML, CSS and JavaScript in `web/`, with no build step. The server reads it from disk, so a reload picks up changes.

A project that runs for 2 hours gets a "Still running?" Mac notification. To try it without waiting, start the server with a shorter nudge, in milliseconds. It applies to projects added after the server starts:

```bash
NUDGE_AFTER_MS=60000 make server
```

The notification is shown by the worker, so restart the worker after changing workflow or activity code. With terminal-notifier installed, clicking the notification opens the web page. Allow terminal-notifier to send notifications in System Settings → Notifications first. Until then, or without it, the nudge is shown with `osascript`, and clicking it opens whatever macOS picks. If no notification appears at all, check that Script Editor is allowed to send notifications too.

Start a project timer workflow:

```bash
make start-project-timer-workflow NAME=myproject
```

Check that the worker is polling its task queue:

```bash
make describe
```

## Tests

```bash
make test
```

The workflow tests use Temporal's time-skipping test environment. The HTTP server tests start their own throwaway dev server with the Temporal CLI, so they don't need, or touch, the one from `make temporal`.
