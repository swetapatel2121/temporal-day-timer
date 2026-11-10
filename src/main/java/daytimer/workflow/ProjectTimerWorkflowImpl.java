package daytimer.workflow;

import daytimer.model.Project;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;

import java.time.Duration;

public class ProjectTimerWorkflowImpl implements ProjectTimerWorkflow {
    private String name;
    private String date;
    private long endsAt;
    private long totalMs = 0;
    private Long runningSince = null;
    private Long nudgedAt = null;

    @WorkflowInit
    public ProjectTimerWorkflowImpl(ProjectTimerWorkflowInput input) {
        name = input.name();
        date = input.date();
        endsAt = input.endsAt();
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

    private void pauseProject(long at) {
        if (runningSince != null) {
            totalMs += at - runningSince;
            runningSince = null;
        }
    }
}
