package daytimer.workflow;

public record ProjectTimerWorkflowInput (
    String name,
    String date,
    long endsAt,
    long nudgeAfterMs
) {}
