package daytimer.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Unit tests for the notification activity. Nothing really runs: a fake runner records each command,
// and fails the ones whose program is in `failing`.
class ProjectTimerActivitiesTest {

    private static final String TERMINAL_NOTIFIER = "/opt/homebrew/bin/terminal-notifier";

    private final List<List<String>> ran = new ArrayList<>();
    private Set<String> failing = Set.of();

    private final ProjectTimerActivitiesImpl.CommandRunner fakeRunner = command -> {
        ran.add(command);
        if (failing.contains(command.getFirst())) {
            throw new IllegalStateException(command.getFirst() + " failed");
        }
    };

    @Test
    void usesTerminalNotifierSoAClickOpensThePage() {
        withTerminalNotifier().notifyUser("edit readme");

        assertEquals(List.of(List.of(TERMINAL_NOTIFIER,
                "-title", "Day Timer",
                "-message", "Is edit readme still running?",
                "-open", "http://localhost:7070",
                "-group", "day-timer")), ran);
    }

    @Test
    void usesOsascriptWhenTerminalNotifierIsNotInstalled() {
        withoutTerminalNotifier().notifyUser("edit readme");

        assertEquals(List.of("osascript"), programsRun());
        assertEquals("Is edit readme still running?", ran.getFirst().getLast());
    }

    @Test
    void osascriptGetsTheMessageAsAnArgumentSoQuotesCannotBreakTheScript() {
        withoutTerminalNotifier().notifyUser("say \"hi\"");

        List<String> command = ran.getFirst();
        assertEquals("Is say \"hi\" still running?", command.getLast());
        assertEquals(List.of(), command.subList(0, command.size() - 1).stream()
                .filter(part -> part.contains("hi")).toList(), "the name ended up inside the script");
    }

    @Test
    void fallsBackToOsascriptWhenTerminalNotifierFails() {
        failing = Set.of(TERMINAL_NOTIFIER); // like when macOS hasn't allowed its notifications

        withTerminalNotifier().notifyUser("edit readme");

        assertEquals(List.of(TERMINAL_NOTIFIER, "osascript"), programsRun());
    }

    @Test
    void failsWhenNeitherCanShowTheNotification() {
        failing = Set.of(TERMINAL_NOTIFIER, "osascript");

        // The activity has to fail, so Temporal retries it.
        assertThrows(IllegalStateException.class, () -> withTerminalNotifier().notifyUser("edit readme"));
        assertEquals(List.of(TERMINAL_NOTIFIER, "osascript"), programsRun());
    }

    private ProjectTimerActivitiesImpl withTerminalNotifier() {
        return new ProjectTimerActivitiesImpl(Optional.of(TERMINAL_NOTIFIER), fakeRunner);
    }

    private ProjectTimerActivitiesImpl withoutTerminalNotifier() {
        return new ProjectTimerActivitiesImpl(Optional.empty(), fakeRunner);
    }

    private List<String> programsRun() {
        return ran.stream().map(List::getFirst).toList();
    }
}
