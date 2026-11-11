# Shortcuts for common tasks. Run `make` or `make help` to list them.
.DEFAULT_GOAL := help
.PHONY: help build test clean temporal project-timer-worker server start-project-timer-workflow describe

PROJECT_TIMER_TASK_QUEUE := project-timers

help: ## List the available commands
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-30s %s\n", $$1, $$2}'

build: ## Compile the code
	mvn -q compile

test: ## Run the tests
	mvn test

clean: ## Delete build output
	mvn -q clean

temporal: ## Start the Temporal dev server, saved to temporal.db (Web UI at http://localhost:8233)
	temporal server start-dev --db-filename temporal.db

project-timer-worker: ## Start the worker (needs the dev server running)
	mvn -q compile exec:java@worker

server: ## Start the HTTP server and the web page at http://localhost:7070 (needs the dev server and the worker)
	mvn -q compile exec:java@server

start-project-timer-workflow: ## Start a workflow: make start-project-timer-workflow NAME=myproject [INPUT='{...}']
	@test -n "$(NAME)" || (echo "usage: make start-project-timer-workflow NAME=<project> [INPUT='<json>']" && exit 1)
	temporal workflow start --task-queue $(PROJECT_TIMER_TASK_QUEUE) --type ProjectTimerWorkflow \
		--workflow-id project-$(NAME)-$$(date +%F) $(if $(INPUT),--input '$(INPUT)')

describe: ## Show the pollers on the task queue (is the worker up?)
	temporal task-queue describe --task-queue $(PROJECT_TIMER_TASK_QUEUE)
