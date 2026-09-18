import type { TrainingSessionState, WorkoutSelection } from "@/types";

export function canAutoStartSelectedWorkout({
    sessionLoaded,
    sessionState,
    workoutSelection,
    hasErgControl,
    heartRateSourceCount,
    selectedHeartRateSourceId,
}: {
    sessionLoaded: boolean;
    sessionState: TrainingSessionState;
    workoutSelection: WorkoutSelection | null;
    hasErgControl: boolean;
    heartRateSourceCount: number;
    selectedHeartRateSourceId: string | null;
}): boolean {
    return (
        sessionLoaded &&
        sessionState === "NOT_STARTED" &&
        workoutSelection !== null &&
        hasErgControl &&
        (heartRateSourceCount <= 1 || selectedHeartRateSourceId !== null)
    );
}
