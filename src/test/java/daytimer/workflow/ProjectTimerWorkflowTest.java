package daytimer.workflow;

import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class ProjectTimerWorkflowTest {

    @RegisterExtension
    public static final TestWorkflowExtension testWorkflowExtension =
            TestWorkflowExtension.newBuilder()
                    .setWorkflowTypes(ProjectTimerWorkflowImpl.class)
                    .setDoNotStart(true)
                    .build();

    @Test
    public void testSuccessfulWorkflowRun(TestWorkflowEnvironment testEnv, Worker worker,
                                          ProjectTimerWorkflow workflow) {

        testEnv.start();
        workflow.run(null);
    }
}
