import type { TrainingSessionState, WorkoutSelection } from "@/types";

export type WorkoutEntryMode = "CHECKING" | "SETUP" | "AUTO_STARTING";

export function workoutEntryMode({
    sessionLoaded,
    equipmentLoaded,
    sessionState,
    workoutSelection,
    hasErgControl,
    setupRequired,
}: {
    sessionLoaded: boolean;
    equipmentLoaded: boolean;
    sessionState: TrainingSessionState;
    workoutSelection: WorkoutSelection | null;
    hasErgControl: boolean;
    setupRequired: boolean;
}): WorkoutEntryMode {
    if (workoutSelection === null) {
        return "SETUP";
    }

    if (setupRequired) {
        return "SETUP";
    }

    if (!sessionLoaded || !equipmentLoaded) {
        return "CHECKING";
    }

    if (sessionState !== "NOT_STARTED" || !hasErgControl) {
        return "SETUP";
    }

    return "AUTO_STARTING";
}
