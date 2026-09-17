import { ChevronRight, Gauge, Route, Zap } from "lucide-react";
import type { AthleteProfile, WorkoutDefinition } from "@/types";
import { WorkoutStructureGraph } from "@/components/workout-structure-graph";
import { WorkoutOverview } from "@/components/workout-overview";
import {
    flattenWorkoutSteps,
    isWorkoutDefinition,
    workoutMetaItems,
    workoutName,
    workoutSteps,
    type WorkoutItem,
} from "@/lib/workouts";
import { cn } from "@/lib/utils";

export function WorkoutProfile({
    workout,
    compact = false,
    athleteProfile = null,
}: {
    workout: WorkoutItem | WorkoutDefinition | null;
    compact?: boolean;
    athleteProfile?: AthleteProfile | null;
}) {
    const steps =
        workout === null
            ? []
            : isWorkoutDefinition(workout)
              ? flattenWorkoutSteps(workout.steps)
              : workoutSteps(workout);

    if (steps.length === 0) {
        return (
            <div
                className={cn(
                    "flex items-center gap-2 rounded-2xl border border-dashed border-white/[0.12] bg-white/[0.025] px-3 text-xs font-semibold text-white/40",
                    compact ? "h-10" : "h-14",
                )}
            >
                <Zap aria-hidden="true" className="size-3.5 text-[#f5bd58]" />
                No interval steps supplied
            </div>
        );
    }

    return (
        <WorkoutStructureGraph steps={steps} athleteProfile={athleteProfile} />
    );
}

export function WorkoutCard({
    workout,
    onSelect,
    compact = false,
    athleteProfile = null,
}: {
    workout: WorkoutItem;
    onSelect: (workout: WorkoutItem) => void;
    compact?: boolean;
    athleteProfile?: AthleteProfile | null;
}) {
    const meta = workoutMetaItems(workout);
    const label = "sourceEventId" in workout ? "Scheduled" : "Library";

    return (
        <button
            type="button"
            onClick={() => onSelect(workout)}
            className={cn(
                "group relative flex h-full w-full flex-col items-stretch overflow-hidden rounded-[2rem] border text-left transition duration-200 focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none",
                compact
                    ? "min-h-[17rem] border-white/[0.1] bg-[#141821]/90 p-4 hover:-translate-y-1 hover:border-white/[0.2] hover:bg-[#181d28] sm:p-5"
                    : "min-h-[19rem] border-white/[0.1] bg-[#141821]/90 p-4 hover:-translate-y-1 hover:border-white/[0.2] hover:bg-[#181d28] sm:p-5",
            )}
        >
            <div className="pointer-events-none absolute -top-20 -right-14 size-48 rounded-full bg-[#6978ff]/10 blur-3xl transition-opacity group-hover:opacity-80" />
            <div className="relative flex items-start justify-between gap-4">
                <div>
                    <p className="text-[0.6rem] font-bold tracking-[0.2em] text-white/40 uppercase">
                        {label}
                    </p>
                    <h3 className="mt-3 max-w-xl text-xl font-black tracking-[-0.07em] text-white">
                        {workoutName(workout)}
                    </h3>
                </div>
                <span className="grid size-10 shrink-0 place-items-center rounded-2xl border border-white/[0.1] bg-white/[0.07] text-white/55 transition-all group-hover:border-[#8b92ff]/50 group-hover:bg-[#7e87ff] group-hover:text-[#0b0d14]">
                    <ChevronRight aria-hidden="true" className="size-4" />
                </span>
            </div>

            <div
                className={cn(
                    "relative flex flex-wrap gap-x-4 gap-y-2",
                    compact ? "mt-4" : "mt-5",
                )}
            >
                {meta.map(({ label: item, kind }) => {
                    const Icon = kind === "type" ? Gauge : Route;
                    return (
                        <span
                            key={item}
                            className="inline-flex items-center gap-1.5 text-xs font-semibold text-white/48"
                        >
                            <Icon
                                aria-hidden="true"
                                className="size-3.5 text-white/30"
                            />
                            {item}
                        </span>
                    );
                })}
                {workout.target ? (
                    <span className="inline-flex items-center gap-1.5 text-xs font-semibold text-[#f5bd58]">
                        <Zap aria-hidden="true" className="size-3.5" />
                        {workout.target}
                    </span>
                ) : null}
            </div>

            <div
                className={cn(
                    "relative w-full min-w-0",
                    compact ? "mt-4" : "mt-6",
                )}
            >
                <WorkoutProfile
                    workout={workout}
                    compact={compact}
                    athleteProfile={athleteProfile}
                />
            </div>

            <WorkoutOverview workout={workout} compact={compact} />
        </button>
    );
}
