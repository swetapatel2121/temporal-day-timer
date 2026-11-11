package daytimer.workflow;

import io.temporal.activity.ActivityInterface;

// Side effects for the project timer workflow. Workflow code can't do I/O, so it goes here.
@ActivityInterface
public interface ProjectTimerActivities {
    // Shows a "Still running?" notification for the project.
    void notifyUser(String name);
}
