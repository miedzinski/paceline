import {
    ArrowLeft,
    FastForward,
    LoaderCircle,
    Pause,
    Play,
    Send,
    Square,
} from "lucide-react";
import type { FormEvent, ReactNode } from "react";
import {
    maximumFtmsPowerWatts,
    minimumFtmsPowerWatts,
} from "./use-ride-session";
import { cn } from "@/lib/utils";

export function ManualTargetForm({
    targetInput,
    isSettingTarget,
    workoutCompleted,
    embedded = false,
    onChange,
    onSubmit,
}: {
    targetInput: string;
    isSettingTarget: boolean;
    workoutCompleted: boolean;
    embedded?: boolean;
    onChange: (value: string) => void;
    onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
    return (
        <form
            onSubmit={onSubmit}
            className={cn(
                embedded
                    ? "border-b border-white/[0.08] pb-4"
                    : "rounded-[2rem] border border-white/[0.1] bg-[#141821] p-4 sm:p-5",
            )}
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
        <div className="mt-4 grid grid-cols-[auto_minmax(0,1fr)_minmax(0,1fr)] items-center gap-2 border-t border-white/[0.08] pt-4">
            <output
                aria-label="Workout target intensity"
                aria-live="polite"
                className="pr-2 font-mono text-xl font-black tracking-[-0.08em] text-[#aeb4ff] sm:text-2xl"
            >
                {percent}%
            </output>
            <button
                type="button"
                onClick={() => onAdjust(-1)}
                disabled={disabled}
                aria-label="Decrease workout target by 1 percent"
                className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-white/[0.1] bg-white/[0.05] text-sm font-black text-white/80 transition-colors hover:bg-white/[0.1] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-45"
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
                className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-[#7e87ff] text-sm font-black text-[#0b0d14] transition-colors hover:bg-[#aeb4ff] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-45"
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
    );
}

export function RideControls({
    children,
    isActive,
    isPaused,
    isStopping,
    isPausing,
    isResuming,
    isAdvancing,
    hasManualStep,
    onAdvance,
    onBack,
    onPause,
    onResume,
    onStop,
    workoutTargetAdjustment,
}: {
    children?: ReactNode;
    isActive: boolean;
    isPaused: boolean;
    isStopping: boolean;
    isPausing: boolean;
    isResuming: boolean;
    isAdvancing: boolean;
    hasManualStep: boolean;
    onAdvance: () => void;
    onBack: () => void;
    onPause: () => void;
    onResume: () => void;
    onStop: () => void;
    workoutTargetAdjustment?: {
        disabled: boolean;
        isAdjusting: boolean;
        percent: number;
        onAdjust: (deltaPercent: number) => void;
    } | null;
}) {
    const isInProgress = isActive || isPaused;
    const isTransitioning = isPausing || isResuming;

    return (
        <section className="rounded-[2rem] border border-white/[0.1] bg-[#141821] p-4 sm:p-5">
            {children ? <div className="space-y-4">{children}</div> : null}
            <div
                className={cn(
                    "flex items-center justify-center gap-3",
                    children ? "mt-4" : null,
                )}
            >
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
                ) : null}
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
                ) : null}
            </div>
            {workoutTargetAdjustment ? (
                <WorkoutTargetAdjustment {...workoutTargetAdjustment} />
            ) : null}
        </section>
    );
}
