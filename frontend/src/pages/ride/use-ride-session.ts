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
import type { RidePoint } from "@/components/telemetry-chart";
import { useAppShell } from "@/lib/app-shell";
import type { WorkoutItem } from "@/lib/workouts";
import type {
    HeartRateSource,
    TrainingSessionResponse,
    WorkoutDefinition,
    WorkoutSelection,
} from "@/types";

const sessionPollIntervalMs = 1_000;
export const minimumFtmsPowerWatts = -32_768;
export const maximumFtmsPowerWatts = 32_767;

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

export function useRideSession() {
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

    const navigateHome = useCallback(() => navigate("/"), [navigate]);

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
            navigateHome();
        } catch (uploadError) {
            setError(displayError(uploadError));
        } finally {
            setIsUploading(false);
        }
    }, [navigateHome, session.sessionId]);

    const discardActivity = useCallback(async () => {
        if (session.sessionId === null) {
            navigateHome();
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
            navigateHome();
        } catch (discardError) {
            setError(displayError(discardError));
        } finally {
            setIsDiscarding(false);
        }
    }, [navigateHome, session.sessionId]);

    const rideName =
        selectedWorkout?.name?.trim() || session.workout?.name || "Free ride";

    return {
        connection,
        openEquipment,
        profile,
        selectedWorkout,
        workoutSelection,
        session,
        trace,
        now,
        targetInput,
        setTargetInput,
        isStarting,
        isPausing,
        isResuming,
        isStopping,
        isAdvancing,
        isSettingTarget,
        isAdjustingWorkoutTarget,
        isUploading,
        isDiscarding,
        stopPromptOpen,
        setStopPromptOpen,
        postRideOpen,
        error,
        liveTelemetry,
        connectedSources,
        trainer,
        hasErgControl,
        selectedHeartRateSourceId,
        definition,
        isPreparingNewWorkout,
        isActive,
        isPaused,
        isStopped,
        currentPower,
        requestedTarget,
        appliedTarget,
        powerProgress,
        rideName,
        navigateHome,
        startSession,
        pauseSession,
        resumeSession,
        stopSession,
        advanceStep,
        setManualTarget,
        adjustWorkoutTarget,
        selectHeartRateSource,
        uploadActivity,
        discardActivity,
    };
}

export type RideSessionModel = ReturnType<typeof useRideSession>;
