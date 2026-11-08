# temporal-day-timer

A small web app for tracking time spent on projects during a single day, built to learn Temporal. Each project's timer is a Temporal workflow that lives until midnight. See [ROADMAP.md](ROADMAP.md) for the plan.

## Prerequisites

- JDK 25 or later
- Maven
- [Temporal CLI](https://docs.temporal.io/cli) (`brew install temporal`)

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

The tests use Temporal's time-skipping test environment, so they don't need the dev server.
