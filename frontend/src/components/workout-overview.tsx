import { Clock3, Flame } from "lucide-react";
import type { WorkoutZoneDistribution } from "@/types";
import {
    formatCompactDuration,
    powerZoneColors,
    sweetSpotColor,
    type WorkoutItem,
} from "@/lib/workouts";
import { cn } from "@/lib/utils";

export function WorkoutOverview({
    workout,
    compact = false,
}: {
    workout: WorkoutItem;
    compact?: boolean;
}) {
    const zones = workout.plannedZoneDistribution ?? [];
    const chartZones = zones.filter((zone) => !isSweetSpotZone(zone.zone));
    const hasOverview =
        workout.durationSeconds !== null ||
        workout.trainingLoad !== null ||
        zones.length > 0;
    if (!hasOverview) {
        return null;
    }

    const totalSeconds = chartZones.reduce(
        (total, zone) => total + Math.max(0, zone.durationSeconds),
        0,
    );
    const maxZoneSeconds = Math.max(
        1,
        ...chartZones.map((zone) => Math.max(0, zone.durationSeconds)),
    );

    return (
        <div
            className={cn(
                "relative mt-3 grid grid-cols-2 gap-x-4 gap-y-3 border-t border-white/[0.08] pt-3",
                compact
                    ? "text-[0.68rem] sm:grid-cols-[minmax(0,0.9fr)_minmax(0,1.15fr)_minmax(4.5rem,0.72fr)]"
                    : "text-xs sm:grid-cols-[minmax(0,0.8fr)_minmax(0,0.95fr)_minmax(10rem,1.25fr)]",
            )}
        >
            <div className="min-w-0">
                <p className="font-bold tracking-[0.16em] text-white/38 uppercase">
                    Workout overview
                </p>
                <div className="mt-2 space-y-1.5 text-white/58">
                    <OverviewStat
                        icon={Clock3}
                        label="Duration"
                        value={formatCompactDuration(workout.durationSeconds)}
                    />
                    <OverviewStat
                        icon={Flame}
                        label="Load"
                        value={formatLoad(workout.trainingLoad)}
                    />
                </div>
            </div>

            <div className="min-w-0">
                <p className="font-bold tracking-[0.16em] text-white/38 uppercase">
                    Zone distribution
                </p>
                {zones.length > 0 ? (
                    <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-white/58">
                        {zones.map((zone, index) => (
                            <div
                                key={`${zone.zone}-${index}`}
                                className="flex min-w-0 items-center gap-1"
                            >
                                <span
                                    className="font-bold"
                                    style={{ color: zoneColor(zone, index) }}
                                >
                                    {zone.zone}:
                                </span>
                                <span className="truncate">
                                    {formatCompactDurationOrDash(
                                        zone.durationSeconds,
                                    )}
                                </span>
                            </div>
                        ))}
                    </div>
                ) : (
                    <p className="mt-2 text-white/30">Not supplied</p>
                )}
            </div>

            {chartZones.length > 0 ? (
                <div
                    className="col-span-2 flex h-16 min-w-0 items-end gap-1.5 sm:col-span-1"
                    aria-label="Planned numbered zone distribution chart"
                >
                    {chartZones.map((zone, index) => {
                        const seconds = Math.max(0, zone.durationSeconds);
                        const percentage =
                            totalSeconds > 0
                                ? Math.round((seconds / totalSeconds) * 100)
                                : 0;
                        const height =
                            seconds === 0
                                ? 10
                                : Math.max(
                                      16,
                                      (seconds / maxZoneSeconds) * 100,
                                  );
                        return (
                            <div
                                key={`${zone.zone}-bar-${index}`}
                                className="flex h-full min-w-0 flex-1 flex-col items-center justify-end gap-1"
                                title={`${zone.zone}: ${percentage}%`}
                            >
                                <span className="text-[0.58rem] font-bold text-white/42">
                                    {percentage}%
                                </span>
                                <span
                                    className="block w-full rounded-md"
                                    style={{
                                        height: `${height}%`,
                                        backgroundColor: zoneColor(zone, index),
                                    }}
                                />
                            </div>
                        );
                    })}
                </div>
            ) : null}
        </div>
    );
}

function OverviewStat({
    icon: Icon,
    label,
    value,
}: {
    icon: typeof Clock3;
    label: string;
    value: string | null;
}) {
    return (
        <div className="flex items-center gap-1.5">
            <Icon
                aria-hidden="true"
                className="size-3.5 shrink-0 text-white/28"
            />
            <span className="font-semibold text-white/42">{label}:</span>
            <span className="font-bold text-white/70">{value ?? "—"}</span>
        </div>
    );
}

function formatLoad(load: number | null): string | null {
    return load === null ? null : `${Math.round(load)}`;
}

function formatCompactDurationOrDash(seconds: number): string {
    return seconds > 0 ? (formatCompactDuration(seconds) ?? "—") : "—";
}

function isSweetSpotZone(zone: string): boolean {
    const normalized = zone.trim().toUpperCase();
    return normalized === "SS" || normalized === "SWEET SPOT";
}

function zoneColor(zone: WorkoutZoneDistribution, index: number): string {
    if (isSweetSpotZone(zone.zone)) {
        return sweetSpotColor;
    }

    const match = zone.zone.match(/\d+/);
    const zoneNumber = match === null ? index + 1 : Number(match[0]);
    return powerZoneColors[
        Math.max(0, zoneNumber - 1) % powerZoneColors.length
    ];
}
