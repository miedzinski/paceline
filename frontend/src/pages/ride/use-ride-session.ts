import { useCallback, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router";
import { ApiError, trainingApi } from "@/api";
import { useAppShell } from "@/lib/app-shell";
import {
    adjustManualErgTargetWatts,
    initialManualErgTargetWatts,
} from "@/lib/manual-erg";
import {
    roleState,
    liveSessionTelemetry,
    sourceConnection,
} from "@/lib/ride-equipment";
import { workoutEntryMode, type WorkoutEntryMode } from "@/lib/ride-entry";
import { workoutDefinitionFromSession } from "@/lib/training-workout";
import type { WorkoutItem } from "@/lib/workouts";
import type {
    TrainingSessionResponse,
    WorkoutDefinition,
    WorkoutSelection,
} from "@/types";

const sessionPollIntervalMs = 1_000;

interface RideLocationState {
    workoutSelection?: WorkoutSelection | null;
    workout?: WorkoutItem | null;
    setupRequired?: boolean;
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
        },
        trainerConnection: "NOT_ACTIVE",
        trainerConnectionRetryAttempt: null,
        trainerConnectionError: null,
        telemetry: null,
        workout: null,
        activityUpload: {
            state: "UNAVAILABLE",
            remoteActivityId: null,
            error: null,
        },
        activitySummary: null,
        equipment: null,
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

function workoutSelectionKey(
    selection: WorkoutSelection | null,
): string | null {
    return selection === null
        ? null
        : `${selection.provider}:${selection.sourceType}:${selection.sourceId}`;
}

export function useRideSession() {
    const navigate = useNavigate();
    const location = useLocation();
    const {
        connection,
        openEquipment,
        profile,
        equipment,
        equipmentLoading,
        equipmentError,
    } = useAppShell();
    const routeState = (location.state as RideLocationState | null) ?? null;
    const selectedWorkout = routeState?.workout ?? null;
    const workoutSelection = routeState?.workoutSelection ?? null;
    const selectedWorkoutKey = workoutSelectionKey(workoutSelection);
    const setupRequired =
        workoutSelection === null ? false : (routeState?.setupRequired ?? true);

    const [session, setSession] = useState<TrainingSessionResponse>(
        createNotStartedSession,
    );
    const [now, setNow] = useState(0);
    const [sessionLoaded, setSessionLoaded] = useState(false);
    const [isStarting, setIsStarting] = useState(false);
    const [isPausing, setIsPausing] = useState(false);
    const [isResuming, setIsResuming] = useState(false);
    const [isStopping, setIsStopping] = useState(false);
    const [isAdvancing, setIsAdvancing] = useState(false);
    const [isAdjustingManualTarget, setIsAdjustingManualTarget] =
        useState(false);
    const [isAdjustingWorkoutTarget, setIsAdjustingWorkoutTarget] =
        useState(false);
    const [isUploading, setIsUploading] = useState(false);
    const [isDiscarding, setIsDiscarding] = useState(false);
    const [stopPromptOpen, setStopPromptOpen] = useState(false);
    const [postRideOpen, setPostRideOpen] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [automaticStartFailedKey, setAutomaticStartFailedKey] = useState<
        string | null
    >(null);
    const sessionRefreshGeneration = useRef(0);
    const automaticStartKey = useRef<string | null>(null);
    const liveTelemetry = liveSessionTelemetry(
        session.state,
        session.trainerConnection,
        session.telemetry,
    );
    const cyclingProjection =
        liveTelemetry ??
        (session.state === "STOPPED"
            ? (session.telemetry?.cycling ?? null)
            : null);
    const telemetry = {
        cycling: cyclingProjection?.sample ?? null,
        cyclingAvailability: cyclingProjection?.availability ?? "UNAVAILABLE",
        heartRate: session.telemetry?.heartRate.sample ?? null,
        heartRateAvailability:
            session.telemetry?.heartRate.availability ?? "UNAVAILABLE",
    };

    const refreshSession = useCallback(async () => {
        const requestGeneration = ++sessionRefreshGeneration.current;
        try {
            const nextSession = await trainingApi.getCurrent();
            if (requestGeneration !== sessionRefreshGeneration.current) {
                return;
            }
            setSession(nextSession);
            setSessionLoaded(true);
            if (nextSession.state === "STOPPED") {
                setPostRideOpen(true);
            } else if (nextSession.state === "NOT_STARTED") {
                setPostRideOpen(false);
            }
        } catch (refreshError) {
            setError(displayError(refreshError));
            setSessionLoaded(true);
            setAutomaticStartFailedKey(selectedWorkoutKey);
        }
    }, [selectedWorkoutKey]);

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

    const controlRole =
        equipment === null ? null : roleState(equipment, "RESISTANCE_CONTROL");
    const trainer =
        equipment === null
            ? null
            : sourceConnection(
                  connection,
                  equipment.assignments.controlSourceId,
              );
    const hasErgControl = controlRole?.status === "SELECTED";
    const selectedHeartRateSourceId =
        session.equipment?.assignments.heartRateSourceId ??
        equipment?.assignments.heartRateSourceId ??
        null;
    const definition =
        workoutDefinitionFromSession(session.workout) ??
        activeWorkoutDefinition(selectedWorkout);
    const isPreparingNewWorkout = workoutSelection !== null;
    const isActive = session.state === "ACTIVE";
    const isPaused = session.state === "PAUSED";
    const isStopped = session.state === "STOPPED";
    const equipmentLoaded =
        !equipmentLoading && (equipment !== null || equipmentError !== null);
    const currentWorkoutEntryMode: WorkoutEntryMode = workoutEntryMode({
        sessionLoaded,
        equipmentLoaded,
        sessionState: session.state,
        workoutSelection,
        hasErgControl,
        setupRequired:
            setupRequired || automaticStartFailedKey === selectedWorkoutKey,
    });
    const currentPower = telemetry.cycling?.powerWatts ?? null;
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

    const startSession = useCallback(
        async ({ automatic = false }: { automatic?: boolean } = {}) => {
            if (equipment === null || equipmentLoading) {
                if (automatic && equipmentLoading) {
                    automaticStartKey.current = null;
                    return;
                }
                setError(equipmentError ?? "Checking connected equipment…");
                if (automatic) {
                    setAutomaticStartFailedKey(selectedWorkoutKey);
                }
                return;
            }

            if (!hasErgControl) {
                if (automatic) {
                    setAutomaticStartFailedKey(selectedWorkoutKey);
                } else {
                    openEquipment();
                }
                return;
            }

            setIsStarting(true);
            setError(null);
            sessionRefreshGeneration.current += 1;
            setNow(0);
            try {
                const result = await trainingApi.start(
                    workoutSelection ?? undefined,
                    equipment.assignments,
                );
                sessionRefreshGeneration.current += 1;
                let nextSession = result;
                if (result.workout === null && result.sessionId !== null) {
                    const initialTarget = initialManualErgTargetWatts(profile);
                    if (initialTarget !== null) {
                        try {
                            nextSession = await trainingApi.setErgTarget(
                                result.sessionId,
                                initialTarget,
                            );
                        } catch (targetError) {
                            setError(displayError(targetError));
                        }
                    }
                }
                setSession(nextSession);
            } catch (startError) {
                setError(displayError(startError));
                if (automatic) {
                    setAutomaticStartFailedKey(selectedWorkoutKey);
                }
            } finally {
                setIsStarting(false);
            }
        },
        [
            equipment,
            equipmentError,
            equipmentLoading,
            hasErgControl,
            openEquipment,
            profile,
            selectedWorkoutKey,
            workoutSelection,
        ],
    );

    useEffect(() => {
        if (
            currentWorkoutEntryMode !== "AUTO_STARTING" ||
            equipmentLoading ||
            session.state !== "NOT_STARTED" ||
            selectedWorkoutKey === null ||
            automaticStartKey.current === selectedWorkoutKey
        ) {
            return;
        }

        automaticStartKey.current = selectedWorkoutKey;
        void startSession({ automatic: true });
    }, [
        currentWorkoutEntryMode,
        equipmentLoading,
        selectedWorkoutKey,
        session.state,
        startSession,
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
        sessionRefreshGeneration.current += 1;
        try {
            const result = await trainingApi.stop(session.sessionId);
            sessionRefreshGeneration.current += 1;
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

    const adjustManualTarget = useCallback(
        async (deltaWatts: number) => {
            if (
                session.sessionId === null ||
                !isActive ||
                (session.workout !== null && !session.workout.completed) ||
                requestedTarget === null
            ) {
                return;
            }

            const nextTarget = adjustManualErgTargetWatts(
                requestedTarget,
                deltaWatts,
            );
            if (nextTarget === requestedTarget) {
                return;
            }

            setIsAdjustingManualTarget(true);
            setError(null);
            try {
                setSession(
                    await trainingApi.setErgTarget(
                        session.sessionId,
                        nextTarget,
                    ),
                );
            } catch (targetError) {
                setError(displayError(targetError));
            } finally {
                setIsAdjustingManualTarget(false);
            }
        },
        [isActive, requestedTarget, session.sessionId, session.workout],
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

    return {
        connection,
        openEquipment,
        profile,
        selectedWorkout,
        workoutSelection,
        session,
        now,
        isStarting,
        isPausing,
        isResuming,
        isStopping,
        isAdvancing,
        isAdjustingManualTarget,
        isAdjustingWorkoutTarget,
        isUploading,
        isDiscarding,
        stopPromptOpen,
        setStopPromptOpen,
        postRideOpen,
        error,
        liveTelemetry,
        telemetry,
        equipment,
        equipmentError,
        equipmentLoading,
        trainer,
        hasErgControl,
        selectedHeartRateSourceId,
        definition,
        isPreparingNewWorkout,
        workoutEntryMode: currentWorkoutEntryMode,
        isWaitingToStartWorkout:
            isPreparingNewWorkout &&
            session.state === "NOT_STARTED" &&
            currentWorkoutEntryMode !== "SETUP",
        isActive,
        isPaused,
        isStopped,
        currentPower,
        requestedTarget,
        appliedTarget,
        powerProgress,
        navigateHome,
        startSession,
        pauseSession,
        resumeSession,
        stopSession,
        advanceStep,
        adjustManualTarget,
        adjustWorkoutTarget,
        uploadActivity,
        discardActivity,
    };
}

export type RideSessionModel = ReturnType<typeof useRideSession>;
