import { Activity, Gauge, HeartPulse, Zap } from "lucide-react";
import { useMemo, useState } from "react";
import { cn } from "@/lib/utils";

export interface RidePoint {
    timestamp: number;
    powerWatts: number | null;
    cadenceRpm: number | null;
    speedKph: number | null;
    heartRateBpm: number | null;
}

type ChartMode = "power" | "heartRate" | "cadence";

function modeValue(point: RidePoint, mode: ChartMode): number | null {
    switch (mode) {
        case "power":
            return point.powerWatts;
        case "heartRate":
            return point.heartRateBpm;
        case "cadence":
            return point.cadenceRpm;
    }
}

function modeLabel(mode: ChartMode): string {
    switch (mode) {
        case "power":
            return "Power";
        case "heartRate":
            return "Heart rate";
        case "cadence":
            return "Cadence";
    }
}

function modeUnit(mode: ChartMode): string {
    switch (mode) {
        case "power":
            return "W";
        case "heartRate":
            return "bpm";
        case "cadence":
            return "rpm";
    }
}

function modeColor(mode: ChartMode): string {
    switch (mode) {
        case "power":
            return "#8b92ff";
        case "heartRate":
            return "#ff8068";
        case "cadence":
            return "#f5bd58";
    }
}

function pathFor(
    points: RidePoint[],
    mode: ChartMode,
    minimum: number,
    maximum: number,
): string {
    const segments: string[] = [];
    let currentSegment = "";
    const range = Math.max(1, maximum - minimum);

    points.forEach((point, index) => {
        const value = modeValue(point, mode);
        if (value === null) {
            if (currentSegment) {
                segments.push(currentSegment);
                currentSegment = "";
            }
            return;
        }

        const x = points.length <= 1 ? 0 : (index / (points.length - 1)) * 100;
        const y = 88 - ((value - minimum) / range) * 76;
        currentSegment += `${currentSegment ? " L" : "M"}${x.toFixed(2)} ${y.toFixed(2)}`;
    });

    if (currentSegment) {
        segments.push(currentSegment);
    }

    return segments.join(" ");
}

function finiteValues(points: RidePoint[], mode: ChartMode): number[] {
    return points
        .map((point) => modeValue(point, mode))
        .filter((value): value is number => value !== null);
}

function chartBounds(
    points: RidePoint[],
    mode: ChartMode,
    target: number | null,
): { minimum: number; maximum: number } {
    const values = finiteValues(points, mode);
    if (mode === "power" && target !== null) {
        values.push(target);
    }

    if (values.length === 0) {
        switch (mode) {
            case "heartRate":
                return { minimum: 80, maximum: 190 };
            case "cadence":
                return { minimum: 40, maximum: 120 };
            default:
                return { minimum: 0, maximum: 300 };
        }
    }

    const minimumValue = Math.min(...values);
    const maximumValue = Math.max(...values);
    const padding = Math.max(8, (maximumValue - minimumValue) * 0.18);
    return {
        minimum: Math.max(0, Math.floor((minimumValue - padding) / 10) * 10),
        maximum: Math.ceil((maximumValue + padding) / 10) * 10,
    };
}

function formatValue(value: number): string {
    return new Intl.NumberFormat(undefined, {
        maximumFractionDigits: 0,
    }).format(value);
}

export function TelemetryChart({
    points,
    target,
    compact = false,
}: {
    points: RidePoint[];
    target: number | null;
    compact?: boolean;
}) {
    const [mode, setMode] = useState<ChartMode>("power");
    const bounds = useMemo(
        () => chartBounds(points, mode, target),
        [mode, points, target],
    );
    const line = useMemo(
        () => pathFor(points, mode, bounds.minimum, bounds.maximum),
        [bounds.maximum, bounds.minimum, mode, points],
    );
    const targetY =
        target === null || mode !== "power"
            ? null
            : 88 -
              ((target - bounds.minimum) /
                  Math.max(1, bounds.maximum - bounds.minimum)) *
                  76;
    const hasData = finiteValues(points, mode).length > 0;
    const color = modeColor(mode);

    return (
        <section
            className={cn(
                "rounded-[2rem] border border-white/[0.1] bg-[#141821]",
                compact ? "p-4" : "p-5 sm:p-6",
            )}
        >
            <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                    <p className="text-[0.6rem] font-bold tracking-[0.2em] text-white/30 uppercase">
                        Live trace
                    </p>
                    <h2 className="mt-2 text-xl font-black tracking-[-0.06em] text-white">
                        Readings as you ride
                    </h2>
                </div>
                <div className="flex rounded-xl bg-white/[0.05] p-1">
                    <ChartToggle
                        active={mode === "power"}
                        label="Power"
                        onClick={() => setMode("power")}
                        icon={Zap}
                    />
                    <ChartToggle
                        active={mode === "heartRate"}
                        label="HR"
                        onClick={() => setMode("heartRate")}
                        icon={HeartPulse}
                    />
                    <ChartToggle
                        active={mode === "cadence"}
                        label="Cadence"
                        onClick={() => setMode("cadence")}
                        icon={Gauge}
                    />
                </div>
            </div>

            <div
                className={cn(
                    "relative mt-5 overflow-hidden rounded-2xl border border-white/[0.08] bg-[#0b0e14] px-2 py-2",
                    compact ? "h-44" : "h-56",
                )}
            >
                <div className="pointer-events-none absolute inset-x-3 top-3 bottom-6 flex flex-col justify-between">
                    {[0, 1, 2, 3].map((lineIndex) => (
                        <span
                            key={lineIndex}
                            className="border-t border-white/[0.065]"
                        />
                    ))}
                </div>
                <div className="pointer-events-none absolute top-3 bottom-6 left-3 flex flex-col justify-between text-[0.57rem] font-semibold text-white/25">
                    <span>{formatValue(bounds.maximum)}</span>
                    <span>
                        {formatValue((bounds.maximum + bounds.minimum) / 2)}
                    </span>
                    <span>{formatValue(bounds.minimum)}</span>
                </div>
                <svg
                    viewBox="0 0 100 100"
                    preserveAspectRatio="none"
                    className="absolute inset-x-3 top-3 bottom-6 h-[calc(100%-2.25rem)] w-[calc(100%-1.5rem)] overflow-visible pl-7"
                    role="img"
                    aria-label={`${modeLabel(mode)} live trace chart`}
                >
                    {targetY !== null ? (
                        <line
                            x1="0"
                            x2="100"
                            y1={targetY}
                            y2={targetY}
                            stroke="#f5bd58"
                            strokeDasharray="2 2"
                            strokeWidth="0.7"
                            vectorEffect="non-scaling-stroke"
                        />
                    ) : null}
                    {line ? (
                        <path
                            d={line}
                            fill="none"
                            stroke={color}
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth="2.2"
                            vectorEffect="non-scaling-stroke"
                        />
                    ) : null}
                </svg>
                {!hasData ? (
                    <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 text-center">
                        <Activity
                            aria-hidden="true"
                            className="size-5 text-white/20"
                        />
                        <p className="text-xs font-semibold text-white/35">
                            Waiting for a live reading.
                        </p>
                    </div>
                ) : null}
                <div className="absolute right-3 bottom-2 left-10 flex justify-between text-[0.57rem] font-semibold text-white/22">
                    <span>Start</span>
                    <span>Now</span>
                </div>
            </div>

            <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-[0.63rem] font-semibold text-white/35">
                <span className="inline-flex items-center gap-1.5">
                    <span
                        className="size-2 rounded-full"
                        style={{ backgroundColor: color }}
                    />
                    {modeLabel(mode)} · {modeUnit(mode)}
                </span>
                {mode === "power" && target !== null ? (
                    <span className="inline-flex items-center gap-1.5">
                        <span className="w-3 border-t border-dashed border-[#f5bd58]" />
                        Target {target} W
                    </span>
                ) : null}
            </div>
        </section>
    );
}

function ChartToggle({
    active,
    label,
    onClick,
    icon: Icon,
}: {
    active: boolean;
    label: string;
    onClick: () => void;
    icon: typeof Zap;
}) {
    return (
        <button
            type="button"
            onClick={onClick}
            className={cn(
                "inline-flex min-h-8 items-center gap-1.5 rounded-lg px-2 text-[0.62rem] font-bold transition-colors focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none sm:px-2.5",
                active
                    ? "bg-[#7e87ff] text-[#0b0d14]"
                    : "text-white/35 hover:text-white/75",
            )}
        >
            <Icon aria-hidden="true" className="size-3.5" />
            <span>{label}</span>
        </button>
    );
}
