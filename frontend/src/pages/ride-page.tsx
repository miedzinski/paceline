import { ActiveRideView } from "@/pages/ride/active-ride-view";
import { PreRideView, RideStartingView } from "@/pages/ride/ride-setup";
import { useRideSession } from "@/pages/ride/use-ride-session";

export function RidePage() {
    const ride = useRideSession();

    if (ride.isAutoStartingWorkout) {
        return (
            <RideStartingView
                connection={ride.connection}
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
                connectedSources={ride.connectedSources}
                hasErgControl={ride.hasErgControl}
                isStarting={ride.isStarting}
                selectedHeartRateSourceId={ride.selectedHeartRateSourceId}
                selectedWorkout={ride.selectedWorkout}
                athleteProfile={ride.profile}
                error={ride.error}
                onLogoClick={ride.navigateHome}
                onOpenEquipment={ride.openEquipment}
                onSelectHeartRateSource={(sourceId) =>
                    void ride.selectHeartRateSource(sourceId)
                }
                onStart={() => void ride.startSession()}
            />
        );
    }

    return <ActiveRideView ride={ride} />;
}
