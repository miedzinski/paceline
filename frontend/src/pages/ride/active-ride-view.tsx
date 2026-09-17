import { TelemetryChart } from "@/components/telemetry-chart";
import { WorkoutTimeline } from "@/components/workout-timeline";
import { formatElapsed } from "@/lib/connection";
import type { RideSessionModel } from "./use-ride-session";
import {
    RideControls,
    ManualTargetForm,
    WorkoutTargetAdjustment,
} from "./ride-controls";
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
        openEquipment,
        profile,
        session,
        trace,
        now,
        targetInput,
        setTargetInput,
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
        trainer,
        definition,
        isActive,
        isPaused,
        isStopped,
        currentPower,
        requestedTarget,
        appliedTarget,
        powerProgress,
        rideName,
        navigateHome,
        pauseSession,
        resumeSession,
        stopSession,
        advanceStep,
        setManualTarget,
        adjustWorkoutTarget,
        uploadActivity,
        discardActivity,
    } = ride;

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
                    onBack={navigateHome}
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
                            onBack={navigateHome}
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
