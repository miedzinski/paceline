import { createContext, useContext } from "react";
import type {
    AthleteProfile,
    DeviceConnectionsResponse,
    DeviceDiscoveryResponse,
    LibraryWorkout,
    RideEquipmentResponse,
    RideRole,
    TodayWorkoutsResponse,
} from "@/types";

export interface AppShellContextValue {
    connection: DeviceConnectionsResponse;
    profile: AthleteProfile | null;
    today: TodayWorkoutsResponse | null;
    isPlanLoading: boolean;
    planError: string | null;
    library: LibraryWorkout[];
    isLibraryLoading: boolean;
    libraryError: string | null;
    discovery: DeviceDiscoveryResponse | null;
    isDiscovering: boolean;
    connectingDeviceId: string | null;
    equipment: RideEquipmentResponse | null;
    equipmentLoading: boolean;
    equipmentError: string | null;
    isEquipmentOpen: boolean;
    actionError: string | null;
    openEquipment: () => void;
    closeEquipment: () => void;
    discoverDevices: () => Promise<void>;
    connectDevice: (deviceId: string) => Promise<void>;
    disconnectDevice: (connectionId: string) => Promise<void>;
    refreshEquipment: () => Promise<void>;
    selectEquipmentRole: (role: RideRole, sourceId: string) => Promise<void>;
    clearEquipmentRole: (role: RideRole) => Promise<void>;
    refreshConnection: () => Promise<void>;
    refreshPlan: () => Promise<void>;
    refreshLibrary: () => Promise<void>;
}

export const AppShellContext = createContext<AppShellContextValue | null>(null);

export function useAppShell(): AppShellContextValue {
    const value = useContext(AppShellContext);
    if (value === null) {
        throw new Error("useAppShell must be used inside AppLayout");
    }

    return value;
}
