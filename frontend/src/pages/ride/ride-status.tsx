import { ChevronRight, CircleAlert, WifiOff } from "lucide-react";
import type { TrainingSessionResponse } from "@/types";
import { cn } from "@/lib/utils";

function formatMetric(value: number | null, fractionDigits = 0): string {
    if (value === null) {
        return "—";
    }

    return new Intl.NumberFormat(undefined, {
        maximumFractionDigits: fractionDigits,
        minimumFractionDigits: fractionDigits,
    }).format(value);
}

export function MetricsPanel({
    currentPower,
    cadence,
    heartRate,
    speed,
    elapsed,
    target,
    appliedTarget,
    controlMode,
    protectionState,
    step,
    powerProgress,
}: {
    currentPower: number | null;
    cadence: number | null;
    heartRate: number | null;
    speed: number | null;
    elapsed: string;
    target: number | null;
    appliedTarget: number | null;
    controlMode: TrainingSessionResponse["controlMode"];
    protectionState: TrainingSessionResponse["ergProtection"]["state"];
    step: string;
    powerProgress: number;
}) {
    const protectionActive =
        protectionState === "BAILED_OUT" ||
        protectionState === "RECOVERY_RETRYING" ||
        protectionState === "RECOVERY_FAILED";
    const targetDetail = protectionActive
        ? `ERG released · ${appliedTarget ?? 0} W applied`
        : controlMode === "FREE_RIDE"
          ? "Free Ride · manual resistance"
          : protectionState === "UNAVAILABLE"
            ? "ERG target unavailable"
            : target === null
              ? "No target"
              : `target ${target} W`;

    return (
        <section className="rounded-[2rem] border border-white/[0.1] bg-[#141821] p-4 sm:p-5">
            <div className="grid grid-cols-2 gap-2.5">
                <MetricTile
                    label="Power"
                    value={formatMetric(currentPower)}
                    unit="W"
                    detail={targetDetail}
                    accent="white"
                    progress={powerProgress}
                    prominent
                />
                <MetricTile
                    label="Cadence"
                    value={formatMetric(cadence, 1)}
                    unit="rpm"
                    detail="live reading"
                    accent="amber"
                    prominent
                />
                <MetricTile
                    label="Heart rate"
                    value={formatMetric(heartRate)}
                    unit="bpm"
                    detail="selected source"
                    accent="coral"
                />
                <MetricTile
                    label="Speed"
                    value={formatMetric(speed, 1)}
                    unit="km/h"
                    detail="live reading"
                    accent="blue"
                />
            </div>
            <div className="mt-2.5 grid grid-cols-4 gap-2 rounded-2xl border border-white/[0.07] bg-[#0d1017] px-2 py-3 sm:px-3">
                <MiniStat label="Elapsed" value={elapsed} />
                <MiniStat
                    label="Target"
                    value={target === null ? "—" : `${target} W`}
                />
                <MiniStat
                    label="Applied"
                    value={appliedTarget === null ? "—" : `${appliedTarget} W`}
                />
                <MiniStat label="Step" value={step} />
            </div>
        </section>
    );
}

export function ErgProtectionBanner({
    protection,
}: {
    protection: TrainingSessionResponse["ergProtection"];
}) {
    if (protection.state === "INACTIVE") {
        return null;
    }

    const isUnavailable = protection.state === "UNAVAILABLE";
    const isRecoveryRetrying = protection.state === "RECOVERY_RETRYING";
    const isRecoveryFailed = protection.state === "RECOVERY_FAILED";
    const isFailure = isUnavailable || isRecoveryFailed;
    return (
        <div
            className={cn(
                "mt-5 flex items-start gap-3 rounded-2xl border p-4 text-sm leading-6",
                isFailure
                    ? "border-[#703e49] bg-[#2a1821] text-[#ffc4c5]"
                    : "border-[#806335] bg-[#2b2418] text-[#f5d28c]",
            )}
        >
            <CircleAlert
                aria-hidden="true"
                className="mt-0.5 size-5 shrink-0"
            />
            <div>
                <p className="font-bold">
                    {isUnavailable
                        ? "ERG protection is unavailable"
                        : isRecoveryRetrying
                          ? "ERG recovery retrying"
                          : isRecoveryFailed
                            ? "ERG recovery failed"
                            : "ERG protection active"}
                </p>
                <p className="mt-1 text-xs opacity-75">
                    {isUnavailable
                        ? (protection.error ??
                          "The trainer did not accept the protective zero-watt target.")
                        : isRecoveryRetrying
                          ? `The trainer rejected the recovery target. Retry ${protection.retryAttempt ?? "—"} is scheduled while resistance remains at 0 W.`
                          : isRecoveryFailed
                            ? (protection.error ??
                              "The trainer did not accept the recovery target after the configured retries. The workout continues recording at 0 W.")
                            : `Resistance was released at ${formatMetric(protection.cadenceRpm, 1)} rpm. ERG will resume automatically when cadence recovers.`}
                </p>
            </div>
        </div>
    );
}

function MetricTile({
    label,
    value,
    unit,
    detail,
    accent,
    progress,
    prominent = false,
}: {
    label: string;
    value: string;
    unit: string;
    detail: string;
    accent: "white" | "amber" | "coral" | "blue";
    progress?: number;
    prominent?: boolean;
}) {
    const accentClass = {
        white: "text-white",
        amber: "text-[#f5bd58]",
        coral: "text-[#ff8068]",
        blue: "text-[#8b92ff]",
    }[accent];

    return (
        <div className="min-h-[8.4rem] rounded-[1.35rem] border border-white/[0.08] bg-[#1b202a] p-4 sm:p-5">
            <p className="truncate text-[0.58rem] font-bold tracking-[0.18em] text-white/30 uppercase">
                {label}
            </p>
            <div className="mt-3 flex items-baseline gap-1.5">
                <span
                    className={cn(
                        "font-mono leading-none font-bold tracking-[-0.09em]",
                        prominent
                            ? "text-[2.5rem] sm:text-[3rem]"
                            : "text-[2.1rem] sm:text-[2.4rem]",
                        accentClass,
                    )}
                >
                    {value}
                </span>
                <span className="text-[0.62rem] font-bold text-white/30">
                    {unit}
                </span>
            </div>
            <div className="mt-3 flex items-center justify-between gap-2">
                <p className="truncate text-[0.61rem] font-semibold text-white/30">
                    {detail}
                </p>
                {progress !== undefined ? (
                    <span className="text-[0.61rem] font-bold text-white/25">
                        {progress > 0 ? `${Math.round(progress)}%` : "—"}
                    </span>
                ) : null}
            </div>
            {progress !== undefined ? (
                <div className="mt-2 h-1 overflow-hidden rounded-full bg-white/[0.08]">
                    <span
                        className="block h-full rounded-full bg-[#8b92ff] transition-[width] duration-500"
                        style={{ width: `${progress}%` }}
                    />
                </div>
            ) : null}
        </div>
    );
}

function MiniStat({ label, value }: { label: string; value: string }) {
    return (
        <div className="min-w-0 text-center">
            <p className="truncate text-[0.55rem] font-bold tracking-[0.15em] text-white/25 uppercase">
                {label}
            </p>
            <p className="mt-1 truncate text-xs font-bold text-white/70">
                {value}
            </p>
        </div>
    );
}

export function ErrorNotice({ message }: { message: string }) {
    return (
        <div className="flex items-start gap-3 rounded-2xl border border-[#703e49] bg-[#2a1821] p-4 text-sm leading-6 text-[#ffc4c5]">
            <CircleAlert
                aria-hidden="true"
                className="mt-0.5 size-5 shrink-0"
            />
            <span>{message}</span>
        </div>
    );
}

export function ConnectionBanner({
    hasTrainer,
    telemetryAvailable,
    status,
    retryAttempt,
    error,
    onOpenEquipment,
}: {
    hasTrainer: boolean;
    telemetryAvailable: boolean;
    status: TrainingSessionResponse["trainerConnection"];
    retryAttempt: number | null;
    error: string | null;
    onOpenEquipment: () => void;
}) {
    if (hasTrainer && telemetryAvailable && status === "CONNECTED") {
        return null;
    }

    const reconnecting = status === "RECONNECTING";
    const interrupted = status === "INTERRUPTED" || status === "RECONNECTING";
    const message = reconnecting
        ? `Trainer connection interrupted. Reconnecting${retryAttempt === null ? "" : ` (attempt ${retryAttempt})`}… The workout continues while live telemetry is unavailable.`
        : interrupted
          ? "Trainer connection interrupted. The workout continues while we retry."
          : hasTrainer
            ? "Waiting for a live trainer reading."
            : "The trainer is not connected.";

    return (
        <div className="mt-5 flex flex-col items-start justify-between gap-3 rounded-2xl border border-[#806335] bg-[#2b2418] p-4 text-sm text-[#f5d28c] sm:flex-row sm:items-center">
            <div className="flex items-start gap-3">
                {hasTrainer ? (
                    <WifiOff
                        aria-hidden="true"
                        className="mt-0.5 size-4 shrink-0"
                    />
                ) : (
                    <CircleAlert
                        aria-hidden="true"
                        className="mt-0.5 size-4 shrink-0"
                    />
                )}
                <span>
                    {message}
                    {error ? (
                        <span className="mt-1 block text-xs text-[#f5d28c]/70">
                            {error}
                        </span>
                    ) : null}
                    {reconnecting ? (
                        <span className="mt-1 block text-xs text-[#f5d28c]/70">
                            You can scan and connect manually; a manual
                            connection takes precedence over an automatic retry.
                        </span>
                    ) : null}
                </span>
            </div>
            <button
                type="button"
                onClick={onOpenEquipment}
                className="inline-flex min-h-9 items-center gap-1.5 rounded-xl bg-[#f5d28c]/10 px-3 text-xs font-bold text-[#f5d28c] transition-colors hover:bg-[#f5d28c]/15 focus-visible:ring-2 focus-visible:ring-[#f5d28c] focus-visible:outline-none"
            >
                Open equipment
                <ChevronRight aria-hidden="true" className="size-3.5" />
            </button>
        </div>
    );
}
