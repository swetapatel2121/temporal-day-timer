package daytimer.workflow;

import daytimer.model.Project;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;

import java.time.Duration;

public class ProjectTimerWorkflowImpl implements ProjectTimerWorkflow {
    private String name;
    private String date;
    private long endsAt;
    private long nudgeAfterMs;
    private long totalMs = 0;
    private Long runningSince = null;
    private Long nudgedAt = null;
    private CancellationScope nudge; // the "Still running?" nudge waiting to fire, while the project runs

    // A notification is nice to have, so it's tried 3 times rather than forever.
    private final ActivityOptions options =
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build();

    private final ProjectTimerActivities activities =
            Workflow.newActivityStub(ProjectTimerActivities.class, options);

    @WorkflowInit
    public ProjectTimerWorkflowImpl(ProjectTimerWorkflowInput input) {
        name = input.name();
        date = input.date();
        endsAt = input.endsAt();
        nudgeAfterMs = input.nudgeAfterMs();
    }

    @Override
    public ProjectTimerWorkflowOutput run(ProjectTimerWorkflowInput input) {
        Workflow.sleep(Duration.ofMillis(endsAt - Workflow.currentTimeMillis()));
        pauseProject(endsAt);
        return new ProjectTimerWorkflowOutput(getProject());
    }

    @Override
    public Project start(long at) {
        if (runningSince == null) {
            runningSince = at;
            // The nudge waits on its own workflow thread, so this update returns right away and run() keeps
            // waiting for midnight. Pausing cancels the scope, which cancels the wait inside it.
            // A nudgeAfterMs of 0 means no nudge, as for workflows started before nudges existed.
            if (nudgeAfterMs > 0) {
                nudge = Workflow.newCancellationScope(() -> Async.procedure(this::nudgeAt, at + nudgeAfterMs));
                nudge.run();
            }
        }
        return getProject();
    }

    @Override
    public Project pause(long at) {
        pauseProject(at);
        return getProject();
    }

    @Override
    public void otherProjectStarted(long at) {
        pauseProject(at);
    }

    @Override
    public Project getProject() {
        return new Project(name, date, endsAt, totalMs, runningSince, nudgedAt);
    }

    // Ends the running stretch. Time after endsAt never counts: the midnight timer can fire late (say the
    // computer was asleep), so a pause can arrive after midnight while the workflow is still running.
    private void pauseProject(long at) {
        if (runningSince != null) {
            totalMs += Math.min(at, endsAt) - runningSince;
            runningSince = null;
            if (nudge != null) {
                nudge.cancel();
            }
        }
    }

    // Waits until the nudge is due, then nudges. dueAt is passed in rather than worked out from runningSince
    // here, because this thread starts a moment after start() returns, and a pause may have run by then.
    // nudgedAt is set before the notification, so the nudge still counts if the notification fails.
    private void nudgeAt(long dueAt) {
        try {
            Workflow.sleep(Duration.ofMillis(dueAt - Workflow.currentTimeMillis()));
        } catch (CanceledFailure paused) {
            return; // the project was paused before the nudge was due
        }
        nudgedAt = Workflow.currentTimeMillis();
        try {
            activities.notifyUser(name);
        } catch (ActivityFailure notShown) {
            Workflow.getLogger(ProjectTimerWorkflowImpl.class).warn("Couldn't show the nudge for {}", name, notShown);
        }
    }
}
