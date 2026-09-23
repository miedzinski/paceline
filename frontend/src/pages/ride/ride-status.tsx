import { shouldShowConnectionBanner } from "@/lib/connection";
import { ChevronRight, CircleAlert, Unplug } from "lucide-react";
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
    heartRateAvailability,
    speed,
    heartRateSelected,
}: {
    currentPower: number | null;
    cadence: number | null;
    heartRate: number | null;
    heartRateAvailability: "CURRENT" | "UNAVAILABLE" | "INTERRUPTED";
    speed: number | null;
    heartRateSelected: boolean;
}) {
    return (
        <section className="rounded-[2rem] border border-white/[0.1] bg-[#141821] p-3 sm:p-4">
            <div className="grid grid-cols-2 gap-2.5">
                <MetricTile
                    label="Power"
                    value={formatMetric(currentPower)}
                    unit="W"
                    accent="white"
                    prominent
                />
                <MetricTile
                    label="Cadence"
                    value={formatMetric(cadence)}
                    unit="rpm"
                    accent="amber"
                    prominent
                />
                <MetricTile
                    label="Heart rate"
                    value={formatMetric(heartRate)}
                    unit={
                        !heartRateSelected
                            ? "not selected"
                            : heartRateAvailability === "CURRENT"
                              ? "bpm"
                              : heartRateAvailability === "INTERRUPTED"
                                ? "interrupted"
                                : "unavailable"
                    }
                    accent="coral"
                />
                <MetricTile
                    label="Speed"
                    value={formatMetric(speed, 1)}
                    unit="km/h"
                    accent="blue"
                />
            </div>
        </section>
    );
}

export function ErgProtectionBanner({
    protection,
    overlay = false,
}: {
    protection: TrainingSessionResponse["ergProtection"];
    overlay?: boolean;
}) {
    if (protection.state === "INACTIVE") {
        return null;
    }

    const isUnavailable = protection.state === "UNAVAILABLE";
    const isRecoveryFailed = protection.state === "RECOVERY_FAILED";
    const isFailure = isUnavailable || isRecoveryFailed;
    return (
        <div
            className={cn(
                overlay
                    ? "pointer-events-none absolute inset-x-3 top-3 z-30 shadow-[0_18px_45px_rgba(0,0,0,0.35)] backdrop-blur-xl sm:inset-x-5"
                    : "mt-5",
                "flex items-start gap-3 rounded-2xl border p-4 text-sm leading-6",
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
                        : isRecoveryFailed
                          ? "ERG recovery failed"
                          : "ERG protection active"}
                </p>
                <p className="mt-1 text-xs opacity-75">
                    {isUnavailable
                        ? (protection.error ??
                          "The trainer did not accept the resistance-release command.")
                        : isRecoveryFailed
                          ? (protection.error ??
                            "The trainer did not accept the recovery target. The workout continues recording while resistance remains released.")
                          : `Resistance was released at ${formatMetric(protection.cadenceRpm)} rpm. ERG will resume automatically when cadence recovers.`}
                </p>
            </div>
        </div>
    );
}

function MetricTile({
    label,
    value,
    unit,
    accent,
    prominent = false,
}: {
    label: string;
    value: string;
    unit: string;
    accent: "white" | "amber" | "coral" | "blue";
    prominent?: boolean;
}) {
    const accentClass = {
        white: "text-white",
        amber: "text-[#f5bd58]",
        coral: "text-[#ff8068]",
        blue: "text-[#8b92ff]",
    }[accent];

    return (
        <div className="flex min-h-[5.75rem] flex-col items-center justify-center rounded-[1.15rem] border border-white/[0.08] bg-[#1b202a] px-2 py-2.5 sm:px-3 sm:py-3">
            <p className="truncate text-center text-[0.56rem] font-bold tracking-[0.16em] text-white/30 uppercase">
                {label}
            </p>
            <div className="mt-1 flex flex-col items-center">
                <span
                    className={cn(
                        "font-mono leading-none font-bold tracking-[-0.09em]",
                        prominent
                            ? "text-[2.05rem] sm:text-[2.35rem]"
                            : "text-[1.75rem] sm:text-[2rem]",
                        accentClass,
                    )}
                >
                    {value}
                </span>
                <span className="mt-0.5 text-[0.58rem] font-bold text-white/30">
                    {unit}
                </span>
            </div>
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
    trainerName,
    telemetryAvailable,
    status,
    retryAttempt,
    error,
    paused,
    postRideOpen,
    onOpenEquipment,
}: {
    hasTrainer: boolean;
    trainerName: string | null;
    telemetryAvailable: boolean;
    status: TrainingSessionResponse["trainerConnection"];
    retryAttempt: number | null;
    error: string | null;
    paused: boolean;
    postRideOpen: boolean;
    onOpenEquipment: () => void;
}) {
    if (
        !shouldShowConnectionBanner({
            hasTrainer,
            telemetryAvailable,
            status,
            paused,
            postRideOpen,
        })
    ) {
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
                    <Unplug
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
                    <span className="mt-1 block text-xs text-[#f5d28c]/80">
                        Control & recovery:{" "}
                        {trainerName ?? "selected trainer unavailable"}
                    </span>
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
