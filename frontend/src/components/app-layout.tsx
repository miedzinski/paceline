import { LoaderCircle, RefreshCw } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Outlet, useLocation, useNavigate } from "react-router";
import {
    ApiError,
    deviceApi,
    profileApi,
    trainingApi,
    workoutApi,
} from "@/api";
import { EquipmentButton } from "@/components/equipment-button";
import { EquipmentSheet } from "@/components/equipment-sheet";
import { PacelineLogo } from "@/components/paceline-logo";
import { AppShellContext, type AppShellContextValue } from "@/lib/app-shell";
import {
    deviceDiscoveryFreshnessMs,
    shouldRefreshDiscovery,
    shouldScanOnOpen,
} from "@/lib/device-discovery";
import { rolesAssignedToSource } from "@/lib/ride-equipment";
import { cn } from "@/lib/utils";
import type {
    AthleteProfile,
    DeviceConnectionsResponse,
    DeviceDiscoveryResponse,
    LibraryWorkout,
    RideEquipmentResponse,
    RideRole,
    TodayWorkoutsResponse,
    TrainingSessionResponse,
} from "@/types";

const connectionPollIntervalMs = 2_000;
const sessionPollIntervalMs = 1_000;

function createReadyConnection(): DeviceConnectionsResponse {
    return {
        connections: [],
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

function hasUnfinishedWorkout(session: TrainingSessionResponse): boolean {
    return (
        (session.state === "ACTIVE" || session.state === "PAUSED") &&
        session.workout !== null &&
        !session.workout.completed
    );
}

function RefreshPlanButton({
    isRefreshing,
    onClick,
}: {
    isRefreshing: boolean;
    onClick: () => void;
}) {
    return (
        <button
            type="button"
            onClick={onClick}
            disabled={isRefreshing}
            aria-label="Sync Intervals.icu"
            title="Sync Intervals.icu"
            className="grid size-10 shrink-0 place-items-center rounded-2xl border border-white/[0.1] bg-white/[0.045] text-white/60 transition-colors hover:border-white/[0.2] hover:bg-white/[0.08] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60"
        >
            {isRefreshing ? (
                <LoaderCircle
                    aria-hidden="true"
                    className="size-4 animate-spin"
                />
            ) : (
                <RefreshCw aria-hidden="true" className="size-4" />
            )}
        </button>
    );
}

export function AppLayout() {
    const location = useLocation();
    const navigate = useNavigate();
    const isRide = location.pathname.startsWith("/ride");
    const isToday = location.pathname === "/";
    const isLibrary = location.pathname === "/library";
    const [connection, setConnection] = useState(createReadyConnection);
    const [profile, setProfile] = useState<AthleteProfile | null>(null);
    const [today, setToday] = useState<TodayWorkoutsResponse | null>(null);
    const [isPlanLoading, setIsPlanLoading] = useState(false);
    const [planError, setPlanError] = useState<string | null>(null);
    const [library, setLibrary] = useState<LibraryWorkout[]>([]);
    const [isLibraryLoading, setIsLibraryLoading] = useState(true);
    const [libraryError, setLibraryError] = useState<string | null>(null);
    const [discovery, setDiscovery] = useState<DeviceDiscoveryResponse | null>(
        null,
    );
    const [isDiscovering, setIsDiscovering] = useState(false);
    const [connectingDeviceId, setConnectingDeviceId] = useState<string | null>(
        null,
    );
    const [equipment, setEquipment] = useState<RideEquipmentResponse | null>(
        null,
    );
    const [equipmentLoading, setEquipmentLoading] = useState(false);
    const [equipmentError, setEquipmentError] = useState<string | null>(null);
    const [isEquipmentOpen, setIsEquipmentOpen] = useState(false);
    const [actionError, setActionError] = useState<string | null>(null);
    const discoveryRequestInFlight = useRef(false);
    const discoveryInitializationStarted = useRef(false);

    const refreshConnection = useCallback(async () => {
        try {
            setConnection(await deviceApi.getConnection());
        } catch (error) {
            setActionError(displayError(error));
        }
    }, []);

    useEffect(() => {
        const initialRefresh = window.setTimeout(
            () => void refreshConnection(),
            0,
        );
        const pollHandle = window.setInterval(
            () => void refreshConnection(),
            connectionPollIntervalMs,
        );

        return () => {
            window.clearTimeout(initialRefresh);
            window.clearInterval(pollHandle);
        };
    }, [refreshConnection]);

    const connectionSignature = useMemo(
        () =>
            connection.connections
                .map((item) =>
                    [
                        item.id,
                        item.state,
                        [...item.capabilities].sort().join(","),
                    ].join(":"),
                )
                .sort()
                .join("|"),
        [connection.connections],
    );

    const refreshEquipment = useCallback(async () => {
        setEquipmentLoading(true);
        try {
            setEquipment(await trainingApi.getEquipment());
            setEquipmentError(null);
        } catch (error) {
            setEquipmentError(displayError(error));
        } finally {
            setEquipmentLoading(false);
        }
    }, []);

    useEffect(() => {
        const handle = window.setTimeout(() => void refreshEquipment(), 0);
        return () => window.clearTimeout(handle);
    }, [connectionSignature, isEquipmentOpen, isRide, refreshEquipment]);

    useEffect(() => {
        if (isRide) {
            return;
        }

        let cancelled = false;

        const redirectToActiveWorkout = async () => {
            try {
                const session = await trainingApi.getCurrent();
                if (!cancelled && hasUnfinishedWorkout(session)) {
                    navigate("/ride", { replace: true });
                }
            } catch {
                // The ride page will surface connection errors if the user opens it directly.
            }
        };

        const initialRefresh = window.setTimeout(
            () => void redirectToActiveWorkout(),
            0,
        );
        const pollHandle = window.setInterval(
            () => void redirectToActiveWorkout(),
            sessionPollIntervalMs,
        );

        return () => {
            cancelled = true;
            window.clearTimeout(initialRefresh);
            window.clearInterval(pollHandle);
        };
    }, [isRide, navigate]);

    const refreshProfile = useCallback(async () => {
        try {
            setProfile(await profileApi.get());
        } catch {
            // Keep the last known profile when a refresh cannot reach the backend.
        }
    }, []);

    const refreshPlan = useCallback(async () => {
        setIsPlanLoading(true);
        setPlanError(null);

        const [todayResult, profileResult] = await Promise.allSettled([
            workoutApi.getToday(),
            profileApi.get(),
        ]);

        if (todayResult.status === "fulfilled") {
            setToday(todayResult.value);
        } else {
            setPlanError(displayError(todayResult.reason));
        }

        if (profileResult.status === "fulfilled") {
            setProfile(profileResult.value);
        }

        setIsPlanLoading(false);
    }, []);

    const refreshLibrary = useCallback(async () => {
        setIsLibraryLoading(true);
        setLibraryError(null);

        const [libraryResult, profileResult] = await Promise.allSettled([
            workoutApi.getLibrary(),
            profileApi.get(),
        ]);

        if (libraryResult.status === "fulfilled") {
            setLibrary(libraryResult.value.workouts);
        } else {
            setLibraryError(displayError(libraryResult.reason));
        }

        if (profileResult.status === "fulfilled") {
            setProfile(profileResult.value);
        }

        setIsLibraryLoading(false);
    }, []);

    useEffect(() => {
        const handle = window.setTimeout(() => {
            if (isToday) {
                void refreshPlan();
            } else if (isLibrary) {
                void refreshLibrary();
            } else {
                void refreshProfile();
            }
        }, 0);
        return () => window.clearTimeout(handle);
    }, [isLibrary, isToday, refreshLibrary, refreshPlan, refreshProfile]);

    const openEquipment = useCallback(() => {
        setActionError(null);
        setIsEquipmentOpen(true);
    }, []);

    const closeEquipment = useCallback(() => {
        setIsEquipmentOpen(false);
    }, []);

    const discoverDevices = useCallback(async () => {
        if (discoveryRequestInFlight.current) {
            return;
        }

        discoveryRequestInFlight.current = true;
        setIsDiscovering(true);
        setActionError(null);
        try {
            setDiscovery(await deviceApi.discover());
        } catch (error) {
            setActionError(displayError(error));
            setIsDiscovering(false);
        } finally {
            discoveryRequestInFlight.current = false;
        }
    }, []);

    useEffect(() => {
        let cancelled = false;
        const stream = deviceApi.openDiscoveryStream();

        const initializeDiscovery = async () => {
            if (cancelled || discoveryInitializationStarted.current) {
                return;
            }
            discoveryInitializationStarted.current = true;
            try {
                const snapshot = await deviceApi.getDiscovery();
                if (cancelled) {
                    return;
                }

                setDiscovery(snapshot);
                setIsDiscovering(snapshot.state === "DISCOVERING");
                if (
                    snapshot.state !== "DISCOVERING" &&
                    shouldScanOnOpen(snapshot)
                ) {
                    await discoverDevices();
                } else if (snapshot.state !== "DISCOVERING") {
                    setIsDiscovering(false);
                }
            } catch (error) {
                if (!cancelled) {
                    setActionError(displayError(error));
                    setIsDiscovering(false);
                }
            }
        };

        const handleDiscovery = (event: Event) => {
            try {
                const snapshot = JSON.parse(
                    (event as MessageEvent<string>).data,
                ) as DeviceDiscoveryResponse;
                setDiscovery(snapshot);
                setIsDiscovering(
                    snapshot.state === "DISCOVERING" ||
                        discoveryRequestInFlight.current,
                );
            } catch {
                setActionError(
                    "The backend returned an invalid discovery update",
                );
            }
        };

        stream.addEventListener("discovery", handleDiscovery);
        stream.onopen = () => void initializeDiscovery();
        stream.onerror = () => {
            if (!cancelled && stream.readyState === EventSource.CLOSED) {
                setActionError("Live discovery updates are unavailable");
                setIsDiscovering(false);
            }
        };
        const initialRefresh = window.setTimeout(
            () => void initializeDiscovery(),
            0,
        );

        return () => {
            cancelled = true;
            window.clearTimeout(initialRefresh);
            stream.close();
        };
    }, [discoverDevices]);

    useEffect(() => {
        if (isRide || discovery === null || discovery.state === "DISCOVERING") {
            return;
        }

        if (shouldRefreshDiscovery(discovery)) {
            const refreshTimer = window.setTimeout(() => {
                if (!isRide) {
                    void discoverDevices();
                }
            }, 0);
            return () => window.clearTimeout(refreshTimer);
        }

        if (discovery.lastScanAt === null) {
            return;
        }

        const scannedAt = Date.parse(discovery.lastScanAt);
        if (!Number.isFinite(scannedAt)) {
            return;
        }

        const delay = Math.max(
            0,
            scannedAt + deviceDiscoveryFreshnessMs - Date.now(),
        );
        const freshnessTimer = window.setTimeout(() => {
            if (!isRide) {
                void discoverDevices();
            }
        }, delay);

        return () => window.clearTimeout(freshnessTimer);
    }, [discoverDevices, discovery, isRide]);

    const connectDevice = useCallback(async (deviceId: string) => {
        setConnectingDeviceId(deviceId);
        setActionError(null);
        try {
            setConnection(await deviceApi.connect(deviceId));
        } catch (error) {
            setActionError(displayError(error));
        } finally {
            setConnectingDeviceId(null);
        }
    }, []);

    const disconnectDevice = useCallback(
        async (connectionId: string) => {
            const assignedRoles = rolesAssignedToSource(
                equipment,
                connectionId,
            );
            setActionError(null);
            try {
                await deviceApi.disconnect(connectionId);
                await refreshConnection();
                let roleClearError: unknown = null;
                for (const role of assignedRoles) {
                    try {
                        setEquipment(
                            await trainingApi.clearEquipmentRole(role),
                        );
                    } catch (error) {
                        roleClearError ??= error;
                    }
                }
                await refreshEquipment();
                if (roleClearError !== null) {
                    throw roleClearError;
                }
            } catch (error) {
                setActionError(displayError(error));
            }
        },
        [equipment, refreshConnection, refreshEquipment],
    );

    const selectEquipmentRole = useCallback(
        async (role: RideRole, sourceId: string) => {
            setActionError(null);
            try {
                setEquipment(
                    await trainingApi.selectEquipmentRole(role, sourceId),
                );
                setEquipmentError(null);
            } catch (error) {
                setActionError(displayError(error));
            }
        },
        [],
    );

    const clearEquipmentRole = useCallback(async (role: RideRole) => {
        setActionError(null);
        try {
            setEquipment(await trainingApi.clearEquipmentRole(role));
            setEquipmentError(null);
        } catch (error) {
            setActionError(displayError(error));
        }
    }, []);

    const contextValue = useMemo<AppShellContextValue>(
        () => ({
            connection,
            profile,
            today,
            isPlanLoading,
            planError,
            library,
            isLibraryLoading,
            libraryError,
            discovery,
            isDiscovering,
            connectingDeviceId,
            equipment,
            equipmentLoading,
            equipmentError,
            isEquipmentOpen,
            actionError,
            openEquipment,
            closeEquipment,
            discoverDevices,
            connectDevice,
            disconnectDevice,
            refreshEquipment,
            selectEquipmentRole,
            clearEquipmentRole,
            refreshConnection,
            refreshPlan,
            refreshLibrary,
        }),
        [
            actionError,
            closeEquipment,
            connectDevice,
            connectingDeviceId,
            connection,
            clearEquipmentRole,
            discoverDevices,
            discovery,
            disconnectDevice,
            equipment,
            equipmentError,
            equipmentLoading,
            isDiscovering,
            isEquipmentOpen,
            isLibraryLoading,
            isPlanLoading,
            library,
            libraryError,
            openEquipment,
            planError,
            profile,
            refreshConnection,
            refreshEquipment,
            refreshLibrary,
            refreshPlan,
            selectEquipmentRole,
            today,
        ],
    );

    return (
        <AppShellContext.Provider value={contextValue}>
            <div className="min-h-screen bg-[#0a0d12] text-[#f5f6fb]">
                {!isRide ? (
                    <header className="sticky top-0 z-30 border-b border-white/[0.08] bg-[#0a0d12]/90 pt-[env(safe-area-inset-top)] backdrop-blur-xl">
                        <div className="mx-auto flex min-h-[4.8rem] max-w-[1200px] min-w-0 items-center justify-between gap-4 px-4 sm:px-7 lg:px-12">
                            <PacelineLogo onClick={() => navigate("/")} />
                            <div className="flex min-w-0 flex-1 items-center justify-end gap-2">
                                {isToday || isLibrary ? (
                                    <RefreshPlanButton
                                        isRefreshing={
                                            isToday
                                                ? isPlanLoading
                                                : isLibraryLoading
                                        }
                                        onClick={() =>
                                            void (isToday
                                                ? refreshPlan()
                                                : refreshLibrary())
                                        }
                                    />
                                ) : null}
                                <EquipmentButton
                                    connection={connection}
                                    equipment={equipment}
                                    onClick={openEquipment}
                                />
                            </div>
                        </div>
                    </header>
                ) : null}

                <main className={cn(!isRide && "pb-12")}>
                    <Outlet />
                </main>
                <EquipmentSheet />
            </div>
        </AppShellContext.Provider>
    );
}
