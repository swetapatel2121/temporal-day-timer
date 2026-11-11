package httpserver;

import daytimer.model.Project;
import daytimer.workflow.ProjectTimerWorkflow;
import daytimer.workflow.ProjectTimerWorkflowImpl;
import daytimer.workflow.ProjectTimerWorkflowInput;
import daytimer.workflow.ProjectTimerWorkflowOutput;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// Runs the HTTP server against a throwaway Temporal dev server with a real worker, and calls it over HTTP.
// All the tests share one Temporal server, so each test uses its own project names.
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ProjectTimerServerTest {

    private static final JavalinJackson JSON = new JavalinJackson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    // For workflows these tests start directly. Longer than the tests run, so no nudge comes.
    private static final long NUDGE_AFTER_MS = 2 * 60 * 60 * 1000;

    private static TemporalDevServer temporal;
    private static WorkflowClient client;
    private static WorkerFactory workers;
    private static Javalin app;

    @BeforeAll
    static void startServers() throws Exception {
        temporal = TemporalDevServer.start();
        client = WorkflowClient.newInstance(temporal.service());
        workers = WorkerFactory.newInstance(client);
        workers.newWorker("project-timers").registerWorkflowImplementationTypes(ProjectTimerWorkflowImpl.class);
        workers.start();
        app = ProjectTimerServer.createApp(client).start(0);
    }

    @AfterAll
    static void stopServers() throws Exception {
        // Stop Temporal first, so any request still waiting on it fails right away instead of holding up the rest.
        if (temporal != null) temporal.close();
        if (workers != null) workers.shutdownNow();
        if (app != null) app.stop();
    }

    @Test
    void addReturnsTheNewProjectPaused() throws Exception {
        HttpResponse<String> response = post("/api/projects", "{\"name\":\"add me\"}");

        assertEquals(201, response.statusCode(), response.body());
        assertEquals(new Project("add me", today(), midnight(), 0, null, null), project(response));
    }

    @Test
    void addStripsSpacesAndRefusesADuplicateName() throws Exception {
        assertEquals("add twice", project(post("/api/projects", "{\"name\":\"  add twice  \"}")).name());

        HttpResponse<String> again = post("/api/projects", "{\"name\":\"add twice\"}");

        assertEquals(409, again.statusCode(), again.body());
        assertHasError(again);
    }

    @Test
    void addRefusesAMissingOrBlankName() throws Exception {
        for (String body : List.of("{}", "{\"name\":\"   \"}")) {
            HttpResponse<String> response = post("/api/projects", body);

            assertEquals(400, response.statusCode(), body);
            assertHasError(response);
        }
    }

    @Test
    void addRefusesABodyThatIsNotJson() throws Exception {
        HttpResponse<String> response = post("/api/projects", "not json");

        assertEquals(400, response.statusCode(), response.body());
        assertHasError(response);
    }

    @Test
    void startAndPauseTrackTheTime() throws Exception {
        add("track time");

        long beforeStart = System.currentTimeMillis();
        HttpResponse<String> started = post("/api/projects/track%20time/start", "");
        long afterStart = System.currentTimeMillis();

        assertEquals(200, started.statusCode(), started.body());
        assertEquals(0, project(started).totalMs());
        assertBetween(beforeStart, afterStart, project(started).runningSince());

        HttpResponse<String> paused = post("/api/projects/track%20time/pause", "");
        long afterPause = System.currentTimeMillis();

        assertEquals(200, paused.statusCode(), paused.body());
        assertNull(project(paused).runningSince());
        assertBetween(0, afterPause - beforeStart, project(paused).totalMs());
    }

    @Test
    void startPausesTheProjectThatWasRunning() throws Exception {
        add("switch from");
        add("switch to");
        post("/api/projects/switch%20from/start", "");
        // The server finds the running project through the workflow list, which can lag a moment behind.
        eventually(() -> listed("switch from"), projects -> !projects.isEmpty());

        HttpResponse<String> started = post("/api/projects/switch%20to/start", "");

        assertEquals(200, started.statusCode(), started.body());
        assertNotNull(project(started).runningSince());
        // The server pauses the other project with a signal, which its workflow handles a moment later.
        Project previous = eventually(() -> workflow("switch from").getProject(), p -> p.runningSince() == null);
        assertNull(previous.runningSince(), "switch from is still running");
    }

    @Test
    void startAndPauseReturn404ForAProjectThatDoesNotExist() throws Exception {
        for (String action : List.of("start", "pause")) {
            HttpResponse<String> response = post("/api/projects/never%20added/" + action, "");

            assertEquals(404, response.statusCode(), action + ": " + response.body());
            assertHasError(response);
        }
    }

    @Test
    void listShowsTodaysProjectsInTheOrderTheyWereAdded() throws Exception {
        add("list first");
        add("list second");
        post("/api/projects/list%20first/start", "");

        List<Project> listed = eventually(() -> listed("list first", "list second"), projects -> projects.size() == 2);

        assertEquals(List.of("list first", "list second"), listed.stream().map(Project::name).toList());
        assertNotNull(listed.get(0).runningSince());
        assertNull(listed.get(1).runningSince());
    }

    @Test
    void listLeavesOutProjectsFromEarlierDays() throws Exception {
        // A project from an earlier day: its workflow has already completed.
        ProjectTimerWorkflow earlier = client.newWorkflowStub(ProjectTimerWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue("project-timers").setWorkflowId("project-earlier-2000-01-01").build());
        WorkflowClient.start(earlier::run, new ProjectTimerWorkflowInput("earlier", "2000-01-01", System.currentTimeMillis() - 1, NUDGE_AFTER_MS));
        WorkflowStub.fromTyped(earlier).getResult(ProjectTimerWorkflowOutput.class);
        add("list today");

        List<Project> listed = eventually(() -> listed("list today", "earlier"),
                projects -> projects.stream().map(Project::name).toList().equals(List.of("list today")));

        assertEquals(List.of("list today"), listed.stream().map(Project::name).toList());
    }

    @Test
    void projectsFromEarlierDaysThatAreStillRunningAreLeftOut() throws Exception {
        // A project from an earlier day whose midnight timer hasn't fired yet, like when the computer was asleep
        // at midnight: its workflow is still running, and the project is too.
        ProjectTimerWorkflow late = client.newWorkflowStub(ProjectTimerWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue("project-timers").setWorkflowId("project-late-2000-01-01").build());
        WorkflowClient.start(late::run, new ProjectTimerWorkflowInput("late", "2000-01-01", midnight(), NUDGE_AFTER_MS));
        late.start(System.currentTimeMillis());
        add("after late");

        HttpResponse<String> started = post("/api/projects/after%20late/start", "");

        assertEquals(200, started.statusCode(), started.body());
        List<Project> listed = eventually(() -> listed("after late", "late"),
                projects -> projects.stream().anyMatch(project -> project.name().equals("after late")));
        assertEquals(List.of("after late"), listed.stream().map(Project::name).toList());
        assertNotNull(late.getProject().runningSince(), "starting a project today paused one from an earlier day");
    }

    @Test
    void servesTheWebPage() throws Exception {
        HttpResponse<String> response = get("/");

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("<title>Day Timer</title>"), response.body());
    }

    // ---- helpers ----

    private static HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + app.port() + path);
    }

    private static void add(String name) throws Exception {
        HttpResponse<String> response = post("/api/projects", "{\"name\":\"" + name + "\"}");
        assertEquals(201, response.statusCode(), "adding " + name + ": " + response.body());
    }

    // GET /api/projects, keeping only the given names, since other tests' projects are in the list too.
    private static List<Project> listed(String... names) throws Exception {
        HttpResponse<String> response = get("/api/projects");
        assertEquals(200, response.statusCode(), response.body());
        List<String> wanted = List.of(names);
        return Arrays.stream(JSON.<Project[]>fromJsonString(response.body(), Project[].class))
                .filter(project -> wanted.contains(project.name()))
                .toList();
    }

    private static ProjectTimerWorkflow workflow(String name) {
        return client.newWorkflowStub(ProjectTimerWorkflow.class, "project-" + name + "-" + today());
    }

    private static Project project(HttpResponse<String> response) {
        return JSON.fromJsonString(response.body(), Project.class);
    }

    private static void assertHasError(HttpResponse<String> response) {
        Map<?, ?> body;
        try {
            body = JSON.fromJsonString(response.body(), Map.class);
        } catch (RuntimeException notJson) {
            fail("Expected an { error } JSON body but got: " + response.body());
            return;
        }
        assertTrue(body.get("error") instanceof String error && !error.isBlank(), "No error message in " + response.body());
    }

    private static void assertBetween(long min, long max, Long actual) {
        assertNotNull(actual);
        assertTrue(min <= actual && actual <= max, actual + " is not between " + min + " and " + max);
    }

    // Reads a value again and again for up to 5 seconds until the check passes, for things that happen
    // a moment after a request returns. Returns the last value read, so the assertion after it shows what went wrong.
    private static <T> T eventually(Callable<T> read, Predicate<T> check) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        T value = read.call();
        while (!check.test(value) && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
            value = read.call();
        }
        return value;
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    private static long midnight() {
        return LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
