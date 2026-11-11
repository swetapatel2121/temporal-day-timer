package daytimer.workflow;

import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class ProjectTimerActivitiesImpl implements ProjectTimerActivities {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectTimerActivitiesImpl.class);

    // The web page `make server` serves, opened when the notification is clicked.
    private static final String PAGE_URL = "http://localhost:7070";

    // Shows a Mac notification. With terminal-notifier (brew install terminal-notifier), clicking it opens
    // the web page. Without it, or if macOS hasn't allowed it to send notifications yet, osascript shows
    // the notification instead, but then macOS decides what a click opens.
    @Override
    public void notifyUser(String name) {
        String message = "Is " + name + " still running?";
        Optional<String> terminalNotifier = terminalNotifier();
        if (terminalNotifier.isPresent()) {
            try {
                run(List.of(terminalNotifier.get(), "-title", "Day Timer", "-message", message, "-open", PAGE_URL,
                        "-group", "day-timer")); // a new nudge replaces the last one instead of piling up
                return;
            } catch (RuntimeException notAllowed) {
                LOG.warn("terminal-notifier couldn't show the nudge, so osascript will: {}", notAllowed.getMessage());
            }
        }
        // The message is passed as an argument instead of inside the script, so quotes can't break it.
        run(List.of("osascript",
                "-e", "on run argv",
                "-e", "display notification (item 1 of argv) with title \"Day Timer\"",
                "-e", "end run",
                message));
    }

    // Looks where Homebrew installs it as well as on the PATH, since apps opened from the Dock, like IntelliJ,
    // may not have Homebrew's folder on their PATH.
    private static Optional<String> terminalNotifier() {
        Stream<String> folders = Stream.concat(
                Stream.of("/opt/homebrew/bin", "/usr/local/bin"),
                Arrays.stream(System.getenv().getOrDefault("PATH", "").split(":")));
        return folders
                .filter(folder -> !folder.isEmpty())
                .map(folder -> Path.of(folder, "terminal-notifier"))
                .filter(Files::isExecutable)
                .map(Path::toString)
                .findFirst();
    }

    private static void run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(command.getFirst() + " didn't finish in 5 seconds");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(command.getFirst() + " failed: "
                        + new String(process.getInputStream().readAllBytes()));
            }
        } catch (IOException | InterruptedException e) {
            throw Activity.wrap(e); // turns checked exceptions into ones Temporal can report and retry
        }
    }
}
