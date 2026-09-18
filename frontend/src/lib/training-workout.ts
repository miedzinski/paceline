import type {
    TrainingWorkoutResponse,
    TrainingWorkoutStepResponse,
    WorkoutDefinition,
    WorkoutTarget,
    WorkoutStep,
} from "../types";

function targetFromSessionStep(
    target: TrainingWorkoutStepResponse["target"],
): WorkoutTarget | null {
    if (target.kind === "OPEN") {
        return null;
    }

    if (target.kind === "RAMP") {
        return {
            value: null,
            start: target.startWatts,
            end: target.endWatts,
            units: "watts",
            target: null,
        };
    }

    const low = target.lowWatts;
    const high = target.highWatts;
    if (low === null && high === null) {
        return null;
    }

    return {
        value: low !== null && low === high ? low : null,
        start: low,
        end: high,
        units: "watts",
        target: null,
    };
}

function stepFromSessionStep(step: TrainingWorkoutStepResponse): WorkoutStep {
    const target = targetFromSessionStep(step.target);
    const intensity = step.intensity?.trim() || null;

    return {
        text: step.text,
        durationSeconds:
            step.completion.kind === "TIME" ? step.completion.value : null,
        distanceMeters:
            step.completion.kind === "DISTANCE" ? step.completion.value : null,
        repeats: null,
        warmup: intensity?.toLowerCase() === "warmup",
        cooldown: intensity?.toLowerCase() === "cooldown",
        intensity,
        ramp: step.target.kind === "RAMP",
        freeRide: step.target.kind === "OPEN",
        power: target,
        resolvedPower: target,
        heartRate: null,
        pace: null,
        cadence: null,
        steps: [],
    };
}

export function workoutDefinitionFromSession(
    workout: TrainingWorkoutResponse | null,
): WorkoutDefinition | null {
    const steps = workout?.steps ?? [];
    if (steps.length === 0) {
        return null;
    }

    return {
        description: null,
        durationSeconds: null,
        distanceMeters: null,
        ftpWatts: null,
        thresholdHeartRateBpm: null,
        target: null,
        steps: steps.map(stepFromSessionStep),
    };
}
