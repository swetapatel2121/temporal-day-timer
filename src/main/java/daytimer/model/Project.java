package daytimer.model;

public record Project (
    String name,
    String date,
    long endsAt,
    long totalMs,
    Long runningSince,
    Long nudgedAt
) {}
