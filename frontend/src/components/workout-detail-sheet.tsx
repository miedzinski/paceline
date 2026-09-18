import { Dumbbell, Play, X, Zap } from "lucide-react";
import { useEffect } from "react";
import { WorkoutProfile } from "@/components/workout-card";
import { WorkoutOverview } from "@/components/workout-overview";
import {
    stepLabel,
    stepSummary,
    workoutName,
    workoutSelection,
    type WorkoutItem,
} from "@/lib/workouts";
import type { AthleteProfile, WorkoutStep } from "@/types";
import { workoutStepColor } from "@/lib/workouts";
import { cn } from "@/lib/utils";

function sourceLabel(workout: WorkoutItem): string {
    return "sourceEventId" in workout
        ? "Scheduled from today"
        : "Saved in your library";
}

export function WorkoutDetailSheet({
    workout,
    onClose,
    onStart,
    athleteProfile = null,
}: {
    workout: WorkoutItem | null;
    onClose: () => void;
    onStart: (selection: ReturnType<typeof workoutSelection>) => void;
    athleteProfile?: AthleteProfile | null;
}) {
    useEffect(() => {
        if (workout === null) {
            return;
        }

        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === "Escape") {
                onClose();
            }
        };
        const previousOverflow = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        window.addEventListener("keydown", handleKeyDown);

        return () => {
            document.body.style.overflow = previousOverflow;
            window.removeEventListener("keydown", handleKeyDown);
        };
    }, [onClose, workout]);

    if (workout === null) {
        return null;
    }

    const steps = workout.workout?.steps ?? [];

    return (
        <div
            className="fixed inset-0 z-[90] flex items-center justify-center bg-[#05070c]/75 p-4 backdrop-blur-sm sm:p-6"
            role="presentation"
            onMouseDown={(event) => {
                if (event.target === event.currentTarget) {
                    onClose();
                }
            }}
        >
            <section
                role="dialog"
                aria-modal="true"
                aria-labelledby="workout-detail-title"
                className="relative flex max-h-[calc(100dvh-2rem)] w-full max-w-[42rem] flex-col overflow-hidden rounded-[2rem] border border-white/[0.1] bg-[#11151e] text-white shadow-[0_28px_80px_rgba(0,0,0,0.45)] sm:max-h-[calc(100dvh-3rem)]"
                onMouseDown={(event) => event.stopPropagation()}
            >
                <div className="flex items-center justify-between border-b border-white/[0.08] px-5 py-4 sm:px-7 sm:py-5">
                    <div>
                        <p className="text-[0.6rem] font-bold tracking-[0.2em] text-[#8f98ad] uppercase">
                            {sourceLabel(workout)}
                        </p>
                        <p className="mt-1 text-xs font-semibold text-white/35">
                            Workout details
                        </p>
                    </div>
                    <button
                        type="button"
                        onClick={onClose}
                        aria-label="Close workout details"
                        className="grid size-10 place-items-center rounded-2xl text-white/40 transition-colors hover:bg-white/[0.08] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
                    >
                        <X aria-hidden="true" className="size-5" />
                    </button>
                </div>

                <div className="overflow-y-auto px-5 py-6 sm:px-7 sm:py-8">
                    <h2
                        id="workout-detail-title"
                        className="max-w-xl text-3xl leading-[0.98] font-black tracking-[-0.08em] text-white sm:text-5xl"
                    >
                        {workoutName(workout)}
                    </h2>
                    <div className="mt-7">
                        <WorkoutProfile
                            workout={workout}
                            athleteProfile={athleteProfile}
                        />
                    </div>

                    <WorkoutOverview workout={workout} />

                    <button
                        type="button"
                        onClick={() => onStart(workoutSelection(workout))}
                        className="mt-7 inline-flex min-h-14 w-full items-center justify-center gap-2 rounded-2xl bg-[#7e87ff] px-5 text-sm font-black text-[#0b0d14] shadow-[0_15px_35px_rgba(126,135,255,0.18)] transition hover:bg-[#aeb4ff] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
                    >
                        <Play
                            aria-hidden="true"
                            className="size-4 fill-current"
                        />
                        Start this workout
                    </button>

                    <div className="mt-9 flex items-end justify-between gap-3">
                        <div>
                            <h3 className="text-xl font-black tracking-[-0.06em] text-white">
                                Structure
                            </h3>
                        </div>
                        <Dumbbell
                            aria-hidden="true"
                            className="size-5 text-white/25"
                        />
                    </div>

                    {steps.length > 0 ? (
                        <ol className="mt-4 overflow-hidden rounded-2xl border border-white/[0.08] bg-[#0d1017]">
                            {steps.map((step, index) => (
                                <WorkoutDetailStep
                                    key={`${stepLabel(step) ?? "step"}-${index}`}
                                    step={step}
                                    index={index}
                                    athleteProfile={athleteProfile}
                                />
                            ))}
                        </ol>
                    ) : (
                        <div className="mt-4 rounded-2xl border border-dashed border-white/[0.14] bg-white/[0.025] p-5 text-sm leading-6 text-white/45">
                            The provider did not include interval steps for this
                            workout.
                        </div>
                    )}
                </div>
            </section>
        </div>
    );
}

function WorkoutDetailStep({
    step,
    index,
    athleteProfile,
    nested = false,
}: {
    step: WorkoutStep;
    index: number;
    athleteProfile: AthleteProfile | null;
    nested?: boolean;
}) {
    const hasChildren = step.steps.length > 0;
    const repetitions = Math.max(1, step.repeats ?? 1);
    const customLabel = stepLabel(step);
    const label = hasChildren
        ? (customLabel ?? `Repeat ${repetitions}×`)
        : customLabel;
    const summary = hasChildren
        ? customLabel === null
            ? null
            : `${repetitions} ${repetitions === 1 ? "repeat" : "repeats"}`
        : stepSummary(step, athleteProfile);

    return (
        <li
            className={cn(
                "border-b border-white/[0.07] last:border-b-0",
                hasChildren && "bg-white/[0.018]",
            )}
        >
            <div
                className={cn(
                    "flex items-start gap-3",
                    nested ? "px-3 py-3" : "px-3.5 py-3.5",
                )}
            >
                {hasChildren ? (
                    <span className="mt-0.5 grid min-w-9 shrink-0 place-items-center rounded-lg bg-[#7e87ff]/15 px-1.5 py-1 text-[0.7rem] font-black text-[#aeb4ff]">
                        ×{repetitions}
                    </span>
                ) : (
                    <span
                        className="mt-0.5 size-2.5 shrink-0 rounded-full"
                        style={{
                            backgroundColor: workoutStepColor(
                                step,
                                index,
                                athleteProfile,
                            ),
                        }}
                    />
                )}
                <div className="min-w-0 flex-1">
                    {label ? (
                        <p className="truncate text-sm font-bold text-white/85">
                            {label}
                        </p>
                    ) : null}
                    {summary ? (
                        <p
                            className={cn(
                                "text-xs font-semibold text-white/38",
                                label
                                    ? "mt-1"
                                    : "text-sm font-bold text-white/85",
                            )}
                        >
                            {summary}
                        </p>
                    ) : null}
                </div>
                {!hasChildren ? (
                    <Zap
                        aria-hidden="true"
                        className="mt-0.5 size-3.5 shrink-0 text-white/25"
                    />
                ) : null}
            </div>

            {hasChildren ? (
                <ol className="mx-3 mb-3 overflow-hidden rounded-xl border border-white/[0.07] bg-[#11151e]/55">
                    {step.steps.map((child, childIndex) => (
                        <WorkoutDetailStep
                            key={`${stepLabel(child) ?? "step"}-${childIndex}`}
                            step={child}
                            index={childIndex}
                            athleteProfile={athleteProfile}
                            nested
                        />
                    ))}
                </ol>
            ) : null}
        </li>
    );
}
