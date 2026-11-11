package httpserver;

import com.fasterxml.jackson.core.JsonProcessingException;
import daytimer.model.Project;
import daytimer.workflow.ProjectTimerWorkflow;
import daytimer.workflow.ProjectTimerWorkflowInput;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.temporal.client.*;
import io.temporal.serviceclient.WorkflowServiceStubs;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ProjectTimerServer {
    record NewProject(String name) {}

    // How long a project runs before its "Still running?" nudge: 2 hours, or NUDGE_AFTER_MS to try it sooner.
    // Each workflow gets the value when it starts, so changing it only affects projects added afterwards.
    private static final long NUDGE_AFTER_MS = Long.parseLong(
            System.getenv().getOrDefault("NUDGE_AFTER_MS", String.valueOf(Duration.ofHours(2).toMillis())));

    public static void main(String[] args) {
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);

        createApp(client).start(7070);
    }

    // Sets up the routes without starting the server, so tests can run it against their own Temporal server.
    public static Javalin createApp(WorkflowClient client) {
        // Also serves the web page from the web/ folder. It's read from disk, so changes show up on reload.
        Javalin app = Javalin.create(config -> config.staticFiles.add("web", Location.EXTERNAL));

        app.get("/api/projects", ctx -> {
            List<Project> projects = todaysWorkflowIds(client).stream()
                    .map(id -> client.newWorkflowStub(ProjectTimerWorkflow.class, id).getProject())
                    .toList();
            ctx.json(projects);
        });

        app.post("/api/projects", ctx -> {
            String name = ctx.bodyAsClass(NewProject.class).name();
            if (name == null || name.isBlank()) {
                ctx.status(400).json(Map.of("error", "A project needs a name"));
                return;
            }
            name = name.strip();

            LocalDate today = LocalDate.now();
            long endsAt = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

            ProjectTimerWorkflow workflow = client.newWorkflowStub(ProjectTimerWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue("project-timers")
                            .setWorkflowId(workflowId(name))
                            .build());
            try {
                // Starts the workflow without waiting for it: run() only finishes at midnight.
                WorkflowClient.start(workflow::run,
                        new ProjectTimerWorkflowInput(name, today.toString(), endsAt, NUDGE_AFTER_MS));
            } catch (WorkflowExecutionAlreadyStarted e) {
                ctx.status(409).json(Map.of("error", "There's already a project called " + name + " today"));
                return;
            }

            ctx.status(201).json(workflow.getProject());
        });

        app.post("/api/projects/{name}/start", ctx -> {
            String name = ctx.pathParam("name");
            long at = System.currentTimeMillis();
            String id = workflowId(name);

            // Start this project first, so an unknown name gets a 404 before anything else is paused.
            Project started = client.newWorkflowStub(ProjectTimerWorkflow.class, id).start(at);

            // Then tell today's other projects that this one started, so the running one pauses.
            todaysWorkflowIds(client).stream()
                    .filter(other -> !other.equals(id))
                    .forEach(other -> {
                        try {
                            client.newWorkflowStub(ProjectTimerWorkflow.class, other).otherProjectStarted(at);
                        } catch (WorkflowNotFoundException finishedMeanwhile) {
                            // The list can lag behind: this project already finished, so there's nothing to pause.
                        }
                    });

            ctx.json(started);
        });

        app.post("/api/projects/{name}/pause", ctx -> {
            String name = ctx.pathParam("name");
            long at = System.currentTimeMillis();

            ProjectTimerWorkflow project = client.newWorkflowStub(ProjectTimerWorkflow.class, workflowId(name));
            ctx.json(project.pause(at));
        });

        app.exception(WorkflowNotFoundException.class, (e, ctx) ->
                ctx.status(404).json(Map.of("error", "No project called " + ctx.pathParam("name") + " today")));
        app.exception(JsonProcessingException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("error", "The request body isn't valid JSON")));

        return app;
    }

    private static String workflowId(String name) {
        return "project-" + name + "-" + LocalDate.now();
    }

    // The IDs of today's project workflows, in the order they were added. A workflow from an earlier day
    // can still be running for a while after midnight if its timer fires late (say the computer was asleep),
    // so this keeps only the IDs that end in today's date, the same date workflowId() uses.
    private static List<String> todaysWorkflowIds(WorkflowClient client) {
        String today = "-" + LocalDate.now();
        return client.listExecutions("WorkflowType = 'ProjectTimerWorkflow' AND ExecutionStatus = 'Running'")
                .sorted(Comparator.comparing(WorkflowExecutionMetadata::getStartTime))
                .map(execution -> execution.getExecution().getWorkflowId())
                .filter(id -> id.endsWith(today))
                .toList();
    }
}
