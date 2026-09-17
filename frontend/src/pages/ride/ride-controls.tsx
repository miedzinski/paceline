import {
    ArrowLeft,
    Bluetooth,
    FastForward,
    LoaderCircle,
    Pause,
    Play,
    Send,
    Square,
} from "lucide-react";
import type { FormEvent } from "react";
import {
    maximumFtmsPowerWatts,
    minimumFtmsPowerWatts,
} from "./use-ride-session";
import { cn } from "@/lib/utils";

export function ManualTargetForm({
    targetInput,
    isSettingTarget,
    workoutCompleted,
    onChange,
    onSubmit,
}: {
    targetInput: string;
    isSettingTarget: boolean;
    workoutCompleted: boolean;
    onChange: (value: string) => void;
    onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
    return (
        <form
            onSubmit={onSubmit}
            className="rounded-[2rem] border border-white/[0.1] bg-[#141821] p-4 sm:p-5"
        >
            <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
                <div>
                    <p className="text-[0.6rem] font-bold tracking-[0.2em] text-white/30 uppercase">
                        Manual ERG target
                    </p>
                    <p className="mt-2 text-sm font-bold text-white/75">
                        {workoutCompleted
                            ? "Workout complete · keep riding"
                            : "Set a target for this free ride"}
                    </p>
                </div>
                <div className="flex gap-2">
                    <label className="relative min-w-0 flex-1 sm:w-32">
                        <span className="sr-only">ERG target in watts</span>
                        <input
                            type="number"
                            inputMode="numeric"
                            min={minimumFtmsPowerWatts}
                            max={maximumFtmsPowerWatts}
                            step="1"
                            value={targetInput}
                            onChange={(event) => onChange(event.target.value)}
                            className="h-12 w-full rounded-2xl border border-white/[0.1] bg-[#0d1017] px-3 pr-9 text-sm font-bold text-white outline-none focus:border-[#7e87ff] focus:ring-2 focus:ring-[#7e87ff]/20"
                        />
                        <span className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-xs font-bold text-white/30">
                            W
                        </span>
                    </label>
                    <button
                        type="submit"
                        disabled={isSettingTarget}
                        className="inline-flex min-h-12 items-center justify-center gap-2 rounded-2xl bg-[#f5bd58] px-4 text-xs font-black text-[#19140b] transition hover:bg-[#ffd682] focus-visible:ring-2 focus-visible:ring-[#f5bd58] focus-visible:outline-none disabled:opacity-50"
                    >
                        {isSettingTarget ? (
                            <LoaderCircle
                                aria-hidden="true"
                                className="size-4 animate-spin"
                            />
                        ) : (
                            <Send aria-hidden="true" className="size-4" />
                        )}
                        Set
                    </button>
                </div>
            </div>
        </form>
    );
}

export function WorkoutTargetAdjustment({
    disabled,
    isAdjusting,
    percent,
    onAdjust,
}: {
    disabled: boolean;
    isAdjusting: boolean;
    percent: number;
    onAdjust: (deltaPercent: number) => void;
}) {
    return (
        <section className="rounded-[2rem] border border-[#7e87ff]/25 bg-[#171b2b] p-4 sm:p-5">
            <div className="flex items-center justify-between gap-4">
                <div>
                    <p className="text-[0.6rem] font-bold tracking-[0.2em] text-[#aeb4ff]/60 uppercase">
                        Workout intensity
                    </p>
                    <p className="mt-2 text-sm font-bold text-white/75">
                        Adjust every power step
                    </p>
                </div>
                <span
                    aria-live="polite"
                    className="font-mono text-2xl font-black tracking-[-0.08em] text-[#aeb4ff]"
                >
                    {percent}%
                </span>
            </div>
            <div className="mt-4 grid grid-cols-2 gap-2">
                <button
                    type="button"
                    onClick={() => onAdjust(-1)}
                    disabled={disabled}
                    aria-label="Decrease workout target by 1 percent"
                    className="inline-flex min-h-12 items-center justify-center gap-2 rounded-2xl border border-white/[0.1] bg-white/[0.05] text-sm font-black text-white/80 transition-colors hover:bg-white/[0.1] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-45"
                >
                    {isAdjusting ? (
                        <LoaderCircle
                            aria-hidden="true"
                            className="size-4 animate-spin"
                        />
                    ) : null}
                    -1%
                </button>
                <button
                    type="button"
                    onClick={() => onAdjust(1)}
                    disabled={disabled}
                    aria-label="Increase workout target by 1 percent"
                    className="inline-flex min-h-12 items-center justify-center gap-2 rounded-2xl bg-[#7e87ff] text-sm font-black text-[#0b0d14] transition-colors hover:bg-[#aeb4ff] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-45"
                >
                    {isAdjusting ? (
                        <LoaderCircle
                            aria-hidden="true"
                            className="size-4 animate-spin"
                        />
                    ) : null}
                    +1%
                </button>
            </div>
            <p className="mt-3 text-[0.65rem] font-semibold text-white/35">
                100% is the prescribed workout target.
            </p>
        </section>
    );
}

export function RideControls({
    isActive,
    isPaused,
    isStopping,
    isPausing,
    isResuming,
    isAdvancing,
    hasManualStep,
    isStopped,
    onAdvance,
    onBack,
    onOpenEquipment,
    onPause,
    onResume,
    onStop,
}: {
    isActive: boolean;
    isPaused: boolean;
    isStopping: boolean;
    isPausing: boolean;
    isResuming: boolean;
    isAdvancing: boolean;
    hasManualStep: boolean;
    isStopped: boolean;
    onAdvance: () => void;
    onBack: () => void;
    onOpenEquipment: () => void;
    onPause: () => void;
    onResume: () => void;
    onStop: () => void;
}) {
    const isInProgress = isActive || isPaused;
    const isTransitioning = isPausing || isResuming;

    return (
        <section className="rounded-[2rem] border border-white/[0.1] bg-[#141821] p-4 sm:p-5">
            <div className="flex items-center justify-center gap-3">
                <button
                    type="button"
                    onClick={onOpenEquipment}
                    aria-label="Open equipment"
                    className="grid size-12 place-items-center rounded-2xl border border-white/[0.1] bg-white/[0.05] text-white/55 transition-colors hover:bg-white/[0.1] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
                >
                    <Bluetooth aria-hidden="true" className="size-5" />
                </button>
                <button
                    type="button"
                    onClick={isInProgress ? onStop : onBack}
                    disabled={isStopping}
                    aria-label={isInProgress ? "Stop ride" : "Back to today"}
                    className={cn(
                        "grid size-16 place-items-center rounded-2xl transition-colors focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none disabled:opacity-60",
                        isInProgress
                            ? "bg-[#ff8068] text-[#210b09] shadow-[0_10px_28px_rgba(255,128,104,0.16)] hover:bg-[#ff9b88] focus-visible:ring-[#ff8068] focus-visible:ring-offset-[#141821]"
                            : "bg-[#7e87ff] text-[#0b0d14] hover:bg-[#aeb4ff] focus-visible:ring-[#8b92ff] focus-visible:ring-offset-[#141821]",
                    )}
                >
                    {isInProgress ? (
                        <Square
                            aria-hidden="true"
                            className="size-5 fill-current"
                        />
                    ) : (
                        <ArrowLeft aria-hidden="true" className="size-5" />
                    )}
                </button>
                {isActive && hasManualStep ? (
                    <button
                        type="button"
                        onClick={onAdvance}
                        disabled={isAdvancing || isStopping}
                        aria-label="Advance interval"
                        className="grid size-12 place-items-center rounded-2xl border border-white/[0.1] bg-white/[0.05] text-white/70 transition-colors hover:bg-white/[0.1] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-50"
                    >
                        {isAdvancing ? (
                            <LoaderCircle
                                aria-hidden="true"
                                className="size-5 animate-spin"
                            />
                        ) : (
                            <FastForward
                                aria-hidden="true"
                                className="size-5"
                            />
                        )}
                    </button>
                ) : (
                    <span aria-hidden="true" className="size-12" />
                )}
                {isInProgress ? (
                    <button
                        type="button"
                        onClick={isPaused ? onResume : onPause}
                        disabled={isStopping || isTransitioning}
                        aria-label={isPaused ? "Resume ride" : "Pause ride"}
                        className="grid size-12 place-items-center rounded-2xl border border-white/[0.1] bg-white/[0.05] text-white/70 transition-colors hover:bg-white/[0.1] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-50"
                    >
                        {isTransitioning ? (
                            <LoaderCircle
                                aria-hidden="true"
                                className="size-5 animate-spin"
                            />
                        ) : isPaused ? (
                            <Play
                                aria-hidden="true"
                                className="size-5 fill-current"
                            />
                        ) : (
                            <Pause aria-hidden="true" className="size-5" />
                        )}
                    </button>
                ) : (
                    <span aria-hidden="true" className="size-12" />
                )}
            </div>
            <p className="mt-4 text-center text-[0.65rem] font-semibold text-white/30">
                {isStopped
                    ? "Ride ended · choose what to do with the activity"
                    : isPaused
                      ? "Ride paused · resume when you are ready"
                      : hasManualStep
                        ? "Advance when ready · stop to finish the ride"
                        : "Stop to finish the ride and open upload options"}
            </p>
        </section>
    );
}
