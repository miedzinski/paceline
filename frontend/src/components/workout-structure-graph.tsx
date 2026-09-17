import type { AthleteProfile, WorkoutStep } from "@/types";
import {
    stepDuration,
    stepLabel,
    stepSummary,
    workoutStepBackground,
    workoutIntensityScalePercent,
    workoutStepColor,
    workoutStepFreeRidePath,
    workoutStepHeight,
    workoutStepRampPath,
} from "@/lib/workouts";

export function WorkoutStructureGraph({
    steps,
    athleteProfile = null,
    progressPercent = null,
    ariaLabel = "Workout structure preview",
}: {
    steps: WorkoutStep[];
    athleteProfile?: AthleteProfile | null;
    progressPercent?: number | null;
    ariaLabel?: string;
}) {
    const weights = steps.map((step) => stepDuration(step) ?? 1);
    const totalWeight = weights.reduce((sum, weight) => sum + weight, 0);
    const intensityScalePercent = workoutIntensityScalePercent(
        steps,
        athleteProfile,
    );
    const markerPercent = Math.min(100, Math.max(0, progressPercent ?? 0));

    return (
        <div
            className="relative flex h-20 w-full items-end overflow-hidden rounded-2xl border border-white/[0.08] bg-[#0d1017] p-2"
            aria-label={ariaLabel}
        >
            <div className="relative h-full w-full">
                <div className="flex h-full items-end gap-px">
                    {steps.map((step, index) => {
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
                                        : (label ?? summary ?? undefined)
                                }
                                className="block min-w-0 rounded-[0.35rem] opacity-95 transition-opacity hover:opacity-70"
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
                                        <path d={shapePath} fill={stepColor} />
                                    </svg>
                                ) : null}
                            </span>
                        );
                    })}
                </div>

                {progressPercent !== null ? (
                    <div
                        className="pointer-events-none absolute inset-0"
                        aria-hidden="true"
                    >
                        <span
                            className="absolute inset-y-0 left-0 bg-[#090c12]/[0.48]"
                            style={{ width: `${markerPercent}%` }}
                        />
                        <span
                            className="absolute inset-y-[-0.25rem] w-0.5 -translate-x-1/2 rounded-full bg-white shadow-[0_0_0_1px_rgba(9,12,18,0.7),0_0_10px_rgba(255,255,255,0.65)]"
                            style={{ left: `${markerPercent}%` }}
                        />
                    </div>
                ) : null}
            </div>
        </div>
    );
}
