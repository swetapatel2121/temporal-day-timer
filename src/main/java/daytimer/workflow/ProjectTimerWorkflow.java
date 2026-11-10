package daytimer.workflow;

import daytimer.model.Project;
import io.temporal.workflow.*;

@WorkflowInterface
public interface ProjectTimerWorkflow {

    @WorkflowMethod
    ProjectTimerWorkflowOutput run(ProjectTimerWorkflowInput input);

    @UpdateMethod
    Project start(long at);

    @UpdateMethod
    Project pause(long at);

    @SignalMethod
    void otherProjectStarted(long at);

    @QueryMethod
    Project getProject();
}
