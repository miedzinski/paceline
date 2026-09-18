import {
    ArrowLeft,
    FastForward,
    LoaderCircle,
    Pause,
    Play,
    Square,
} from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

function TargetAdjustmentButton({
    disabled,
    isAdjusting,
    delta,
    unit,
    onAdjust,
}: {
    disabled: boolean;
    isAdjusting: boolean;
    delta: -1 | 1;
    unit: "percent" | "watt";
    onAdjust: (delta: number) => void;
}) {
    const isIncrease = delta === 1;
    const targetLabel =
        unit === "percent" ? "workout target" : "manual ERG target";
    const amountLabel = unit === "percent" ? "1 percent" : "1 watt";
    return (
        <button
            type="button"
            onClick={() => onAdjust(delta)}
            disabled={disabled}
            aria-label={`${isIncrease ? "Increase" : "Decrease"} ${targetLabel} by ${amountLabel}`}
            className="grid size-12 shrink-0 place-items-center rounded-2xl border border-white/[0.1] bg-white/[0.05] text-xl font-black text-white/75 transition-colors hover:bg-white/[0.1] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-45"
        >
            {isAdjusting ? (
                <LoaderCircle
                    aria-hidden="true"
                    className="size-4 animate-spin"
                />
            ) : isIncrease ? (
                "+"
            ) : (
                "−"
            )}
        </button>
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
    manualTargetAdjustment,
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
    manualTargetAdjustment?: {
        disabled: boolean;
        isAdjusting: boolean;
        onAdjust: (deltaWatts: number) => void;
    } | null;
    workoutTargetAdjustment?: {
        disabled: boolean;
        isAdjusting: boolean;
        onAdjust: (deltaPercent: number) => void;
    } | null;
}) {
    const isInProgress = isActive || isPaused;
    const isTransitioning = isPausing || isResuming;
    const targetAdjustment =
        workoutTargetAdjustment ?? manualTargetAdjustment ?? null;
    const targetAdjustmentUnit =
        workoutTargetAdjustment !== null &&
        workoutTargetAdjustment !== undefined
            ? "percent"
            : "watt";

    return (
        <section className="shrink-0 rounded-[2rem] border border-white/[0.1] bg-[#141821] p-4 sm:p-5">
            {children ? <div className="space-y-4">{children}</div> : null}
            <div
                className={cn(
                    "flex items-center justify-center gap-3",
                    children ? "mt-4" : null,
                )}
            >
                {targetAdjustment ? (
                    <TargetAdjustmentButton
                        disabled={targetAdjustment.disabled}
                        isAdjusting={targetAdjustment.isAdjusting}
                        delta={-1}
                        unit={targetAdjustmentUnit}
                        onAdjust={targetAdjustment.onAdjust}
                    />
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
                <button
                    type="button"
                    onClick={isInProgress ? onStop : onBack}
                    disabled={isStopping}
                    aria-label={isInProgress ? "Stop ride" : "Back to today"}
                    className={cn(
                        "grid size-12 shrink-0 place-items-center rounded-2xl border transition-colors focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none disabled:opacity-60",
                        isInProgress
                            ? "border-[#ff8068]/35 bg-[#ff8068]/10 text-[#ff9b88] hover:bg-[#ff8068]/20 focus-visible:ring-[#ff8068] focus-visible:ring-offset-[#141821]"
                            : "border-[#7e87ff]/50 bg-[#7e87ff]/15 text-[#c3c7ff] hover:bg-[#7e87ff]/25 focus-visible:ring-[#8b92ff] focus-visible:ring-offset-[#141821]",
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
                {targetAdjustment ? (
                    <TargetAdjustmentButton
                        disabled={targetAdjustment.disabled}
                        isAdjusting={targetAdjustment.isAdjusting}
                        delta={1}
                        unit={targetAdjustmentUnit}
                        onAdjust={targetAdjustment.onAdjust}
                    />
                ) : null}
            </div>
        </section>
    );
}
