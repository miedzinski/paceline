import type {
    AthleteProfile,
    LibraryWorkout,
    PowerZone,
    ScheduledWorkout,
    WorkoutDefinition,
    WorkoutSelection,
    WorkoutStep,
    WorkoutTarget,
} from "@/types";
import { flattenWorkoutSteps } from "./workout-steps.ts";

export { flattenWorkoutSteps } from "./workout-steps.ts";

export type WorkoutItem = ScheduledWorkout | LibraryWorkout;

export const powerZoneColors = [
    "#85888f", // Z1 — active recovery
    "#3f8ff3", // Z2 — endurance
    "#58b957", // Z3 — tempo
    "#f0c13a", // Z4 — threshold
    "#ef6945", // Z5 — VO2 max
    "#f2361b", // Z6 — anaerobic
    "#a875e8", // Z7 — neuromuscular
];

export const sweetSpotColor = "#2bb8a7";
export const freeRideColor = "#b8bbc1";

export function isFreeRideStep(step: WorkoutStep): boolean {
    return step.freeRide === true;
}

function stepPowerRangeWatts(
    step: WorkoutStep,
    profile: AthleteProfile | null | undefined,
): [number, number] | null {
    const rawPower = step.power;
    const target = isFtpRelativePowerTarget(rawPower)
        ? rawPower
        : (step.resolvedPower ?? rawPower);
    if (target === null) {
        return null;
    }

    const start = target.start ?? target.value;
    const end = target.end ?? target.value ?? start;
    if (start === null || end === null) {
        return null;
    }

    if (isFtpRelativePowerTarget(target)) {
        const ftpWatts = profile?.indoorFtpWatts ?? profile?.ftpWatts;
        if (ftpWatts === null || ftpWatts === undefined) {
            return null;
        }

        return [(start / 100) * ftpWatts, (end / 100) * ftpWatts];
    }

    return [start, end];
}

export function powerZoneForStep(
    step: WorkoutStep,
    profile: AthleteProfile | null | undefined,
): PowerZone | null {
    if (
        profile === null ||
        profile === undefined ||
        profile.powerZones.length === 0
    ) {
        return null;
    }

    const firstZone = profile.powerZones[0];
    const lastZone = profile.powerZones.at(-1);
    if (firstZone === undefined || lastZone === undefined) {
        return null;
    }

    const declaredZoneNumber = powerZoneNumberForStep(step);
    if (declaredZoneNumber !== null) {
        const declaredZone = profile.powerZones.find(
            (zone) => zone.number === declaredZoneNumber,
        );
        if (declaredZone !== undefined) {
            return declaredZone;
        }
    }

    const declaredZoneRange = powerZoneRangeForTarget(step.power, profile);
    if (declaredZoneRange !== null) {
        const firstZone = declaredZoneRange[0];
        const lastZone = declaredZoneRange[1];
        if (lastZone.maxPercent === null) {
            return lastZone;
        }

        const midpoint = (firstZone.minPercent + lastZone.maxPercent) / 2;
        return (
            profile.powerZones.find(
                (zone) =>
                    midpoint > zone.minPercent &&
                    (zone.maxPercent === null || midpoint <= zone.maxPercent),
            ) ?? lastZone
        );
    }

    const percentageRange = stepTargetIntensityRangePercent(step, profile);
    if (isFtpRelativePowerTarget(step.power) && percentageRange !== null) {
        const midpoint = (percentageRange[0] + percentageRange[1]) / 2;
        return (
            profile.powerZones.find(
                (zone) =>
                    midpoint > zone.minPercent &&
                    (zone.maxPercent === null || midpoint <= zone.maxPercent),
            ) ?? (midpoint <= firstZone.minPercent ? firstZone : lastZone)
        );
    }

    const range = stepPowerRangeWatts(step, profile);
    if (range === null) {
        return null;
    }

    const midpoint = (range[0] + range[1]) / 2;
    return (
        profile.powerZones.find(
            (zone) =>
                midpoint >= zone.minWatts &&
                (zone.maxWatts === null || midpoint <= zone.maxWatts),
        ) ?? (midpoint < firstZone.minWatts ? firstZone : lastZone)
    );
}

function stepTargetIntensityRangePercent(
    step: WorkoutStep,
    profile: AthleteProfile | null | undefined,
): [number, number] | null {
    const declaredZoneRange = powerZoneRangeForTarget(step.power, profile);
    if (declaredZoneRange !== null) {
        return [
            declaredZoneRange[0].minPercent,
            declaredZoneRange[1].maxPercent ?? declaredZoneRange[1].minPercent,
        ];
    }

    const target = isFtpRelativePowerTarget(step.power)
        ? step.power
        : (step.resolvedPower ?? step.power);
    if (target === null) {
        return null;
    }

    const start = target.start ?? target.value;
    const end = target.end ?? target.value ?? start;
    if (start === null || end === null) {
        return null;
    }

    if (isFtpRelativePowerTarget(target)) {
        return [start, end];
    }

    if (isPowerZoneTarget(target)) {
        return null;
    }

    const ftpWatts = profile?.indoorFtpWatts ?? profile?.ftpWatts;
    return ftpWatts === null || ftpWatts === undefined || ftpWatts <= 0
        ? null
        : [(start / ftpWatts) * 100, (end / ftpWatts) * 100];
}

function isFtpRelativePowerTarget(target: WorkoutTarget | null): boolean {
    const units = normalizedTargetUnits(target);
    return units === "%ftp" || units === "%offtp" || units === "percentftp";
}

function normalizedTargetUnits(target: WorkoutTarget | null): string | null {
    return target?.units?.trim().toLowerCase().replace(/\s+/g, "") ?? null;
}

function isPowerZoneTarget(target: WorkoutTarget | null): boolean {
    const units = normalizedTargetUnits(target);
    return units === "power_zone" || units === "powerzone";
}

function isExplicitPowerRange(step: WorkoutStep): boolean {
    if (isPowerZoneTarget(step.power)) {
        return true;
    }

    const target = isFtpRelativePowerTarget(step.power)
        ? step.power
        : (step.resolvedPower ?? step.power);
    return (
        target?.start !== null &&
        target?.start !== undefined &&
        target.end !== null &&
        target.end !== undefined &&
        target.start !== target.end
    );
}

function powerZoneNumberForStep(step: WorkoutStep): number | null {
    const target = step.power;
    if (!isPowerZoneTarget(target)) {
        return null;
    }

    const value = target?.value;
    if (value === null || value === undefined || !Number.isFinite(value)) {
        return null;
    }

    const zoneNumber = Math.round(value);
    return zoneNumber > 0 && zoneNumber === value ? zoneNumber : null;
}

export function workoutStepIntensityPercent(
    step: WorkoutStep,
    profile: AthleteProfile | null | undefined,
): number | null {
    const intensityRange = stepTargetIntensityRangePercent(step, profile);
    if (intensityRange === null) {
        return null;
    }

    return step.ramp === true || isPowerZoneTarget(step.power)
        ? Math.max(...intensityRange)
        : (intensityRange[0] + intensityRange[1]) / 2;
}

const previewIntensityScaleFloorPercent = 100;
const freeRidePreviewHeightPercent = 72;

export function workoutIntensityScalePercent(
    steps: WorkoutStep[],
    profile: AthleteProfile | null | undefined,
): number | null {
    const intensities = steps
        .map((step) => workoutStepIntensityPercent(step, profile))
        .filter((intensity): intensity is number => intensity !== null);

    return intensities.length === 0
        ? null
        : Math.max(previewIntensityScaleFloorPercent, ...intensities);
}

export function workoutStepHeight(
    step: WorkoutStep,
    profile: AthleteProfile | null | undefined,
    intensityScalePercent: number | null = null,
): number {
    if (isFreeRideStep(step)) {
        return freeRidePreviewHeightPercent;
    }

    const intensityPercent = workoutStepIntensityPercent(step, profile);
    if (intensityPercent !== null) {
        return intensityScalePercent !== null && intensityScalePercent > 0
            ? (intensityPercent / intensityScalePercent) * 100
            : intensityPercent;
    }

    const zone = powerZoneForStep(step, profile);
    if (zone !== null) {
        return [32, 45, 58, 70, 80, 90, 100][Math.max(0, zone.number - 1) % 7];
    }

    const declaredZoneNumber = powerZoneNumberForStep(step);
    if (declaredZoneNumber !== null) {
        return [32, 45, 58, 70, 80, 90, 100][
            Math.max(0, declaredZoneNumber - 1) % 7
        ];
    }

    return step.resolvedPower === null && step.power === null ? 30 : 52;
}

export function workoutStepRampPath(
    step: WorkoutStep,
    profile: AthleteProfile | null | undefined,
): string | null {
    if (step.ramp !== true) {
        return null;
    }

    const intensityRange = stepTargetIntensityRangePercent(step, profile);
    if (intensityRange === null || intensityRange[0] === intensityRange[1]) {
        return null;
    }

    const startHeight = intensityRange[0];
    const endHeight = intensityRange[1];
    const maxHeight = Math.max(startHeight, endHeight);
    const startTop = 100 - (startHeight / maxHeight) * 100;
    const endTop = 100 - (endHeight / maxHeight) * 100;
    const radius = Math.min(6, (100 - Math.max(startTop, endTop)) / 2);
    const slope = (endTop - startTop) / 100;
    const leftTopY = startTop + slope * radius;
    const rightTopY = endTop - slope * radius;

    return [
        `M 0 ${startTop + radius}`,
        `Q 0 ${startTop} ${radius} ${leftTopY}`,
        `L ${100 - radius} ${rightTopY}`,
        `Q 100 ${endTop} 100 ${endTop + radius}`,
        `L 100 ${100 - radius}`,
        `Q 100 100 ${100 - radius} 100`,
        `L ${radius} 100`,
        `Q 0 100 0 ${100 - radius}`,
        "Z",
    ].join(" ");
}

export function workoutStepFreeRidePath(step: WorkoutStep): string | null {
    if (!isFreeRideStep(step)) {
        return null;
    }

    return [
        "M 0 52",
        "C 10 52 16 39 25 34",
        "C 34 29 39 7 47 9",
        "C 56 11 59 39 68 52",
        "C 78 64 84 37 92 34",
        "C 96 33 98 39 100 44",
        "L 100 94",
        "Q 100 100 94 100",
        "L 6 100",
        "Q 0 100 0 94",
        "Z",
    ].join(" ");
}

export function workoutStepColor(
    step: WorkoutStep,
    index: number,
    profile: AthleteProfile | null | undefined,
): string {
    const power = step.resolvedPower ?? step.power;
    const zone = powerZoneForStep(step, profile);
    if (zone !== null) {
        return powerZoneColors[
            Math.max(0, zone.number - 1) % powerZoneColors.length
        ];
    }

    const declaredZoneNumber = powerZoneNumberForStep(step);
    if (declaredZoneNumber !== null) {
        return powerZoneColors[
            Math.max(0, declaredZoneNumber - 1) % powerZoneColors.length
        ];
    }

    if (isFreeRideStep(step) || power === null) {
        return freeRideColor;
    }

    if (step.warmup) {
        return powerZoneColors[0];
    }
    if (step.cooldown) {
        return freeRideColor;
    }

    const value = power?.start ?? power?.value ?? 0;
    if (value >= 300) {
        return powerZoneColors[5];
    }
    if (value >= 220) {
        return powerZoneColors[4];
    }
    return powerZoneColors[(index + 1) % powerZoneColors.length];
}

export function workoutStepBackground(
    step: WorkoutStep,
    profile: AthleteProfile | null | undefined,
    color: string,
): string | null {
    if (!isExplicitPowerRange(step)) {
        return null;
    }

    const intensityRange = stepTargetIntensityRangePercent(step, profile);
    if (intensityRange === null || intensityRange[0] === intensityRange[1]) {
        return null;
    }

    const lowerPercent = Math.min(...intensityRange);
    const upperPercent = Math.max(...intensityRange);
    if (upperPercent <= 0) {
        return null;
    }

    const lowerTone = toneColor(color, -0.18);
    const upperTone = toneColor(color, 0.18);
    const lowerStop = Math.max(
        0,
        Math.min(100, (lowerPercent / upperPercent) * 100),
    );
    return `linear-gradient(to top, ${lowerTone} 0%, ${lowerTone} ${lowerStop}%, ${upperTone} ${lowerStop}%, ${upperTone} 100%)`;
}

function toneColor(hexColor: string, amount: number): string {
    if (!/^#[0-9a-f]{6}$/i.test(hexColor)) {
        return hexColor;
    }

    const channels = [0, 2, 4].map((offset) =>
        Number.parseInt(hexColor.slice(offset + 1, offset + 3), 16),
    );
    const target = amount >= 0 ? 255 : 0;
    const strength = Math.abs(amount);
    return `#${channels
        .map((channel) =>
            Math.round(channel + (target - channel) * strength)
                .toString(16)
                .padStart(2, "0"),
        )
        .join("")}`;
}

export function workoutSelection(workout: WorkoutItem): WorkoutSelection {
    if ("sourceEventId" in workout) {
        return {
            provider: workout.provider,
            sourceType: "SCHEDULED",
            sourceId: workout.sourceEventId,
        };
    }

    return {
        provider: workout.provider,
        sourceType: "LIBRARY",
        sourceId: workout.sourceWorkoutId,
    };
}

export function workoutName(workout: WorkoutItem): string {
    return workout.name?.trim() || "Untitled workout";
}

function splitDuration(totalSeconds: number): {
    hours: number;
    minutes: number;
    seconds: number;
} {
    const normalizedSeconds = Math.max(0, Math.floor(totalSeconds));

    return {
        hours: Math.floor(normalizedSeconds / 3600),
        minutes: Math.floor((normalizedSeconds % 3600) / 60),
        seconds: normalizedSeconds % 60,
    };
}

export function formatDuration(seconds: number | null): string | null {
    if (seconds === null) {
        return null;
    }

    const duration = splitDuration(seconds);
    const parts: string[] = [];

    if (duration.hours > 0) {
        parts.push(`${duration.hours} h`);
    }
    if (duration.minutes > 0) {
        parts.push(`${duration.minutes} min`);
    }
    if (duration.seconds > 0 || parts.length === 0) {
        parts.push(`${duration.seconds} sec`);
    }

    return parts.join(" ");
}

export function formatCompactDuration(seconds: number | null): string | null {
    if (seconds === null) {
        return null;
    }

    const duration = splitDuration(seconds);
    const parts: string[] = [];

    if (duration.hours > 0) {
        parts.push(`${duration.hours}h`);
    }
    if (duration.minutes > 0) {
        parts.push(`${duration.minutes}m`);
    }
    if (duration.seconds > 0 || parts.length === 0) {
        parts.push(`${duration.seconds}s`);
    }

    return parts.join(" ");
}

export function formatDistance(meters: number | null): string | null {
    if (meters === null || meters <= 0) {
        return null;
    }

    return `${(meters / 1000).toFixed(1)} km`;
}

export function formatTarget(target: WorkoutTarget | null): string | null {
    if (target === null) {
        return null;
    }

    if (isPowerZoneTarget(target)) {
        return formatPowerZoneTarget(target);
    }

    const units = formatTargetUnits(target.units);
    const start = target.start ?? target.value;
    const end = target.end;
    if (start === null && end === null) {
        return units ?? target.target;
    }

    const first = start === null ? null : `${Math.round(start)}`;
    const last = end === null ? null : `${Math.round(end)}`;
    const range =
        first === null
            ? last
            : last === null || first === last
              ? first
              : `${first}–${last}`;

    if (range === null) {
        return units;
    }

    return units?.startsWith("%") === true
        ? `${range}${units}`
        : [range, units].filter(Boolean).join(" ");
}

function formatPowerTarget(
    step: WorkoutStep,
    profile: AthleteProfile | null | undefined,
): string | null {
    const sourceTarget = step.power;
    if (sourceTarget === null) {
        return formatTarget(step.resolvedPower);
    }

    if (isFtpRelativePowerTarget(sourceTarget)) {
        const watts =
            formatTarget(step.resolvedPower) ??
            formatWattsRange(stepPowerRangeWatts(step, profile));
        return joinTargetLabels(formatTarget(sourceTarget), watts);
    }

    if (isPowerZoneTarget(sourceTarget)) {
        const watts =
            formatTarget(step.resolvedPower) ??
            formatPowerZoneWattsTarget(sourceTarget, profile);
        return joinTargetLabels(
            formatTarget(sourceTarget),
            formatPowerZonePercentTarget(sourceTarget, profile),
            watts,
        );
    }

    return formatTarget(sourceTarget);
}

function formatWattsRange(range: [number, number] | null): string | null {
    if (range === null) {
        return null;
    }

    return formatTarget({
        value: null,
        start: range[0],
        end: range[1],
        units: "W",
        target: null,
    });
}

function formatPowerZonePercentTarget(
    target: WorkoutTarget,
    profile: AthleteProfile | null | undefined,
): string | null {
    const zones = powerZoneRangeForTarget(target, profile);
    if (zones === null) {
        return null;
    }

    return formatPercentRange(zones[0].minPercent, zones[1].maxPercent);
}

function formatPowerZoneWattsTarget(
    target: WorkoutTarget,
    profile: AthleteProfile | null | undefined,
): string | null {
    const zones = powerZoneRangeForTarget(target, profile);
    if (zones === null) {
        return null;
    }

    const first = zones[0];
    const last = zones[1];
    if (last.maxWatts === null) {
        return `≥${first.minWatts} W`;
    }

    return formatWattsRange([first.minWatts, last.maxWatts]);
}

function powerZoneRangeForTarget(
    target: WorkoutTarget | null,
    profile: AthleteProfile | null | undefined,
): [PowerZone, PowerZone] | null {
    if (
        !isPowerZoneTarget(target) ||
        profile === null ||
        profile === undefined
    ) {
        return null;
    }

    const numbers = powerZoneNumbersForTarget(target);
    if (numbers === null) {
        return null;
    }

    const first = profile.powerZones.find((zone) => zone.number === numbers[0]);
    const last = profile.powerZones.find((zone) => zone.number === numbers[1]);
    return first === undefined || last === undefined ? null : [first, last];
}

function powerZoneNumbersForTarget(
    target: WorkoutTarget | null,
): [number, number] | null {
    if (target === null || !isPowerZoneTarget(target)) {
        return null;
    }

    const start = target.start ?? target.value ?? target.end;
    const end = target.end ?? target.value ?? target.start;
    if (
        start === null ||
        start === undefined ||
        end === null ||
        end === undefined ||
        !Number.isFinite(start) ||
        !Number.isFinite(end)
    ) {
        return null;
    }

    const first = Math.round(Math.min(start, end));
    const last = Math.round(Math.max(start, end));
    return first > 0 &&
        first === Math.min(start, end) &&
        last === Math.max(start, end)
        ? [first, last]
        : null;
}

function formatPercentRange(
    minPercent: number,
    maxPercent: number | null,
): string {
    const first = Math.round(minPercent);
    if (maxPercent === null) {
        return `${first}% FTP+`;
    }

    const last = Math.round(maxPercent);
    return first === last ? `${first}% FTP` : `${first}–${last}% FTP`;
}

function joinTargetLabels(...labels: Array<string | null>): string | null {
    const uniqueLabels = labels.filter(
        (label, index, all): label is string =>
            label !== null && all.indexOf(label) === index,
    );
    return uniqueLabels.length === 0 ? null : uniqueLabels.join(" · ");
}

function formatPowerZoneTarget(target: WorkoutTarget): string {
    const start = target.start ?? target.value;
    const end = target.end;
    if (start === null && end === null) {
        return target.target ?? "Power zone";
    }

    const first = start === null ? null : `Z${Math.round(start)}`;
    const last = end === null ? null : `Z${Math.round(end)}`;
    if (first === null) {
        return last ?? "Power zone";
    }
    return last === null || first === last ? first : `${first}–${last}`;
}

function formatTargetUnits(units: string | null): string | null {
    const trimmed = units?.trim();
    const normalized = trimmed?.toLowerCase().replaceAll(" ", "");
    if (normalized === undefined || normalized === "") {
        return null;
    }

    if (
        normalized === "%ftp" ||
        normalized === "%offtp" ||
        normalized === "percentftp"
    ) {
        return "% FTP";
    }

    if (normalized === "w" || normalized === "watt" || normalized === "watts") {
        return "W";
    }

    return trimmed ?? null;
}

export function stepTarget(
    step: WorkoutStep,
    profile: AthleteProfile | null | undefined = null,
): string | null {
    const target =
        formatPowerTarget(step, profile) ??
        formatTarget(step.heartRate ?? step.pace ?? step.cadence);
    if (target === null && isFreeRideStep(step)) {
        return "Free Ride";
    }
    return step.ramp === true && target !== null ? `Ramp ${target}` : target;
}

export function stepLabel(step: WorkoutStep): string | null {
    return step.text?.trim() || step.intensity?.trim() || null;
}

export function stepDuration(step: WorkoutStep): number | null {
    if (step.durationSeconds !== null) {
        return step.durationSeconds;
    }

    if (step.distanceMeters !== null) {
        return step.distanceMeters;
    }

    return null;
}

export function stepDurationLabel(step: WorkoutStep): string | null {
    if (step.durationSeconds !== null) {
        return formatDuration(step.durationSeconds);
    }
    if (step.distanceMeters !== null) {
        return formatDistance(step.distanceMeters);
    }
    return null;
}

export function stepSummary(
    step: WorkoutStep,
    profile: AthleteProfile | null | undefined = null,
): string | null {
    const duration = stepDurationLabel(step);
    const target = stepTarget(step, profile);
    const repeats = step.repeats === null ? null : `${step.repeats} repeats`;

    if (duration !== null && target !== null) {
        return [`${duration} @ ${target}`, repeats].filter(Boolean).join(" · ");
    }

    return [duration, target, repeats].filter(Boolean).join(" · ") || null;
}

export function workoutSteps(workout: WorkoutItem): WorkoutStep[] {
    return flattenWorkoutSteps(workout.workout?.steps ?? []);
}

export function workoutMetaItems(workout: WorkoutItem): Array<{
    label: string;
    kind: "type" | "distance";
}> {
    const items = [
        { label: workout.type, kind: "type" as const },
        {
            label: formatDistance(workout.distanceMeters),
            kind: "distance" as const,
        },
    ];

    return items.filter(
        (item): item is { label: string; kind: typeof item.kind } =>
            Boolean(item.label),
    );
}

export function isWorkoutDefinition(
    value: WorkoutItem | WorkoutDefinition,
): value is WorkoutDefinition {
    return !("provider" in value);
}
