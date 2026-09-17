import { ArrowRight, Check, Flag, Pause, Timer, Zap } from "lucide-react";
import type {
    AthleteProfile,
    TrainingWorkoutResponse,
    WorkoutDefinition,
    WorkoutStep,
} from "@/types";
import {
    formatDistance,
    flattenWorkoutSteps,
    stepLabel,
    stepSummary,
    workoutStepBackground,
    workoutIntensityScalePercent,
    workoutStepColor,
    workoutStepFreeRidePath,
    workoutStepHeight,
    workoutStepRampPath,
} from "@/lib/workouts";
import { cn } from "@/lib/utils";

function stepWeight(step: WorkoutStep): number {
    return step.durationSeconds ?? step.distanceMeters ?? 1;
}

function activeStepTarget(workout: TrainingWorkoutResponse | null): string {
    if (workout === null) {
        return "Open target";
    }
    if (workout.completed) {
        return "Workout complete";
    }
    if (workout.target.kind === "OPEN") {
        return "Open target";
    }

    if (workout.target.kind === "RAMP") {
        const start = workout.target.startWatts;
        const end = workout.target.endWatts;
        if (start === null || end === null) {
            return "Ramp target unavailable";
        }
        return `${start}→${end} W ramp`;
    }

    const low = workout.target.lowWatts;
    const high = workout.target.highWatts;
    if (low === null && high === null) {
        return "Target unavailable";
    }
    if (low === high || high === null) {
        return `${low ?? high} W`;
    }
    if (low === null) {
        return `${high} W`;
    }
    return `${low}–${high} W · ${Math.round((low + high) / 2)} W ERG`;
}

function remainingLabel(
    workout: TrainingWorkoutResponse | null,
    now: number,
): string {
    if (workout === null) {
        return "Open target";
    }
    if (workout.completed) {
        return "Complete";
    }

    if (workout.completion.kind === "TIME") {
        const startedAt = Date.parse(workout.stepStartedAt);
        const total = Math.max(0, workout.completion.value ?? 0);
        const elapsed = Number.isFinite(startedAt)
            ? Math.max(0, Math.floor((now - startedAt) / 1000))
            : 0;
        const remaining = Math.max(0, total - elapsed);
        const minutes = Math.floor(remaining / 60);
        const seconds = remaining % 60;
        return `${minutes}:${String(seconds).padStart(2, "0")} left`;
    }

    if (workout.completion.kind === "DISTANCE") {
        return `${formatDistance(workout.completion.value ?? 0) ?? "—"} total`;
    }

    return "Manual";
}

export function WorkoutTimeline({
    definition,
    workout,
    compact = false,
    now = 0,
    athleteProfile = null,
    paused = false,
}: {
    definition: WorkoutDefinition | null;
    workout: TrainingWorkoutResponse | null;
    compact?: boolean;
    now?: number;
    athleteProfile?: AthleteProfile | null;
    paused?: boolean;
}) {
    const steps =
        definition === null ? [] : flattenWorkoutSteps(definition.steps);
    const weights = steps.map(stepWeight);
    const totalWeight = weights.reduce((sum, weight) => sum + weight, 0);
    const intensityScalePercent = workoutIntensityScalePercent(
        steps,
        athleteProfile,
    );
    const currentStep = workout?.currentStep ?? 0;
    const currentIndex = Math.max(
        0,
        Math.min(steps.length - 1, currentStep - 1),
    );

    return (
        <section
            className={cn(
                "rounded-[2rem] border border-white/[0.1] bg-[#141821]",
                compact ? "p-4" : "p-5 sm:p-6",
            )}
        >
            <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                    <p className="text-[0.6rem] font-bold tracking-[0.2em] text-white/30 uppercase">
                        Workout structure
                    </p>
                    <h2 className="mt-2 truncate text-xl font-black tracking-[-0.06em] text-white sm:text-2xl">
                        {workout?.name ?? "Free ride"}
                    </h2>
                </div>
                {workout ? (
                    <span className="shrink-0 rounded-xl bg-white/[0.06] px-3 py-1.5 text-[0.65rem] font-bold text-white/55">
                        {paused
                            ? "Paused"
                            : workout.completed
                              ? "Done"
                              : `${workout.currentStep} / ${workout.totalSteps}`}
                    </span>
                ) : null}
            </div>

            {steps.length > 0 ? (
                <div className="mt-6">
                    <div className="flex h-24 items-end gap-px overflow-hidden rounded-2xl border border-white/[0.08] bg-[#0b0e14] p-2 sm:h-28">
                        {steps.map((step, index) => {
                            const isCurrent =
                                index === currentIndex && !workout?.completed;
                            const isDone =
                                index < currentIndex ||
                                workout?.completed === true;
                            const rampPath = workoutStepRampPath(
                                step,
                                athleteProfile,
                            );
                            const freeRidePath = workoutStepFreeRidePath(step);
                            const shapePath = rampPath ?? freeRidePath;
                            const stepColor = workoutStepColor(
                                step,
                                index,
                                athleteProfile,
                            );
                            const label = stepLabel(step);
                            const summary = stepSummary(step);
                            return (
                                <span
                                    key={`${label ?? "step"}-${index}`}
                                    title={
                                        label && summary
                                            ? `${label} · ${summary}`
                                            : (label ??
                                              summary ??
                                              `Step ${index + 1}`)
                                    }
                                    className={cn(
                                        "block min-w-0 rounded-[0.35rem] transition-all",
                                        isCurrent && shapePath === null
                                            ? "ring-2 ring-white ring-offset-2 ring-offset-[#0b0e14]"
                                            : null,
                                        isDone ? "opacity-25" : "opacity-95",
                                    )}
                                    style={{
                                        width: `${(weights[index] / Math.max(1, totalWeight)) * 100}%`,
                                        height: `${workoutStepHeight(step, athleteProfile, intensityScalePercent)}%`,
                                        background:
                                            shapePath === null
                                                ? (workoutStepBackground(
                                                      step,
                                                      athleteProfile,
                                                      stepColor,
                                                  ) ?? stepColor)
                                                : "transparent",
                                    }}
                                >
                                    {shapePath !== null ? (
                                        <svg
                                            aria-hidden="true"
                                            className="block size-full"
                                            preserveAspectRatio="none"
                                            viewBox="0 0 100 100"
                                        >
                                            <path
                                                d={shapePath}
                                                fill={stepColor}
                                                stroke={
                                                    isCurrent
                                                        ? "white"
                                                        : undefined
                                                }
                                                strokeLinejoin="round"
                                                strokeWidth={
                                                    isCurrent ? 2 : undefined
                                                }
                                                vectorEffect="non-scaling-stroke"
                                            />
                                        </svg>
                                    ) : null}
                                </span>
                            );
                        })}
                    </div>

                    <div className="mt-5 flex items-start gap-3">
                        <span className="grid size-10 shrink-0 place-items-center rounded-2xl bg-[#7e87ff] text-[#0b0d14]">
                            {paused ? (
                                <Pause aria-hidden="true" className="size-4" />
                            ) : workout?.completed ? (
                                <Check aria-hidden="true" className="size-4" />
                            ) : (
                                <Zap aria-hidden="true" className="size-4" />
                            )}
                        </span>
                        <div className="min-w-0 flex-1">
                            <p className="truncate text-base font-black tracking-[-0.03em] text-white">
                                {paused
                                    ? "Workout paused"
                                    : workout?.completed
                                      ? "Workout complete"
                                      : (workout?.stepText ??
                                        stepLabel(steps[currentIndex]) ??
                                        stepSummary(steps[currentIndex]) ??
                                        `Step ${currentIndex + 1}`)}
                            </p>
                        </div>
                        <span className="shrink-0 text-xs font-bold text-[#aeb4ff]">
                            {paused ? "Paused" : remainingLabel(workout, now)}
                        </span>
                    </div>

                    {!workout?.completed && currentStep < steps.length ? (
                        <div className="mt-4 flex items-center gap-2 rounded-2xl bg-white/[0.04] px-3.5 py-3 text-xs font-semibold text-white/45">
                            <Flag
                                aria-hidden="true"
                                className="size-3.5 text-white/25"
                            />
                            <span className="truncate">
                                Next ·{" "}
                                {stepLabel(steps[currentStep]) ??
                                    stepSummary(steps[currentStep]) ??
                                    `Step ${currentStep + 1}`}
                            </span>
                        </div>
                    ) : null}
                </div>
            ) : (
                <div className="mt-6 rounded-2xl border border-dashed border-white/[0.14] bg-white/[0.025] p-5">
                    <div className="flex items-start gap-3">
                        <span className="grid size-10 shrink-0 place-items-center rounded-2xl bg-white/[0.07] text-white/45">
                            <Timer aria-hidden="true" className="size-4" />
                        </span>
                        <div>
                            <p className="text-sm font-bold text-white/85">
                                {paused
                                    ? "Workout paused"
                                    : workout === null
                                      ? "Manual ERG ride"
                                      : workout.completed
                                        ? "Workout complete"
                                        : "Workout in progress"}
                            </p>
                            <p className="mt-1 text-xs leading-5 text-white/40">
                                {paused
                                    ? "Resume when you are ready to continue."
                                    : workout === null
                                      ? "Set a target after the session starts."
                                      : workout.completed
                                        ? "Continue riding manually."
                                        : `${workout.stepText ?? `Step ${workout.currentStep} of ${workout.totalSteps}`} · ${activeStepTarget(workout)}`}
                            </p>
                        </div>
                        {workout !== null ? (
                            <span className="shrink-0 text-xs font-bold text-[#aeb4ff]">
                                {paused
                                    ? "Paused"
                                    : remainingLabel(workout, now)}
                            </span>
                        ) : null}
                    </div>
                </div>
            )}

            {workout?.completed ? (
                <div className="mt-4 flex items-center gap-2 text-xs font-semibold text-[#73d6a1]">
                    <ArrowRight aria-hidden="true" className="size-3.5" />
                    Continue manually after the planned steps.
                </div>
            ) : null}
        </section>
    );
}
