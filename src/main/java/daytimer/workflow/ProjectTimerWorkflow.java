package daytimer.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ProjectTimerWorkflow {

    @WorkflowMethod
    ProjectTimerWorkflowOutput run(ProjectTimerWorkflowInput input);
}
