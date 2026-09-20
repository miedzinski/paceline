import {
    Gauge,
    HeartPulse,
    LoaderCircle,
    Radio,
    Settings2,
    Zap,
} from "lucide-react";
import type { AppShellContextValue } from "@/lib/app-shell";
import {
    rideRoleLabel as roleLabel,
    roleStatusLabel,
    sourceName,
    visibleReadinessReasons,
} from "@/lib/ride-equipment";
import type { WorkoutItem } from "@/lib/workouts";
import { cn } from "@/lib/utils";
import { WorkoutProfile } from "@/components/workout-card";
import type { RideEquipmentResponse, RideRole, RideRoleState } from "@/types";
import { ErrorNotice } from "./ride-status";
import { RideHeader } from "./ride-header";

export function PreRideView({
    connection,
    equipment,
    equipmentError,
    equipmentLoading,
    hasErgControl,
    isStarting,
    selectedWorkout,
    athleteProfile,
    error,
    onLogoClick,
    onOpenEquipment,
    onStart,
}: {
    connection: AppShellContextValue["connection"];
    equipment: RideEquipmentResponse | null;
    equipmentError: string | null;
    equipmentLoading: boolean;
    hasErgControl: boolean;
    isStarting: boolean;
    selectedWorkout: WorkoutItem | null;
    athleteProfile: AppShellContextValue["profile"];
    error: string | null;
    onLogoClick: () => void;
    onOpenEquipment: () => void;
    onStart: () => void;
}) {
    const startLabel = equipmentLoading
        ? "Checking equipment…"
        : equipment === null
          ? "Review equipment"
          : !hasErgControl
            ? "Connect trainer"
            : !equipment.ready
              ? "Review equipment"
              : "Start ride";
    const readinessReasons =
        equipment === null ? [] : visibleReadinessReasons(equipment);

    return (
        <div className="min-h-[100svh] bg-[#090c12] px-4 pt-[calc(0.9rem+env(safe-area-inset-top))] pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-[#f5f6fb] sm:px-6 lg:px-8">
            <div className="mx-auto max-w-[1250px]">
                <RideHeader
                    connection={connection}
                    equipment={equipment}
                    onLogoClick={onLogoClick}
                    onOpenEquipment={onOpenEquipment}
                />

                <div className="mt-7 grid gap-4 lg:grid-cols-[minmax(0,1.15fr)_minmax(20rem,0.85fr)]">
                    <section className="relative overflow-hidden rounded-[2.25rem] border border-[#5d68d9]/35 bg-[linear-gradient(140deg,#181d35,#141924_58%,#241923)] p-6 shadow-[0_28px_80px_rgba(0,0,0,0.28)] sm:p-9">
                        <div className="pointer-events-none absolute -top-28 -right-16 size-72 rounded-full bg-[#7e87ff]/15 blur-3xl" />
                        <div className="relative">
                            <p className="flex items-center gap-2 text-[0.62rem] font-bold tracking-[0.2em] text-[#aeb4ff] uppercase">
                                <Zap aria-hidden="true" className="size-3.5" />
                                Session setup
                            </p>
                            <h1 className="mt-5 max-w-2xl text-4xl leading-[0.95] font-black tracking-[-0.09em] text-white sm:text-6xl">
                                {selectedWorkout?.name?.trim() || "Free ride"}
                            </h1>
                            <p className="mt-5 max-w-xl text-sm leading-6 text-white/48 sm:text-base sm:leading-7">
                                {selectedWorkout
                                    ? "Connect your trainer and start when you are ready."
                                    : "A blank canvas for an easy spin or a target of your choosing."}
                            </p>
                            {selectedWorkout ? (
                                <div className="mt-8 max-w-2xl">
                                    <WorkoutProfile
                                        workout={selectedWorkout}
                                        athleteProfile={athleteProfile}
                                    />
                                </div>
                            ) : null}
                        </div>
                    </section>

                    <section className="rounded-[2.25rem] border border-white/[0.1] bg-[#141821] p-5 sm:p-7">
                        <div>
                            <p className="text-[0.62rem] font-bold tracking-[0.2em] text-white/30 uppercase">
                                Readiness
                            </p>
                            <h2 className="mt-2 text-2xl font-black tracking-[-0.06em] text-white">
                                Check your connections.
                            </h2>
                        </div>
                        {equipment !== null ? (
                            <div className="mt-6 grid gap-2.5">
                                {equipment.roles.map((role) => (
                                    <RideRoleSummary
                                        key={role.role}
                                        equipment={equipment}
                                        role={role}
                                        onClick={onOpenEquipment}
                                    />
                                ))}
                                {readinessReasons.length > 0 ? (
                                    <div className="rounded-2xl border border-[#806335] bg-[#2b2418] p-3 text-xs leading-5 text-[#f5d28c]">
                                        {readinessReasons.map((reason) => (
                                            <p
                                                key={`${reason.code}-${reason.role}`}
                                            >
                                                {reason.message}
                                            </p>
                                        ))}
                                    </div>
                                ) : null}
                            </div>
                        ) : (
                            <div className="mt-6 grid gap-2.5">
                                <ReadinessRow
                                    icon={Radio}
                                    label="Ride equipment"
                                    value={
                                        equipmentLoading
                                            ? "Checking connected sources…"
                                            : (equipmentError ??
                                              "Equipment setup unavailable")
                                    }
                                    ready={false}
                                    onClick={onOpenEquipment}
                                />
                            </div>
                        )}

                        {error ? <ErrorNotice message={error} /> : null}

                        <button
                            type="button"
                            onClick={onStart}
                            disabled={isStarting || equipmentLoading}
                            className="mt-7 inline-flex min-h-14 w-full items-center justify-center gap-2 rounded-2xl bg-[#7e87ff] px-5 text-sm font-black text-[#0b0d14] shadow-[0_15px_35px_rgba(126,135,255,0.18)] transition hover:bg-[#aeb4ff] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-60"
                        >
                            {isStarting ? (
                                <LoaderCircle
                                    aria-hidden="true"
                                    className="size-4 animate-spin"
                                />
                            ) : (
                                <Zap
                                    aria-hidden="true"
                                    className="size-4 fill-current"
                                />
                            )}
                            {isStarting ? "Starting ride…" : startLabel}
                        </button>
                    </section>
                </div>
            </div>
        </div>
    );
}

export function RideStartingView({
    connection,
    equipment,
    isChecking,
    onLogoClick,
    onOpenEquipment,
}: {
    connection: AppShellContextValue["connection"];
    equipment: RideEquipmentResponse | null;
    isChecking: boolean;
    onLogoClick: () => void;
    onOpenEquipment: () => void;
}) {
    return (
        <div className="min-h-[100svh] bg-[#090c12] px-4 pt-[calc(0.9rem+env(safe-area-inset-top))] pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-[#f5f6fb] sm:px-6 lg:px-8">
            <div className="mx-auto max-w-[1250px]">
                <RideHeader
                    connection={connection}
                    equipment={equipment}
                    onLogoClick={onLogoClick}
                    onOpenEquipment={onOpenEquipment}
                />
                <main className="mt-7 grid min-h-[calc(100svh-8rem)] place-items-center rounded-[2.25rem] border border-white/[0.1] bg-[#141821] p-8 text-center">
                    <div>
                        <LoaderCircle
                            aria-hidden="true"
                            className="mx-auto size-8 animate-spin text-[#aeb4ff]"
                        />
                        <p className="mt-5 text-2xl font-black tracking-[-0.06em] text-white">
                            {isChecking
                                ? "Checking equipment…"
                                : "Starting ride…"}
                        </p>
                        <p className="mt-2 text-sm text-white/45">
                            {isChecking
                                ? "Checking for a connected resistance trainer."
                                : "Connecting to your trainer and preparing the workout."}
                        </p>
                    </div>
                </main>
            </div>
        </div>
    );
}

function ReadinessRow({
    icon: Icon,
    label,
    value,
    ready,
    onClick,
}: {
    icon: typeof Radio;
    label: string;
    value: string;
    ready: boolean;
    onClick?: () => void;
}) {
    const content = (
        <>
            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-white/[0.06] text-white/45">
                <Icon aria-hidden="true" className="size-4" />
            </span>
            <span className="min-w-0 flex-1 text-left">
                <span className="block text-xs font-bold text-white/45">
                    {label}
                </span>
                <span className="mt-1 block truncate text-sm font-bold text-white/80">
                    {value}
                </span>
            </span>
            <span
                className={cn(
                    "size-2 shrink-0 rounded-full",
                    ready ? "bg-[#73d6a1]" : "bg-[#f0b766]",
                )}
            />
        </>
    );

    if (onClick === undefined) {
        return (
            <div className="flex items-center gap-3 rounded-2xl border border-white/[0.08] bg-white/[0.025] p-3">
                {content}
            </div>
        );
    }

    return (
        <button
            type="button"
            onClick={onClick}
            className="flex w-full items-center gap-3 rounded-2xl border border-white/[0.08] bg-white/[0.025] p-3 transition-colors hover:border-white/[0.18] hover:bg-white/[0.06] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
        >
            {content}
        </button>
    );
}

function RoleIcon({ role }: { role: RideRole }) {
    switch (role) {
        case "RESISTANCE_CONTROL":
            return <Settings2 aria-hidden="true" className="size-4" />;
        case "POWER":
            return <Zap aria-hidden="true" className="size-4" />;
        case "CADENCE":
            return <Gauge aria-hidden="true" className="size-4" />;
        case "HEART_RATE":
            return <HeartPulse aria-hidden="true" className="size-4" />;
    }
}

function RideRoleSummary({
    equipment,
    role,
    onClick,
}: {
    equipment: RideEquipmentResponse;
    role: RideRoleState;
    onClick: () => void;
}) {
    return (
        <button
            type="button"
            onClick={onClick}
            aria-label={"Open equipment for " + roleLabel(role.role)}
            className="flex w-full items-center gap-3 rounded-2xl border border-white/[0.08] bg-white/[0.025] p-3 text-left transition-colors hover:border-white/[0.18] hover:bg-white/[0.06] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
        >
            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-white/[0.06] text-white/45">
                <RoleIcon role={role.role} />
            </span>
            <span className="min-w-0 flex-1">
                <span className="block text-xs font-bold text-white/45">
                    {roleLabel(role.role)}
                </span>
                <span className="mt-1 block truncate text-sm font-bold text-white/80">
                    {role.sourceId === null
                        ? roleStatusLabel(role)
                        : (sourceName(equipment, role.sourceId) ??
                          roleStatusLabel(role))}
                </span>
            </span>
            <span
                className={cn(
                    "size-2 shrink-0 rounded-full",
                    role.status === "SELECTED"
                        ? "bg-[#73d6a1]"
                        : role.role === "RESISTANCE_CONTROL"
                          ? "bg-[#ff8068]"
                          : "bg-[#f0b766]",
                )}
            />
        </button>
    );
}
