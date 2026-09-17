interface WorkoutProgressSource {
    completed: boolean;
    completion: {
        kind: "TIME" | "DISTANCE" | "MANUAL";
        value: number | null;
    };
    stepStartedAt: string;
}

interface WorkoutProgressPositionSource extends WorkoutProgressSource {
    currentStep: number;
}

interface WorkoutTimedStepSource {
    durationSeconds: number | null;
    distanceMeters: number | null;
}

export function workoutStepProgressPercent(
    workout: WorkoutProgressSource | null,
    now: number,
): number | null {
    if (
        workout === null ||
        workout.completed ||
        workout.completion.kind !== "TIME"
    ) {
        return null;
    }

    const startedAt = Date.parse(workout.stepStartedAt);
    const totalSeconds = workout.completion.value ?? 0;
    if (
        !Number.isFinite(startedAt) ||
        !Number.isFinite(totalSeconds) ||
        totalSeconds <= 0
    ) {
        return null;
    }

    const elapsedSeconds = Number.isFinite(now)
        ? Math.max(0, Math.min(totalSeconds, (now - startedAt) / 1000))
        : 0;
    return Math.round((elapsedSeconds / totalSeconds) * 100);
}

export function workoutProgressPercent(
    workout: WorkoutProgressPositionSource | null,
    stepWeights: number[],
    now: number,
): number | null {
    if (workout === null || stepWeights.length === 0) {
        return null;
    }
    if (workout.completed) {
        return 100;
    }

    const weights = stepWeights.map((weight) => Math.max(1, weight));
    const totalWeight = weights.reduce((total, weight) => total + weight, 0);
    const currentIndex = Math.max(
        0,
        Math.min(weights.length - 1, workout.currentStep - 1),
    );
    const completedWeight = weights
        .slice(0, currentIndex)
        .reduce((total, weight) => total + weight, 0);
    const currentStepProgress = workoutStepProgressPercent(workout, now) ?? 0;
    const progressWeight =
        completedWeight + weights[currentIndex] * (currentStepProgress / 100);

    return Math.min(100, (progressWeight / totalWeight) * 100);
}

export function workoutRemainingSeconds(
    workout: WorkoutProgressPositionSource | null,
    steps: WorkoutTimedStepSource[],
    now: number,
): number | null {
    if (workout === null || workout.completed || steps.length === 0) {
        return null;
    }

    const durations = steps.map((step) => {
        const duration = step.durationSeconds;
        return step.distanceMeters === null &&
            duration !== null &&
            Number.isFinite(duration) &&
            duration > 0
            ? duration
            : null;
    });
    if (durations.some((duration) => duration === null)) {
        return null;
    }

    const timedDurations = durations as number[];
    const currentIndex = Math.max(
        0,
        Math.min(timedDurations.length - 1, workout.currentStep - 1),
    );
    const startedAt = Date.parse(workout.stepStartedAt);
    if (!Number.isFinite(startedAt)) {
        return null;
    }

    const elapsedSeconds = Number.isFinite(now)
        ? Math.max(
              0,
              Math.min(timedDurations[currentIndex], (now - startedAt) / 1000),
          )
        : 0;
    const completedSeconds = timedDurations
        .slice(0, currentIndex)
        .reduce((total, duration) => total + duration, 0);
    const totalSeconds = timedDurations.reduce(
        (total, duration) => total + duration,
        0,
    );

    return Math.max(
        0,
        totalSeconds - completedSeconds - Math.floor(elapsedSeconds),
    );
}
