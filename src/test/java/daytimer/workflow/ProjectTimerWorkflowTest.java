package daytimer.workflow;

import daytimer.model.Project;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WithStartWorkflowOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

// Each test gets a fresh time-skipping test environment, so waiting for midnight takes no real time.
// A workflow whose task keeps failing would make a test wait forever, so each test gives up after 30 seconds.
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class ProjectTimerWorkflowTest {

    private static final String NAME = "edit readme";
    private static final String DATE = "2026-11-09";
    private static final long MINUTE = 60_000;
    private static final long HOUR = 60 * MINUTE;
    private static final long NUDGE_AFTER = HOUR;

    // A mock instead of the real activities, so no notification pops up and the tests can check the calls.
    // Temporal needs it made without copying the interface's annotations.
    private static final ProjectTimerActivities activities =
            mock(ProjectTimerActivities.class, withSettings().withoutAnnotations());

    @RegisterExtension
    public static final TestWorkflowExtension testWorkflowExtension =
            TestWorkflowExtension.newBuilder()
                    .setWorkflowTypes(ProjectTimerWorkflowImpl.class)
                    .setActivityImplementations(activities)
                    .build();

    private long now;
    private long endsAt;

    @BeforeEach
    void setUp(TestWorkflowEnvironment testEnv) {
        now = testEnv.currentTimeMillis();
        endsAt = now + 10 * HOUR;
        reset(activities);
    }

    @Test
    public void dayWithNoWorkEndsWithNothingRecorded(ProjectTimerWorkflow workflow) {
        ProjectTimerWorkflowOutput output = workflow.run(new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        assertEquals(new Project(NAME, DATE, endsAt, 0, null, null), output.project());
    }

    @Test
    public void startThenPauseAddsTheStretch(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        Project started = workflow.start(now);
        assertEquals(new Project(NAME, DATE, endsAt, 0, now, null), started);

        Project paused = workflow.pause(now + 30 * MINUTE);
        assertEquals(new Project(NAME, DATE, endsAt, 30 * MINUTE, null, null), paused);

        assertEquals(paused, workflow.getProject());
    }

    @Test
    public void stretchesAddUp(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.start(now);
        workflow.pause(now + 30 * MINUTE);
        workflow.start(now + HOUR);
        Project paused = workflow.pause(now + HOUR + 15 * MINUTE);

        assertEquals(45 * MINUTE, paused.totalMs());
    }

    @Test
    public void startWhileRunningKeepsTheFirstStart(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.start(now);
        Project startedAgain = workflow.start(now + 10 * MINUTE);
        assertEquals(now, startedAgain.runningSince());

        assertEquals(30 * MINUTE, workflow.pause(now + 30 * MINUTE).totalMs());
    }

    @Test
    public void pauseWhilePausedChangesNothing(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        assertEquals(new Project(NAME, DATE, endsAt, 0, null, null), workflow.pause(now));
    }

    @Test
    public void otherProjectStartedStopsTheRunningStretch(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.start(now);
        workflow.otherProjectStarted(now + 20 * MINUTE);

        // A signal returns before the workflow handles it, so check the result at midnight.
        Project atMidnight = resultAtMidnight(workflow);
        assertEquals(20 * MINUTE, atMidnight.totalMs());
        assertNull(atMidnight.runningSince());
    }

    @Test
    public void otherProjectStartedWhilePausedIsIgnored(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.otherProjectStarted(now);

        assertEquals(new Project(NAME, DATE, endsAt, 0, null, null), resultAtMidnight(workflow));
    }

    @Test
    public void stillRunningAtMidnightCountsUpToEndsAt(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.start(now);

        Project atMidnight = resultAtMidnight(workflow);
        assertEquals(10 * HOUR, atMidnight.totalMs());
        assertNull(atMidnight.runningSince());
    }

    // ---- the "Still running?" nudge ----

    @Test
    public void runningForNudgeAfterNudgesOnce(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.start(now);

        Project atMidnight = resultAtMidnight(workflow);
        verify(activities, times(1)).notifyUser(NAME);
        assertAround(now + NUDGE_AFTER, atMidnight.nudgedAt());
    }

    @Test
    public void pausingBeforeTheNudgeCancelsIt(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.start(now);
        workflow.pause(now + 30 * MINUTE);

        Project atMidnight = resultAtMidnight(workflow);
        verify(activities, never()).notifyUser(any());
        assertNull(atMidnight.nudgedAt());
        assertEquals(30 * MINUTE, atMidnight.totalMs());
    }

    @Test
    public void otherProjectStartedCancelsTheNudge(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.start(now);
        workflow.otherProjectStarted(now + 30 * MINUTE);

        assertNull(resultAtMidnight(workflow).nudgedAt());
        verify(activities, never()).notifyUser(any());
    }

    @Test
    public void eachRunningStretchWaitsTheFullTimeAgain(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.start(now);
        workflow.pause(now + 30 * MINUTE);
        workflow.start(now + 40 * MINUTE);

        // Counted from the second start, not the first: the first nudge was cancelled by the pause.
        assertAround(now + 40 * MINUTE + NUDGE_AFTER, resultAtMidnight(workflow).nudgedAt());
        verify(activities, times(1)).notifyUser(NAME);
    }

    @Test
    public void aNudgeDueAfterMidnightNeverComes(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.start(endsAt - 30 * MINUTE);

        assertNull(resultAtMidnight(workflow).nudgedAt());
        verify(activities, never()).notifyUser(any());
    }

    @Test
    public void nudgeAfterOfZeroMeansNoNudge(ProjectTimerWorkflow workflow) {
        // Workflows started before nudges existed have no nudgeAfterMs in their input, so it reads as 0.
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, 0));

        workflow.start(now);

        assertNull(resultAtMidnight(workflow).nudgedAt());
        verify(activities, never()).notifyUser(any());
    }

    @Test
    public void aNotificationThatKeepsFailingDoesNotStopTheDay(ProjectTimerWorkflow workflow) {
        doThrow(new IllegalStateException("notifications are turned off")).when(activities).notifyUser(any());
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.start(now);

        Project atMidnight = resultAtMidnight(workflow);
        verify(activities, times(3)).notifyUser(NAME); // tried 3 times, then given up
        assertNotNull(atMidnight.nudgedAt());
        assertEquals(10 * HOUR, atMidnight.totalMs());
    }

    // In these two, the midnight timer hasn't fired yet when a pause arrives an hour after endsAt, like when
    // the computer was asleep at midnight. (In the test environment, time only moves while waiting on a result.)

    @Test
    public void lateOtherProjectStartedOnlyCountsUntilEndsAt(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.start(endsAt - 30 * MINUTE);
        workflow.otherProjectStarted(endsAt + HOUR);

        assertEquals(new Project(NAME, DATE, endsAt, 30 * MINUTE, null, null), resultAtMidnight(workflow));
    }

    @Test
    public void latePauseOnlyCountsUntilEndsAt(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        workflow.start(endsAt - 30 * MINUTE);

        assertEquals(new Project(NAME, DATE, endsAt, 30 * MINUTE, null, null), workflow.pause(endsAt + HOUR));
    }

    @Test
    public void updateSentWithTheStartSeesTheInput(TestWorkflowEnvironment testEnv, Worker worker) {
        // Update-with-start sends the workflow start and the update in one request, so the update
        // can run before run() does. @WorkflowInit sets name, date and endsAt before any handler runs.
        ProjectTimerWorkflow workflow = testEnv.getWorkflowClient().newWorkflowStub(ProjectTimerWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(worker.getTaskQueue())
                        .setWorkflowId("project-" + NAME + "-" + DATE)
                        .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                        .build());
        WithStartWorkflowOperation<ProjectTimerWorkflowOutput> startWorkflow =
                new WithStartWorkflowOperation<>(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt, NUDGE_AFTER));

        Project started = WorkflowClient.executeUpdateWithStart(workflow::start, now,
                UpdateOptions.<Project>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build(),
                startWorkflow);

        assertEquals(new Project(NAME, DATE, endsAt, 0, now, null), started);
    }

    // The nudge's time comes from the test environment's clock, which also moves with real time for the
    // moment the workflow takes to handle the timer, so it's checked to within a minute.
    private static void assertAround(long expected, Long actual) {
        assertNotNull(actual);
        assertTrue(expected <= actual && actual < expected + MINUTE, actual + " is not just after " + expected);
    }

    // Waiting for the result lets the test environment skip ahead to midnight.
    private static Project resultAtMidnight(ProjectTimerWorkflow workflow) {
        return WorkflowStub.fromTyped(workflow).getResult(ProjectTimerWorkflowOutput.class).project();
    }
}
