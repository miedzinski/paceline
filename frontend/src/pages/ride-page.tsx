import { ActiveRideView } from "@/pages/ride/active-ride-view";
import { PreRideView, RideStartingView } from "@/pages/ride/ride-setup";
import { useRideSession } from "@/pages/ride/use-ride-session";

export function RidePage() {
    const ride = useRideSession();

    if (ride.isWaitingToStartWorkout) {
        return (
            <RideStartingView
                connection={ride.connection}
                equipment={ride.equipment}
                isChecking={ride.workoutEntryMode === "CHECKING"}
                onLogoClick={ride.navigateHome}
                onOpenEquipment={ride.openEquipment}
            />
        );
    }

    if (
        !ride.isActive &&
        !ride.isPaused &&
        (!ride.isStopped || (ride.isPreparingNewWorkout && !ride.postRideOpen))
    ) {
        return (
            <PreRideView
                connection={ride.connection}
                equipment={ride.equipment}
                equipmentError={ride.equipmentError}
                equipmentLoading={ride.equipmentLoading}
                hasErgControl={ride.hasErgControl}
                isStarting={ride.isStarting}
                selectedWorkout={ride.selectedWorkout}
                athleteProfile={ride.profile}
                error={ride.error}
                onLogoClick={ride.navigateHome}
                onOpenEquipment={ride.openEquipment}
                onStart={() => void ride.startSession()}
            />
        );
    }

    return <ActiveRideView ride={ride} />;
}
