import { Timer } from "lucide-react";
import type {
    AthleteProfile,
    TrainingWorkoutResponse,
    WorkoutDefinition,
} from "@/types";
import { WorkoutStructureGraph } from "@/components/workout-structure-graph";
import {
    flattenWorkoutSteps,
    formatDistance,
    stepDuration,
    stepLabel,
    stepSummary,
    stepTarget,
} from "@/lib/workouts";
import {
    workoutProgressPercent,
    workoutRemainingSeconds,
} from "@/lib/workout-progress";

function activeStepTarget(
    workout: TrainingWorkoutResponse | null,
    steps: WorkoutDefinition["steps"],
    profile: AthleteProfile | null | undefined,
): string {
    if (workout === null) {
        return "Open target";
    }
    if (workout.completed) {
        return "Workout complete";
    }

    const step = steps[workout.currentStep - 1];
    const sourceTarget = step === undefined ? null : stepTarget(step, profile);
    if (sourceTarget !== null) {
        return sourceTarget;
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
    if (workout.completion.kind === "TIME") {
        const startedAt = Date.parse(workout.stepStartedAt);
        const total = Math.max(0, workout.completion.value ?? 0);
        const elapsed = Number.isFinite(startedAt)
            ? Math.max(0, Math.floor((now - startedAt) / 1000))
            : 0;
        const remaining = Math.max(0, total - elapsed);
        return `${formatClockDuration(remaining)} left`;
    }

    if (workout.completion.kind === "DISTANCE") {
        return `${formatDistance(workout.completion.value ?? 0) ?? "—"} total`;
    }

    return "Manual";
}

function formatClockDuration(totalSeconds: number): string {
    const remaining = Math.max(0, Math.floor(totalSeconds));
    const minutes = Math.floor(remaining / 60);
    const seconds = remaining % 60;
    return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

function workoutRemainingLabel(
    workout: TrainingWorkoutResponse | null,
    steps: WorkoutDefinition["steps"],
    now: number,
    paused: boolean,
): string | null {
    if (workout === null || workout.completed) {
        return null;
    }
    if (paused) {
        return "Paused";
    }

    const remaining = workoutRemainingSeconds(workout, steps, now);
    return remaining === null ? null : `${formatClockDuration(remaining)} left`;
}

function currentStepText(
    workout: TrainingWorkoutResponse | null,
    steps: WorkoutDefinition["steps"],
    currentIndex: number,
    profile: AthleteProfile | null | undefined,
): string | null {
    const liveText = workout?.stepText?.trim();
    if (liveText) {
        return liveText;
    }

    const step = steps[currentIndex];
    return step === undefined
        ? null
        : (stepLabel(step) ?? stepSummary(step, profile));
}

function nextStepText(
    steps: WorkoutDefinition["steps"],
    currentIndex: number,
    profile: AthleteProfile | null | undefined,
): string | null {
    const step = steps[currentIndex + 1];
    return step === undefined
        ? null
        : (stepLabel(step) ?? stepSummary(step, profile));
}

export function WorkoutProgressTile({
    definition,
    workout,
    now = 0,
    athleteProfile = null,
    paused = false,
}: {
    definition: WorkoutDefinition | null;
    workout: TrainingWorkoutResponse | null;
    now?: number;
    athleteProfile?: AthleteProfile | null;
    paused?: boolean;
}) {
    const steps =
        definition === null ? [] : flattenWorkoutSteps(definition.steps);
    const currentStep = workout?.currentStep ?? 0;
    const currentIndex = Math.max(
        0,
        Math.min(steps.length - 1, currentStep - 1),
    );
    const stepWeights = steps.map((step) => stepDuration(step) ?? 1);
    const progressPercent = workoutProgressPercent(workout, stepWeights, now);
    const activeText = currentStepText(
        workout,
        steps,
        currentIndex,
        athleteProfile,
    );
    const upcomingText = nextStepText(steps, currentIndex, athleteProfile);
    const hasUpcomingStep =
        upcomingText !== null && workout !== null && !workout.completed;
    const totalRemainingText = workoutRemainingLabel(
        workout,
        steps,
        now,
        paused,
    );

    return (
        <section
            aria-labelledby="active-workout-progress-title"
            className="rounded-[2rem] border border-white/[0.1] bg-[#141821] p-5 sm:p-6"
        >
            <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                    <p className="text-[0.6rem] font-bold tracking-[0.2em] text-white/30 uppercase">
                        Workout structure
                    </p>
                    <h2
                        id="active-workout-progress-title"
                        className="mt-2 truncate text-xl font-black tracking-[-0.06em] text-white sm:text-2xl"
                    >
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
                <div className="mt-5">
                    <WorkoutStructureGraph
                        steps={steps}
                        athleteProfile={athleteProfile}
                        progressPercent={progressPercent}
                        ariaLabel="Workout structure and progress"
                    />

                    <div className="mt-4 flex items-start justify-between gap-3">
                        <div className="min-w-0">
                            <p className="truncate text-sm font-bold text-white/90">
                                {paused
                                    ? "Workout paused"
                                    : workout?.completed
                                      ? "Workout complete"
                                      : (activeText ??
                                        activeStepTarget(
                                            workout,
                                            steps,
                                            athleteProfile,
                                        ))}
                            </p>
                            {!paused &&
                            workout !== null &&
                            !workout.completed ? (
                                <p className="mt-1 truncate text-xs font-semibold text-white/60">
                                    {activeStepTarget(
                                        workout,
                                        steps,
                                        athleteProfile,
                                    )}
                                </p>
                            ) : null}
                        </div>
                        {workout?.completed && !paused ? null : (
                            <div className="shrink-0 text-right">
                                <span className="block pt-0.5 text-base leading-none font-black text-white/90 sm:text-lg">
                                    {paused
                                        ? "Paused"
                                        : remainingLabel(workout, now)}
                                </span>
                            </div>
                        )}
                    </div>
                    {hasUpcomingStep || totalRemainingText !== null ? (
                        <div className="mt-3 flex items-center gap-3">
                            {hasUpcomingStep ? (
                                <p className="min-w-0 flex-1 truncate text-xs font-semibold text-white/60">
                                    <span className="mr-2 text-[0.6rem] font-bold tracking-[0.16em] text-white/35 uppercase">
                                        Next
                                    </span>
                                    {upcomingText}
                                </p>
                            ) : (
                                <span aria-hidden="true" className="flex-1" />
                            )}
                            {totalRemainingText !== null ? (
                                <p className="shrink-0 text-right text-xs font-semibold text-white/55">
                                    <span className="text-white/35">
                                        Workout
                                    </span>{" "}
                                    {totalRemainingText}
                                </p>
                            ) : null}
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
                                        : (workout.stepText ??
                                          activeStepTarget(
                                              workout,
                                              steps,
                                              athleteProfile,
                                          ))}
                            </p>
                        </div>
                        {workout !== null && (!workout.completed || paused) ? (
                            <span className="shrink-0 text-xs font-bold text-white/85">
                                {paused
                                    ? "Paused"
                                    : remainingLabel(workout, now)}
                            </span>
                        ) : null}
                    </div>
                </div>
            )}
        </section>
    );
}
