import { WorkoutProgressTile } from "@/components/workout-progress-tile";
import { formatElapsed } from "@/lib/connection";
import { sourceName } from "@/lib/ride-equipment";
import type { RideSessionModel } from "./use-ride-session";
import { RideControls } from "./ride-controls";
import {
    ConnectionBanner,
    ErgProtectionBanner,
    ErrorNotice,
    MetricsPanel,
} from "./ride-status";
import { RideHeader } from "./ride-header";
import { PostRideSheet, StopPrompt } from "./ride-sheets";

export function ActiveRideView({ ride }: { ride: RideSessionModel }) {
    const {
        connection,
        openEquipment,
        profile,
        session,
        now,
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
        trainer,
        equipment,
        selectedHeartRateSourceId,
        definition,
        isActive,
        isPaused,
        currentPower,
        requestedTarget,
        navigateHome,
        pauseSession,
        resumeSession,
        stopSession,
        advanceStep,
        adjustManualTarget,
        adjustWorkoutTarget,
        uploadActivity,
        discardActivity,
    } = ride;

    const workoutTargetAdjustment =
        session.workout !== null && !session.workout.completed
            ? {
                  disabled:
                      !isActive ||
                      isAdjustingWorkoutTarget ||
                      isStopping ||
                      session.ergProtection.state === "UNAVAILABLE" ||
                      session.ergProtection.state === "RECOVERY_FAILED",
                  isAdjusting: isAdjustingWorkoutTarget,
                  onAdjust: (deltaPercent: number) =>
                      void adjustWorkoutTarget(deltaPercent),
              }
            : null;
    const isManualMode = session.workout === null || session.workout.completed;
    const manualTargetAdjustment =
        (isActive || isPaused) && isManualMode && requestedTarget !== null
            ? {
                  disabled: isAdjustingManualTarget || isStopping || !isActive,
                  isAdjusting: isAdjustingManualTarget,
                  onAdjust: (deltaWatts: number) =>
                      void adjustManualTarget(deltaWatts),
              }
            : null;
    const controlTrainerName =
        trainer?.device.name ??
        sourceName(equipment, equipment?.assignments.controlSourceId ?? null);
    const controlTrainerAssigned =
        equipment?.assignments.controlSourceId !== null &&
        equipment?.assignments.controlSourceId !== undefined;

    return (
        <div className="flex min-h-[100svh] flex-col bg-[#090c12] px-4 pt-[calc(0.9rem+env(safe-area-inset-top))] pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-[#f5f6fb] sm:px-6 lg:px-8">
            <div className="mx-auto flex w-full max-w-[1200px] flex-1 flex-col">
                <RideHeader
                    connection={connection}
                    equipment={equipment}
                    onOpenEquipment={openEquipment}
                />

                <ConnectionBanner
                    hasTrainer={controlTrainerAssigned}
                    trainerName={controlTrainerName}
                    telemetryAvailable={
                        liveTelemetry?.availability === "CURRENT"
                    }
                    status={session.trainerConnection}
                    retryAttempt={session.trainerConnectionRetryAttempt}
                    error={session.trainerConnectionError}
                    onOpenEquipment={openEquipment}
                />

                <div className="relative mt-6 flex flex-1 flex-col">
                    <ErgProtectionBanner
                        protection={session.ergProtection}
                        overlay
                    />

                    <div className="flex flex-1 flex-col gap-4">
                        <WorkoutProgressTile
                            now={now}
                            manualTargetWatts={
                                isActive || isPaused ? requestedTarget : null
                            }
                            targetPercent={session.workoutPowerTargetPercent}
                            definition={definition}
                            workout={session.workout}
                            athleteProfile={profile}
                            paused={isPaused}
                        />

                        <MetricsPanel
                            currentPower={currentPower}
                            cadence={telemetry.cycling?.cadenceRpm ?? null}
                            heartRate={
                                telemetry.heartRate?.heartRateBpm ?? null
                            }
                            heartRateAvailability={
                                telemetry.heartRateAvailability
                            }
                            speed={telemetry.cycling?.speedKph ?? null}
                            heartRateSelected={
                                selectedHeartRateSourceId !== null
                            }
                        />

                        <div className="mt-auto shrink-0">
                            <p className="mb-2 text-center text-xs font-semibold text-white/55">
                                <span className="text-white/35">Elapsed</span>{" "}
                                {formatElapsed(session.startedAt, now)}
                            </p>
                            <RideControls
                                isActive={isActive}
                                isPaused={isPaused}
                                isPausing={isPausing}
                                isResuming={isResuming}
                                isStopping={isStopping}
                                isAdvancing={isAdvancing}
                                hasManualStep={
                                    session.workout?.completed === false &&
                                    session.workout.completion.kind === "MANUAL"
                                }
                                onAdvance={() => void advanceStep()}
                                onBack={navigateHome}
                                onPause={() => void pauseSession()}
                                onResume={() => void resumeSession()}
                                onStop={() => setStopPromptOpen(true)}
                                manualTargetAdjustment={manualTargetAdjustment}
                                workoutTargetAdjustment={
                                    workoutTargetAdjustment
                                }
                            >
                                {error ? <ErrorNotice message={error} /> : null}
                            </RideControls>
                        </div>
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
                        summary={session.activitySummary}
                        upload={session.activityUpload}
                        isUploading={isUploading}
                        isDiscarding={isDiscarding}
                        error={error}
                        onDiscard={() => void discardActivity()}
                        onUpload={() => void uploadActivity()}
                    />
                ) : null}
            </div>
        </div>
    );
}
