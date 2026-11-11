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
import static org.junit.jupiter.api.Assertions.assertNull;

// Each test gets a fresh time-skipping test environment, so waiting for midnight takes no real time.
// A workflow whose task keeps failing would make a test wait forever, so each test gives up after 30 seconds.
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class ProjectTimerWorkflowTest {

    private static final String NAME = "edit readme";
    private static final String DATE = "2026-11-09";
    private static final long MINUTE = 60_000;
    private static final long HOUR = 60 * MINUTE;

    @RegisterExtension
    public static final TestWorkflowExtension testWorkflowExtension =
            TestWorkflowExtension.newBuilder()
                    .setWorkflowTypes(ProjectTimerWorkflowImpl.class)
                    .build();

    private long now;
    private long endsAt;

    @BeforeEach
    void setUp(TestWorkflowEnvironment testEnv) {
        now = testEnv.currentTimeMillis();
        endsAt = now + 10 * HOUR;
    }

    @Test
    public void dayWithNoWorkEndsWithNothingRecorded(ProjectTimerWorkflow workflow) {
        ProjectTimerWorkflowOutput output = workflow.run(new ProjectTimerWorkflowInput(NAME, DATE, endsAt));

        assertEquals(new Project(NAME, DATE, endsAt, 0, null, null), output.project());
    }

    @Test
    public void startThenPauseAddsTheStretch(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt));

        Project started = workflow.start(now);
        assertEquals(new Project(NAME, DATE, endsAt, 0, now, null), started);

        Project paused = workflow.pause(now + 30 * MINUTE);
        assertEquals(new Project(NAME, DATE, endsAt, 30 * MINUTE, null, null), paused);

        assertEquals(paused, workflow.getProject());
    }

    @Test
    public void stretchesAddUp(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt));

        workflow.start(now);
        workflow.pause(now + 30 * MINUTE);
        workflow.start(now + HOUR);
        Project paused = workflow.pause(now + HOUR + 15 * MINUTE);

        assertEquals(45 * MINUTE, paused.totalMs());
    }

    @Test
    public void startWhileRunningKeepsTheFirstStart(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt));

        workflow.start(now);
        Project startedAgain = workflow.start(now + 10 * MINUTE);
        assertEquals(now, startedAgain.runningSince());

        assertEquals(30 * MINUTE, workflow.pause(now + 30 * MINUTE).totalMs());
    }

    @Test
    public void pauseWhilePausedChangesNothing(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt));

        assertEquals(new Project(NAME, DATE, endsAt, 0, null, null), workflow.pause(now));
    }

    @Test
    public void otherProjectStartedStopsTheRunningStretch(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt));

        workflow.start(now);
        workflow.otherProjectStarted(now + 20 * MINUTE);

        // A signal returns before the workflow handles it, so check the result at midnight.
        Project atMidnight = resultAtMidnight(workflow);
        assertEquals(20 * MINUTE, atMidnight.totalMs());
        assertNull(atMidnight.runningSince());
    }

    @Test
    public void otherProjectStartedWhilePausedIsIgnored(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt));

        workflow.otherProjectStarted(now);

        assertEquals(new Project(NAME, DATE, endsAt, 0, null, null), resultAtMidnight(workflow));
    }

    @Test
    public void stillRunningAtMidnightCountsUpToEndsAt(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt));

        workflow.start(now);

        assertEquals(new Project(NAME, DATE, endsAt, 10 * HOUR, null, null), resultAtMidnight(workflow));
    }

    // In these two, the midnight timer hasn't fired yet when a pause arrives an hour after endsAt, like when
    // the computer was asleep at midnight. (In the test environment, time only moves while waiting on a result.)

    @Test
    public void lateOtherProjectStartedOnlyCountsUntilEndsAt(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt));

        workflow.start(endsAt - 30 * MINUTE);
        workflow.otherProjectStarted(endsAt + HOUR);

        assertEquals(new Project(NAME, DATE, endsAt, 30 * MINUTE, null, null), resultAtMidnight(workflow));
    }

    @Test
    public void latePauseOnlyCountsUntilEndsAt(ProjectTimerWorkflow workflow) {
        WorkflowClient.start(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt));

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
                new WithStartWorkflowOperation<>(workflow::run, new ProjectTimerWorkflowInput(NAME, DATE, endsAt));

        Project started = WorkflowClient.executeUpdateWithStart(workflow::start, now,
                UpdateOptions.<Project>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build(),
                startWorkflow);

        assertEquals(new Project(NAME, DATE, endsAt, 0, now, null), started);
    }

    // Waiting for the result lets the test environment skip ahead to midnight.
    private static Project resultAtMidnight(ProjectTimerWorkflow workflow) {
        return WorkflowStub.fromTyped(workflow).getResult(ProjectTimerWorkflowOutput.class).project();
    }
}
