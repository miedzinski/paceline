import { HeartPulse, LoaderCircle, Radio, Zap } from "lucide-react";
import type { AppShellContextValue } from "@/lib/app-shell";
import { transportLabel } from "@/lib/connection";
import type { WorkoutItem } from "@/lib/workouts";
import { cn } from "@/lib/utils";
import { WorkoutProfile } from "@/components/workout-card";
import type { HeartRateSource } from "@/types";
import { ErrorNotice } from "./ride-status";
import { RideHeader } from "./ride-header";

export function PreRideView({
    connection,
    connectedSources,
    hasErgControl,
    isStarting,
    selectedHeartRateSourceId,
    selectedWorkout,
    athleteProfile,
    error,
    onLogoClick,
    onOpenEquipment,
    onSelectHeartRateSource,
    onStart,
}: {
    connection: AppShellContextValue["connection"];
    connectedSources: HeartRateSource[];
    hasErgControl: boolean;
    isStarting: boolean;
    selectedHeartRateSourceId: string | null;
    selectedWorkout: WorkoutItem | null;
    athleteProfile: AppShellContextValue["profile"];
    error: string | null;
    onLogoClick: () => void;
    onOpenEquipment: () => void;
    onSelectHeartRateSource: (sourceId: string) => void;
    onStart: () => void;
}) {
    const trainer = connection.connections.find(
        (item) =>
            item.state === "CONNECTED" &&
            item.capabilities.includes("INDOOR_BIKE_TELEMETRY"),
    );

    return (
        <div className="min-h-[100svh] bg-[#090c12] px-4 pt-[calc(0.9rem+env(safe-area-inset-top))] pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-[#f5f6fb] sm:px-6 lg:px-8">
            <div className="mx-auto max-w-[1250px]">
                <RideHeader
                    connection={connection}
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
                        <div className="mt-6 grid gap-2.5">
                            <ReadinessRow
                                icon={Radio}
                                label="Trainer"
                                value={
                                    trainer
                                        ? `${trainer.device.name} · ${transportLabel(trainer.device.transport)}`
                                        : "Not connected"
                                }
                                ready={hasErgControl}
                                onClick={onOpenEquipment}
                            />
                            <ReadinessRow
                                icon={HeartPulse}
                                label="Heart rate"
                                value={
                                    connectedSources.length === 0
                                        ? "Optional · none connected"
                                        : connectedSources.length === 1
                                          ? connectedSources[0].device.name
                                          : `${connectedSources.length} sources available`
                                }
                                ready={
                                    connectedSources.length <= 1 ||
                                    selectedHeartRateSourceId !== null
                                }
                                onClick={
                                    connectedSources.length > 1
                                        ? undefined
                                        : onOpenEquipment
                                }
                            />
                        </div>

                        {connectedSources.length > 1 ? (
                            <label className="mt-4 block">
                                <span className="mb-2 block text-[0.6rem] font-bold tracking-[0.18em] text-white/30 uppercase">
                                    Choose heart-rate source
                                </span>
                                <select
                                    value={selectedHeartRateSourceId ?? ""}
                                    onChange={(event) =>
                                        onSelectHeartRateSource(
                                            event.target.value,
                                        )
                                    }
                                    className="min-h-12 w-full rounded-2xl border border-white/[0.1] bg-[#0d1017] px-3 text-sm font-semibold text-white outline-none focus:border-[#7e87ff] focus:ring-2 focus:ring-[#7e87ff]/20"
                                >
                                    <option value="">Select a source</option>
                                    {connectedSources.map((source) => (
                                        <option
                                            key={source.id}
                                            value={source.id}
                                        >
                                            {source.device.name}
                                        </option>
                                    ))}
                                </select>
                            </label>
                        ) : null}

                        {error ? <ErrorNotice message={error} /> : null}

                        <button
                            type="button"
                            onClick={onStart}
                            disabled={isStarting}
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
                            {isStarting
                                ? "Starting ride…"
                                : hasErgControl
                                  ? "Start ride"
                                  : "Connect trainer"}
                        </button>
                    </section>
                </div>
            </div>
        </div>
    );
}

export function RideStartingView({
    connection,
    onLogoClick,
    onOpenEquipment,
}: {
    connection: AppShellContextValue["connection"];
    onLogoClick: () => void;
    onOpenEquipment: () => void;
}) {
    return (
        <div className="min-h-[100svh] bg-[#090c12] px-4 pt-[calc(0.9rem+env(safe-area-inset-top))] pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-[#f5f6fb] sm:px-6 lg:px-8">
            <div className="mx-auto max-w-[1250px]">
                <RideHeader
                    connection={connection}
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
                            Starting ride…
                        </p>
                        <p className="mt-2 text-sm text-white/45">
                            Connecting to your trainer and preparing the
                            workout.
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
