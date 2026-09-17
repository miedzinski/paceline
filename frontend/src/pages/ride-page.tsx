import {
    ArrowLeft,
    Bluetooth,
    Check,
    ChevronRight,
    CircleAlert,
    Clock3,
    FastForward,
    HeartPulse,
    LoaderCircle,
    Pause,
    Play,
    Radio,
    Send,
    Square,
    Upload,
    WifiOff,
    X,
    Zap,
} from "lucide-react";
import {
    useCallback,
    useEffect,
    useMemo,
    useRef,
    useState,
    type FormEvent,
} from "react";
import { useLocation, useNavigate } from "react-router";
import { ApiError, trainingApi } from "@/api";
import { TelemetryChart, type RidePoint } from "@/components/telemetry-chart";
import { WorkoutProfile } from "@/components/workout-card";
import { WorkoutTimeline } from "@/components/workout-timeline";
import { useAppShell } from "@/lib/app-shell";
import { formatElapsed, transportLabel } from "@/lib/connection";
import type { WorkoutItem } from "@/lib/workouts";
import { cn } from "@/lib/utils";
import type {
    HeartRateSource,
    TrainingActivityUploadResponse,
    TrainingSessionResponse,
    WorkoutDefinition,
    WorkoutSelection,
} from "@/types";

const sessionPollIntervalMs = 1_000;
const minimumFtmsPowerWatts = -32_768;
const maximumFtmsPowerWatts = 32_767;

interface RideLocationState {
    workoutSelection?: WorkoutSelection | null;
    workout?: WorkoutItem | null;
}

function createNotStartedSession(): TrainingSessionResponse {
    return {
        state: "NOT_STARTED",
        sessionId: null,
        startedAt: null,
        changedAt: new Date().toISOString(),
        controlMode: "ERG",
        ergRequestedTargetPowerWatts: null,
        ergTargetPowerWatts: null,
        workoutPowerTargetPercent: null,
        ergProtection: {
            state: "INACTIVE",
            changedAt: null,
            cadenceRpm: null,
            error: null,
            retryAttempt: null,
            nextRetryAt: null,
        },
        trainerConnection: "NOT_ACTIVE",
        trainerConnectionRetryAttempt: null,
        trainerConnectionError: null,
        heartRateSourceId: null,
        heartRate: null,
        workout: null,
        activityUpload: {
            state: "UNAVAILABLE",
            remoteActivityId: null,
            error: null,
        },
    };
}

function displayError(error: unknown): string {
    if (error instanceof ApiError) {
        return error.message;
    }

    if (error instanceof Error) {
        return error.message;
    }

    return "The Paceline backend could not be reached";
}

function formatMetric(value: number | null, fractionDigits = 0): string {
    if (value === null) {
        return "—";
    }

    return new Intl.NumberFormat(undefined, {
        maximumFractionDigits: fractionDigits,
        minimumFractionDigits: fractionDigits,
    }).format(value);
}

function activeWorkoutDefinition(
    selectedWorkout: WorkoutItem | null,
): WorkoutDefinition | null {
    return selectedWorkout?.workout ?? null;
}

function connectedHeartRateSources(
    sources: HeartRateSource[],
): HeartRateSource[] {
    return sources.filter((source) => source.state === "CONNECTED");
}

export function RidePage() {
    const navigate = useNavigate();
    const location = useLocation();
    const { connection, openEquipment, profile } = useAppShell();
    const routeState = (location.state as RideLocationState | null) ?? null;
    const selectedWorkout = routeState?.workout ?? null;
    const workoutSelection = routeState?.workoutSelection ?? null;

    const [session, setSession] = useState<TrainingSessionResponse>(
        createNotStartedSession,
    );
    const [trace, setTrace] = useState<RidePoint[]>([]);
    const [now, setNow] = useState(0);
    const [targetInput, setTargetInput] = useState("150");
    const [pendingHeartRateSourceId, setPendingHeartRateSourceId] = useState<
        string | null
    >(null);
    const [isStarting, setIsStarting] = useState(false);
    const [isPausing, setIsPausing] = useState(false);
    const [isResuming, setIsResuming] = useState(false);
    const [isStopping, setIsStopping] = useState(false);
    const [isAdvancing, setIsAdvancing] = useState(false);
    const [isSettingTarget, setIsSettingTarget] = useState(false);
    const [isAdjustingWorkoutTarget, setIsAdjustingWorkoutTarget] =
        useState(false);
    const [isUploading, setIsUploading] = useState(false);
    const [isDiscarding, setIsDiscarding] = useState(false);
    const [stopPromptOpen, setStopPromptOpen] = useState(false);
    const [postRideOpen, setPostRideOpen] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const sessionRefreshGeneration = useRef(0);
    const liveTelemetry =
        session.trainerConnection === "CONNECTED" ? connection.telemetry : null;

    const refreshSession = useCallback(async () => {
        const requestGeneration = ++sessionRefreshGeneration.current;
        try {
            const nextSession = await trainingApi.getCurrent();
            if (requestGeneration !== sessionRefreshGeneration.current) {
                return;
            }
            setSession(nextSession);
            if (nextSession.state === "STOPPED") {
                setPostRideOpen(true);
            } else if (nextSession.state === "NOT_STARTED") {
                setPostRideOpen(false);
            }
            const requestedTarget =
                nextSession.controlMode === "FREE_RIDE"
                    ? null
                    : (nextSession.ergRequestedTargetPowerWatts ??
                      nextSession.ergTargetPowerWatts);
            if (requestedTarget !== null) {
                setTargetInput(String(requestedTarget));
            }
        } catch (refreshError) {
            setError(displayError(refreshError));
        }
    }, []);

    useEffect(() => {
        const initialRefresh = window.setTimeout(
            () => void refreshSession(),
            0,
        );
        const pollHandle = window.setInterval(
            () => void refreshSession(),
            sessionPollIntervalMs,
        );
        return () => {
            window.clearTimeout(initialRefresh);
            window.clearInterval(pollHandle);
        };
    }, [refreshSession]);

    useEffect(() => {
        if (
            session.state !== "ACTIVE" &&
            session.state !== "PAUSED" &&
            session.state !== "STOPPED"
        ) {
            return;
        }

        const initialNow = window.setTimeout(() => setNow(Date.now()), 0);
        const timerHandle =
            session.state === "ACTIVE"
                ? window.setInterval(() => setNow(Date.now()), 1_000)
                : null;
        return () => {
            window.clearTimeout(initialNow);
            if (timerHandle !== null) {
                window.clearInterval(timerHandle);
            }
        };
    }, [session.state]);

    useEffect(() => {
        if (session.state !== "ACTIVE" && session.state !== "STOPPED") {
            return;
        }

        const telemetry = liveTelemetry;
        const heartRate = session.heartRate;
        if (telemetry === null && heartRate === null) {
            return;
        }

        const telemetryTime =
            telemetry === null ? 0 : Date.parse(telemetry.receivedAt);
        const heartRateTime =
            heartRate === null ? 0 : Date.parse(heartRate.receivedAt);
        const sampleTimes = [telemetryTime, heartRateTime].filter(
            (timestamp) => Number.isFinite(timestamp) && timestamp > 0,
        );
        const timestamp =
            sampleTimes.length > 0 ? Math.max(...sampleTimes) : Date.now();
        const updateHandle = window.setTimeout(() => {
            setTrace((current) => {
                const point: RidePoint = {
                    timestamp,
                    powerWatts: telemetry?.powerWatts ?? null,
                    cadenceRpm: telemetry?.cadenceRpm ?? null,
                    speedKph: telemetry?.speedKph ?? null,
                    heartRateBpm: heartRate?.heartRateBpm ?? null,
                };
                const lastPoint = current.at(-1);
                if (lastPoint?.timestamp === timestamp) {
                    return [
                        ...current.slice(0, -1),
                        {
                            ...lastPoint,
                            ...point,
                            powerWatts:
                                point.powerWatts ?? lastPoint.powerWatts,
                            cadenceRpm:
                                point.cadenceRpm ?? lastPoint.cadenceRpm,
                            speedKph: point.speedKph ?? lastPoint.speedKph,
                            heartRateBpm:
                                point.heartRateBpm ?? lastPoint.heartRateBpm,
                        },
                    ];
                }
                return [...current, point].slice(-600);
            });
        }, 0);
        return () => window.clearTimeout(updateHandle);
    }, [liveTelemetry, session.heartRate, session.state]);

    const connectedSources = useMemo(
        () => connectedHeartRateSources(connection.heartRateSources),
        [connection.heartRateSources],
    );
    const trainer = connection.connections.find(
        (item) =>
            item.state === "CONNECTED" &&
            item.capabilities.includes("INDOOR_BIKE_TELEMETRY"),
    );
    const hasErgControl =
        trainer?.capabilities.includes("ERG_POWER_CONTROL") ?? false;
    const selectedHeartRateSourceId =
        session.state === "ACTIVE" || session.state === "PAUSED"
            ? session.heartRateSourceId
            : (pendingHeartRateSourceId ??
              (connectedSources.length === 1 ? connectedSources[0].id : null));
    const definition = activeWorkoutDefinition(selectedWorkout);
    const isPreparingNewWorkout = workoutSelection !== null;
    const isActive = session.state === "ACTIVE";
    const isPaused = session.state === "PAUSED";
    const isStopped = session.state === "STOPPED";
    const currentPower = liveTelemetry?.powerWatts ?? null;
    const requestedTarget =
        session.controlMode === "FREE_RIDE"
            ? null
            : (session.ergRequestedTargetPowerWatts ??
              session.ergTargetPowerWatts);
    const appliedTarget = session.ergTargetPowerWatts;
    const powerProgress =
        currentPower === null ||
        requestedTarget === null ||
        requestedTarget <= 0
            ? 0
            : Math.min(
                  100,
                  Math.max(
                      3,
                      (currentPower / Math.max(1, requestedTarget)) * 100,
                  ),
              );

    const startSession = useCallback(async () => {
        if (!hasErgControl) {
            openEquipment();
            return;
        }

        if (connectedSources.length > 1 && selectedHeartRateSourceId === null) {
            setError("Choose a heart-rate source before starting the ride");
            return;
        }

        setIsStarting(true);
        setError(null);
        sessionRefreshGeneration.current += 1;
        setTrace([]);
        setNow(0);
        try {
            const result = await trainingApi.start(
                workoutSelection ?? undefined,
                selectedHeartRateSourceId ?? undefined,
            );
            sessionRefreshGeneration.current += 1;
            setSession(result);
            setPendingHeartRateSourceId(result.heartRateSourceId);
            setTargetInput(
                String(
                    result.ergRequestedTargetPowerWatts ??
                        result.ergTargetPowerWatts ??
                        150,
                ),
            );
        } catch (startError) {
            setError(displayError(startError));
        } finally {
            setIsStarting(false);
        }
    }, [
        connectedSources.length,
        hasErgControl,
        openEquipment,
        selectedHeartRateSourceId,
        workoutSelection,
    ]);

    const pauseSession = useCallback(async () => {
        if (session.sessionId === null) {
            return;
        }

        setIsPausing(true);
        setError(null);
        try {
            setSession(await trainingApi.pause(session.sessionId));
        } catch (pauseError) {
            setError(displayError(pauseError));
        } finally {
            setIsPausing(false);
        }
    }, [session.sessionId]);

    const resumeSession = useCallback(async () => {
        if (session.sessionId === null) {
            return;
        }

        setIsResuming(true);
        setError(null);
        try {
            setSession(await trainingApi.resume(session.sessionId));
        } catch (resumeError) {
            setError(displayError(resumeError));
        } finally {
            setIsResuming(false);
        }
    }, [session.sessionId]);

    const stopSession = useCallback(async () => {
        if (session.sessionId === null) {
            return;
        }

        setIsStopping(true);
        setError(null);
        try {
            const result = await trainingApi.stop(session.sessionId);
            setSession(result);
            setStopPromptOpen(false);
            setPostRideOpen(true);
        } catch (stopError) {
            setError(displayError(stopError));
        } finally {
            setIsStopping(false);
        }
    }, [session.sessionId]);

    const advanceStep = useCallback(async () => {
        if (session.sessionId === null) {
            return;
        }

        setIsAdvancing(true);
        setError(null);
        try {
            setSession(await trainingApi.advance(session.sessionId));
        } catch (advanceError) {
            setError(displayError(advanceError));
        } finally {
            setIsAdvancing(false);
        }
    }, [session.sessionId]);

    const setManualTarget = useCallback(
        async (event: FormEvent<HTMLFormElement>) => {
            event.preventDefault();
            if (session.sessionId === null) {
                return;
            }

            const powerWatts = Number(targetInput.trim());
            if (
                !Number.isInteger(powerWatts) ||
                powerWatts < minimumFtmsPowerWatts ||
                powerWatts > maximumFtmsPowerWatts
            ) {
                setError(
                    `Target must be an integer from ${minimumFtmsPowerWatts} to ${maximumFtmsPowerWatts} W`,
                );
                return;
            }

            setIsSettingTarget(true);
            setError(null);
            try {
                const result = await trainingApi.setErgTarget(
                    session.sessionId,
                    powerWatts,
                );
                setSession(result);
                setTargetInput(
                    String(
                        result.ergRequestedTargetPowerWatts ??
                            result.ergTargetPowerWatts ??
                            powerWatts,
                    ),
                );
            } catch (targetError) {
                setError(displayError(targetError));
            } finally {
                setIsSettingTarget(false);
            }
        },
        [session.sessionId, targetInput],
    );

    const adjustWorkoutTarget = useCallback(
        async (deltaPercent: number) => {
            if (
                session.sessionId === null ||
                !isActive ||
                session.workout === null ||
                session.workout.completed ||
                session.ergProtection.state === "UNAVAILABLE" ||
                session.ergProtection.state === "RECOVERY_FAILED"
            ) {
                return;
            }

            setIsAdjustingWorkoutTarget(true);
            setError(null);
            try {
                setSession(
                    await trainingApi.adjustWorkoutTarget(
                        session.sessionId,
                        deltaPercent,
                    ),
                );
            } catch (adjustmentError) {
                setError(displayError(adjustmentError));
            } finally {
                setIsAdjustingWorkoutTarget(false);
            }
        },
        [
            isActive,
            session.ergProtection.state,
            session.sessionId,
            session.workout,
        ],
    );

    const selectHeartRateSource = useCallback(
        async (sourceId: string) => {
            if (sourceId === "") {
                return;
            }

            setPendingHeartRateSourceId(sourceId);
            if (!isActive || session.sessionId === null) {
                return;
            }

            try {
                setSession(
                    await trainingApi.selectHeartRateSource(
                        session.sessionId,
                        sourceId,
                    ),
                );
            } catch (selectionError) {
                setError(displayError(selectionError));
            }
        },
        [isActive, session.sessionId],
    );

    const uploadActivity = useCallback(async () => {
        if (session.sessionId === null) {
            return;
        }

        setIsUploading(true);
        setError(null);
        try {
            const result = await trainingApi.upload(session.sessionId);
            setSession(result);
            setPostRideOpen(false);
            navigate("/");
        } catch (uploadError) {
            setError(displayError(uploadError));
        } finally {
            setIsUploading(false);
        }
    }, [navigate, session.sessionId]);

    const discardActivity = useCallback(async () => {
        if (session.sessionId === null) {
            navigate("/");
            return;
        }

        setIsDiscarding(true);
        setError(null);
        sessionRefreshGeneration.current += 1;
        try {
            const result = await trainingApi.discard(session.sessionId);
            sessionRefreshGeneration.current += 1;
            setSession(result);
            setPostRideOpen(false);
            navigate("/");
        } catch (discardError) {
            setError(displayError(discardError));
        } finally {
            setIsDiscarding(false);
        }
    }, [navigate, session.sessionId]);

    const rideName =
        selectedWorkout?.name?.trim() || session.workout?.name || "Free ride";

    if (
        !isActive &&
        !isPaused &&
        (!isStopped || (isPreparingNewWorkout && !postRideOpen))
    ) {
        return (
            <PreRideView
                connection={connection}
                connectedSources={connectedSources}
                hasErgControl={hasErgControl}
                isStarting={isStarting}
                selectedHeartRateSourceId={selectedHeartRateSourceId}
                selectedWorkout={selectedWorkout}
                athleteProfile={profile}
                error={error}
                onBack={() => navigate("/")}
                onOpenEquipment={openEquipment}
                onSelectHeartRateSource={(sourceId) =>
                    void selectHeartRateSource(sourceId)
                }
                onStart={() => void startSession()}
            />
        );
    }

    return (
        <div className="min-h-[100svh] bg-[#090c12] px-4 pt-[calc(0.9rem+env(safe-area-inset-top))] pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-[#f5f6fb] sm:px-6 lg:px-8">
            <div className="mx-auto max-w-[1200px]">
                <RideHeader
                    eyebrow={
                        isStopped
                            ? "Ride ended"
                            : isPaused
                              ? "Ride paused"
                              : "Live ride"
                    }
                    title={rideName}
                    trainerName={trainer?.device.name ?? null}
                    onBack={() => navigate("/")}
                    onOpenEquipment={openEquipment}
                />

                <ConnectionBanner
                    hasTrainer={trainer !== undefined}
                    telemetryAvailable={liveTelemetry !== null}
                    status={session.trainerConnection}
                    retryAttempt={session.trainerConnectionRetryAttempt}
                    error={session.trainerConnectionError}
                    onOpenEquipment={openEquipment}
                />

                <ErgProtectionBanner protection={session.ergProtection} />

                <div className="mt-6 grid gap-4 xl:grid-cols-12">
                    <div className="xl:col-span-7">
                        <WorkoutTimeline
                            now={now}
                            definition={definition}
                            workout={session.workout}
                            athleteProfile={profile}
                            paused={isPaused}
                        />
                    </div>

                    <div className="xl:col-span-5">
                        <MetricsPanel
                            currentPower={currentPower}
                            cadence={liveTelemetry?.cadenceRpm ?? null}
                            heartRate={session.heartRate?.heartRateBpm ?? null}
                            speed={liveTelemetry?.speedKph ?? null}
                            elapsed={formatElapsed(session.startedAt, now)}
                            target={requestedTarget}
                            appliedTarget={appliedTarget}
                            controlMode={session.controlMode}
                            protectionState={session.ergProtection.state}
                            step={
                                session.controlMode === "FREE_RIDE" ||
                                session.workout === null
                                    ? "Free ride"
                                    : `${session.workout.currentStep}/${session.workout.totalSteps}`
                            }
                            powerProgress={powerProgress}
                        />
                    </div>

                    <div className="xl:col-span-7">
                        <TelemetryChart
                            points={trace}
                            target={requestedTarget}
                        />
                    </div>

                    <div className="space-y-4 xl:col-span-5">
                        {session.workout !== null &&
                        !session.workout.completed ? (
                            <WorkoutTargetAdjustment
                                disabled={
                                    !isActive ||
                                    isAdjustingWorkoutTarget ||
                                    isStopping ||
                                    session.ergProtection.state ===
                                        "UNAVAILABLE" ||
                                    session.ergProtection.state ===
                                        "RECOVERY_FAILED"
                                }
                                isAdjusting={isAdjustingWorkoutTarget}
                                percent={
                                    session.workoutPowerTargetPercent ?? 100
                                }
                                onAdjust={(deltaPercent) =>
                                    void adjustWorkoutTarget(deltaPercent)
                                }
                            />
                        ) : null}
                        {isActive &&
                        (session.workout === null ||
                            session.workout.completed) ? (
                            <ManualTargetForm
                                targetInput={targetInput}
                                isSettingTarget={isSettingTarget}
                                workoutCompleted={
                                    session.workout?.completed ?? false
                                }
                                onChange={setTargetInput}
                                onSubmit={(event) =>
                                    void setManualTarget(event)
                                }
                            />
                        ) : null}
                        {error ? <ErrorNotice message={error} /> : null}
                        <RideControls
                            isActive={isActive}
                            isPaused={isPaused}
                            isStopped={isStopped}
                            isPausing={isPausing}
                            isResuming={isResuming}
                            isStopping={isStopping}
                            isAdvancing={isAdvancing}
                            hasManualStep={
                                session.workout?.completed === false &&
                                session.workout.completion.kind === "MANUAL"
                            }
                            onAdvance={() => void advanceStep()}
                            onBack={() => navigate("/")}
                            onOpenEquipment={openEquipment}
                            onPause={() => void pauseSession()}
                            onResume={() => void resumeSession()}
                            onStop={() => setStopPromptOpen(true)}
                        />
                    </div>
                </div>

                {stopPromptOpen ? (
                    <StopPrompt
                        isStopping={isStopping}
                        onCancel={() => setStopPromptOpen(false)}
                        onConfirm={() => void stopSession()}
                    />
                ) : null}
                {postRideOpen ? (
                    <PostRideSheet
                        trace={trace}
                        upload={session.activityUpload}
                        isUploading={isUploading}
                        isDiscarding={isDiscarding}
                        error={error}
                        duration={formatElapsed(session.startedAt, now)}
                        onDiscard={() => void discardActivity()}
                        onUpload={() => void uploadActivity()}
                    />
                ) : null}
            </div>
        </div>
    );
}

function RideHeader({
    eyebrow,
    title,
    trainerName,
    onBack,
    onOpenEquipment,
}: {
    eyebrow: string;
    title: string;
    trainerName: string | null;
    onBack: () => void;
    onOpenEquipment: () => void;
}) {
    return (
        <div className="flex items-center justify-between gap-3">
            <button
                type="button"
                onClick={onBack}
                aria-label="Back to today"
                className="grid size-11 shrink-0 place-items-center rounded-2xl border border-white/[0.1] bg-white/[0.045] text-white/65 transition-colors hover:bg-white/[0.09] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
            >
                <ArrowLeft aria-hidden="true" className="size-5" />
            </button>
            <div className="min-w-0 flex-1 text-center">
                <p className="flex items-center justify-center gap-2 text-[0.6rem] font-bold tracking-[0.2em] text-white/35 uppercase">
                    <span className="size-1.5 rounded-full bg-[#ff8068] shadow-[0_0_12px_rgba(255,128,104,0.75)]" />
                    {eyebrow}
                </p>
                <h1 className="mt-1 truncate text-base font-black tracking-[-0.05em] text-white sm:text-lg">
                    {title}
                </h1>
            </div>
            <button
                type="button"
                onClick={onOpenEquipment}
                aria-label={
                    trainerName
                        ? `Equipment connected: ${trainerName}`
                        : "Open equipment"
                }
                className="relative grid size-11 shrink-0 place-items-center rounded-2xl border border-white/[0.1] bg-white/[0.045] text-white/65 transition-colors hover:bg-white/[0.09] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
            >
                <Bluetooth aria-hidden="true" className="size-5" />
                <span
                    className={cn(
                        "absolute right-0.5 bottom-0.5 size-2.5 rounded-full border-2 border-[#090c12]",
                        trainerName ? "bg-[#73d6a1]" : "bg-[#f0b766]",
                    )}
                />
            </button>
        </div>
    );
}

function PreRideView({
    connection,
    connectedSources,
    hasErgControl,
    isStarting,
    selectedHeartRateSourceId,
    selectedWorkout,
    athleteProfile,
    error,
    onBack,
    onOpenEquipment,
    onSelectHeartRateSource,
    onStart,
}: {
    connection: ReturnType<typeof useAppShell>["connection"];
    connectedSources: HeartRateSource[];
    hasErgControl: boolean;
    isStarting: boolean;
    selectedHeartRateSourceId: string | null;
    selectedWorkout: WorkoutItem | null;
    athleteProfile: ReturnType<typeof useAppShell>["profile"];
    error: string | null;
    onBack: () => void;
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
                    eyebrow="Ready to ride"
                    title={selectedWorkout?.name?.trim() || "Free ride"}
                    trainerName={trainer?.device.name ?? null}
                    onBack={onBack}
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
                                    ? "Review the structure, connect your trainer, and start when you are ready."
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
                        <p className="mt-3 text-center text-xs leading-5 text-white/30">
                            Your trainer receives a target only after start.
                        </p>
                    </section>
                </div>
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

function MetricsPanel({
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
    protectionState:
        | "INACTIVE"
        | "BAILED_OUT"
        | "RECOVERY_RETRYING"
        | "RECOVERY_FAILED"
        | "UNAVAILABLE";
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

function ErgProtectionBanner({
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

function ManualTargetForm({
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

function WorkoutTargetAdjustment({
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

function ErrorNotice({ message }: { message: string }) {
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

function ConnectionBanner({
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

function RideControls({
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

function StopPrompt({
    isStopping,
    onCancel,
    onConfirm,
}: {
    isStopping: boolean;
    onCancel: () => void;
    onConfirm: () => void;
}) {
    return (
        <ModalBackdrop onClose={onCancel}>
            <div className="w-full max-w-md rounded-[2rem] border border-white/[0.1] bg-[#151a24] p-5 text-white shadow-[0_28px_80px_rgba(0,0,0,0.5)] sm:p-7">
                <div className="flex items-start justify-between gap-4">
                    <div>
                        <p className="text-[0.6rem] font-bold tracking-[0.2em] text-[#ff8068] uppercase">
                            End ride
                        </p>
                        <h2 className="mt-3 text-3xl font-black tracking-[-0.08em]">
                            Stop this ride?
                        </h2>
                    </div>
                    <button
                        type="button"
                        onClick={onCancel}
                        aria-label="Keep riding"
                        className="grid size-9 place-items-center rounded-xl text-white/35 hover:bg-white/[0.08] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
                    >
                        <X aria-hidden="true" className="size-5" />
                    </button>
                </div>
                <p className="mt-5 text-sm leading-6 text-white/48">
                    Paceline will send a 0 W target to the trainer, finish the
                    local recording, and open the Intervals.icu upload option.
                </p>
                <div className="mt-7 flex flex-col-reverse gap-2.5 sm:flex-row sm:justify-end">
                    <button
                        type="button"
                        onClick={onCancel}
                        className="min-h-11 rounded-xl px-4 text-sm font-bold text-white/55 transition-colors hover:bg-white/[0.07] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
                    >
                        Keep riding
                    </button>
                    <button
                        type="button"
                        onClick={onConfirm}
                        disabled={isStopping}
                        className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-[#ff8068] px-4 text-sm font-black text-[#210b09] transition-colors hover:bg-[#ff9b88] focus-visible:ring-2 focus-visible:ring-[#ff8068] focus-visible:outline-none disabled:opacity-60"
                    >
                        {isStopping ? (
                            <LoaderCircle
                                aria-hidden="true"
                                className="size-4 animate-spin"
                            />
                        ) : (
                            <Square
                                aria-hidden="true"
                                className="size-3.5 fill-current"
                            />
                        )}
                        {isStopping ? "Stopping…" : "Stop ride"}
                    </button>
                </div>
            </div>
        </ModalBackdrop>
    );
}

function PostRideSheet({
    trace,
    upload,
    isUploading,
    isDiscarding,
    error,
    duration,
    onDiscard,
    onUpload,
}: {
    trace: RidePoint[];
    upload: TrainingActivityUploadResponse;
    isUploading: boolean;
    isDiscarding: boolean;
    error: string | null;
    duration: string;
    onDiscard: () => void;
    onUpload: () => void;
}) {
    const averagePower = useMemo(() => {
        const values = trace
            .map((point) => point.powerWatts)
            .filter((value): value is number => value !== null);
        if (values.length === 0) {
            return null;
        }
        return Math.round(
            values.reduce((sum, value) => sum + value, 0) / values.length,
        );
    }, [trace]);
    const canUpload = upload.state === "AVAILABLE" || upload.state === "FAILED";

    return (
        <ModalBackdrop onClose={onDiscard}>
            <div className="w-full max-w-lg rounded-[2rem] border border-white/[0.1] bg-[#151a24] p-5 text-white shadow-[0_28px_80px_rgba(0,0,0,0.5)] sm:p-7">
                <div className="flex items-start justify-between gap-4">
                    <div>
                        <span className="grid size-11 place-items-center rounded-2xl bg-[#73d6a1] text-[#0b1912]">
                            <Check aria-hidden="true" className="size-5" />
                        </span>
                        <p className="mt-5 text-[0.6rem] font-bold tracking-[0.2em] text-[#73d6a1] uppercase">
                            Ride ended
                        </p>
                        <h2 className="mt-2 text-3xl font-black tracking-[-0.08em]">
                            Nice work.
                        </h2>
                    </div>
                    <button
                        type="button"
                        onClick={onDiscard}
                        aria-label="Close ride summary"
                        className="grid size-9 place-items-center rounded-xl text-white/35 hover:bg-white/[0.08] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
                    >
                        <X aria-hidden="true" className="size-5" />
                    </button>
                </div>

                <div className="mt-6 grid grid-cols-2 gap-2.5">
                    <SummaryStat
                        label="Duration"
                        value={duration}
                        icon={Clock3}
                    />
                    <SummaryStat
                        label="Average power"
                        value={
                            averagePower === null ? "—" : `${averagePower} W`
                        }
                        icon={Zap}
                    />
                </div>

                <div className="mt-5 rounded-2xl border border-white/[0.08] bg-white/[0.04] p-4">
                    <div className="flex items-start gap-3">
                        <Upload
                            aria-hidden="true"
                            className="mt-0.5 size-5 text-[#aeb4ff]"
                        />
                        <div>
                            <p className="text-sm font-bold">
                                Send this activity to Intervals.icu?
                            </p>
                            <p className="mt-1 text-xs leading-5 text-white/42">
                                Upload it now or discard this local activity.
                                Intervals.icu remains your training history.
                            </p>
                        </div>
                    </div>
                    {upload.error ? (
                        <p className="mt-3 text-xs leading-5 text-[#ffaaa1]">
                            {upload.error}
                        </p>
                    ) : null}
                    {error ? (
                        <p className="mt-3 text-xs leading-5 text-[#ffaaa1]">
                            {error}
                        </p>
                    ) : null}
                </div>

                <div className="mt-6 flex flex-col gap-2.5">
                    {canUpload ? (
                        <button
                            type="button"
                            onClick={onUpload}
                            disabled={isUploading || isDiscarding}
                            className="inline-flex min-h-13 items-center justify-center gap-2 rounded-2xl bg-[#7e87ff] px-5 text-sm font-black text-[#0b0d14] transition-colors hover:bg-[#aeb4ff] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-60"
                        >
                            {isUploading ? (
                                <LoaderCircle
                                    aria-hidden="true"
                                    className="size-4 animate-spin"
                                />
                            ) : (
                                <Send aria-hidden="true" className="size-4" />
                            )}
                            {isUploading
                                ? "Sending activity…"
                                : "Send to Intervals.icu"}
                        </button>
                    ) : null}
                    <button
                        type="button"
                        onClick={onDiscard}
                        disabled={isUploading || isDiscarding}
                        className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl px-5 text-sm font-bold text-white/50 transition-colors hover:bg-white/[0.07] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
                    >
                        {isDiscarding ? "Discarding…" : "Discard"}
                        <ArrowLeft
                            aria-hidden="true"
                            className="size-4 rotate-180"
                        />
                    </button>
                </div>
            </div>
        </ModalBackdrop>
    );
}

function SummaryStat({
    label,
    value,
    icon: Icon,
}: {
    label: string;
    value: string;
    icon: typeof Clock3;
}) {
    return (
        <div className="rounded-2xl border border-white/[0.08] bg-white/[0.04] p-3.5">
            <Icon aria-hidden="true" className="size-4 text-white/35" />
            <p className="mt-3 text-[0.58rem] font-bold tracking-[0.14em] text-white/30 uppercase">
                {label}
            </p>
            <p className="mt-1 text-xl font-black tracking-[-0.05em] text-white">
                {value}
            </p>
        </div>
    );
}

function ModalBackdrop({
    children,
    onClose,
}: {
    children: React.ReactNode;
    onClose: () => void;
}) {
    useEffect(() => {
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === "Escape") {
                onClose();
            }
        };
        const previousOverflow = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        window.addEventListener("keydown", handleKeyDown);
        return () => {
            document.body.style.overflow = previousOverflow;
            window.removeEventListener("keydown", handleKeyDown);
        };
    }, [onClose]);

    return (
        <div
            className="fixed inset-0 z-[95] flex items-end justify-center bg-[#05070c]/78 p-4 backdrop-blur-sm sm:items-center sm:p-6"
            role="presentation"
            onMouseDown={(event) => {
                if (event.target === event.currentTarget) {
                    onClose();
                }
            }}
        >
            {children}
        </div>
    );
}
