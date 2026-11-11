package httpserver;

import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

// A throwaway Temporal dev server for tests, started with the Temporal CLI. It keeps everything in memory
// and uses free ports, so it never touches the dev server you run with `make temporal`.
// The server tests need a real server because Temporal's in-memory test environment can't list workflows.
final class TemporalDevServer implements AutoCloseable {

    private final Process process;
    private final WorkflowServiceStubs service;

    private TemporalDevServer(Process process, WorkflowServiceStubs service) {
        this.process = process;
        this.service = service;
    }

    // Where to look for the `temporal` command. Apps opened from the Dock, like IntelliJ, may not have
    // Homebrew's folder on their PATH, so this also checks where Homebrew and Temporal's install script put it.
    private static final List<String> CLI_LOCATIONS = List.of(
            "temporal",
            "/opt/homebrew/bin/temporal",
            "/usr/local/bin/temporal",
            System.getProperty("user.home") + "/.temporalio/bin/temporal");

    // Starts the server and waits until it's ready to take workflows.
    static TemporalDevServer start() throws IOException, InterruptedException {
        String cli = findCli();
        int port = freePort();
        Process process = new ProcessBuilder(cli, "server", "start-dev", "--headless", "--log-level", "error",
                "--port", String.valueOf(port), "--ui-port", String.valueOf(freePort()))
                .redirectErrorStream(true)
                .redirectOutput(new File("target", "temporal-dev-server.log"))
                .start();
        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget("127.0.0.1:" + port).build());
        TemporalDevServer server = new TemporalDevServer(process, service);
        server.waitUntilReady();
        return server;
    }

    WorkflowServiceStubs service() {
        return service;
    }

    private void waitUntilReady() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (true) {
            try {
                service.blockingStub().describeNamespace(DescribeNamespaceRequest.newBuilder().setNamespace("default").build());
                return;
            } catch (RuntimeException notReadyYet) {
                if (!process.isAlive() || Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("The Temporal dev server didn't start; see target/temporal-dev-server.log", notReadyYet);
                }
                Thread.sleep(200);
            }
        }
    }

    @Override
    public void close() throws InterruptedException {
        service.shutdownNow();
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }

    private static String findCli() throws InterruptedException {
        for (String cli : CLI_LOCATIONS) {
            try {
                if (new ProcessBuilder(cli, "--version").redirectErrorStream(true).start().waitFor() == 0) {
                    return cli;
                }
            } catch (IOException notThere) {
                // Try the next place.
            }
        }
        throw new IllegalStateException("The server tests need the Temporal CLI. Install it with: brew install temporal");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
