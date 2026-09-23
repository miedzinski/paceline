import {
    AlertTriangle,
    ArrowLeft,
    Bluetooth,
    Check,
    ChevronRight,
    CircleHelp,
    Gauge,
    HeartPulse,
    LoaderCircle,
    Radar,
    ScanLine,
    Settings2,
    Unplug,
    Network,
    X,
    Zap,
} from "lucide-react";
import { useEffect } from "react";
import { useRef, useState } from "react";
import { useAppShell } from "@/lib/app-shell";
import { cn } from "@/lib/utils";
import {
    formatDiscoveryStatus,
    isFreshDiscovery,
} from "@/lib/device-discovery";
import {
    deviceEndpointLabel,
    sameDevice,
    transportLabel,
} from "@/lib/connection";
import {
    roleSourceIds,
    roleStatusLabel,
    rideRoleLabel,
    rideRoleOrder,
    sourceName,
    visibleReadinessReasons,
} from "@/lib/ride-equipment";
import type {
    DeviceConnection,
    Device,
    RideEquipmentResponse,
    RideRole,
    RideRoleState,
} from "@/types";

const deviceActionButtonClass =
    "inline-flex h-9 shrink-0 items-center justify-center gap-1.5 rounded-xl bg-[#7e87ff] px-3 text-xs font-bold text-[#0b0d14] transition-colors hover:bg-[#aeb4ff] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-50";

function capabilityLabel(capability: string): string {
    switch (capability) {
        case "CYCLING_TELEMETRY":
            return "Power + cadence";
        case "POWER_TELEMETRY":
            return "Power data";
        case "CADENCE_TELEMETRY":
            return "Cadence data";
        case "ERG_POWER_CONTROL":
            return "ERG control";
        case "HEART_RATE":
            return "Heart rate";
        default:
            return capability.replaceAll("_", " ").toLowerCase();
    }
}

function DeviceIcon({ transport }: { transport: Device["transport"] }) {
    const Icon = transport === "BLUETOOTH" ? Bluetooth : Network;
    return <Icon aria-hidden="true" className="size-5" />;
}

function connectionForDevice(
    device: Device,
    connections: DeviceConnection[],
): DeviceConnection | null {
    return (
        connections.find((connection) =>
            sameDevice(connection.device, device),
        ) ?? null
    );
}

function connectionStateLabel(state: DeviceConnection["state"]): string {
    switch (state) {
        case "CONNECTED":
            return "Connected";
        case "CONNECTING":
            return "Connecting";
        case "DISCONNECTED":
            return "Disconnected";
        case "FAILED":
            return "Connection failed";
        default:
            return "Unknown";
    }
}

function connectionStateOrder(state: DeviceConnection["state"]): number {
    switch (state) {
        case "CONNECTED":
            return 0;
        case "CONNECTING":
            return 1;
        case "DISCONNECTED":
            return 2;
        case "FAILED":
            return 3;
        default:
            return 4;
    }
}

function ConnectedDeviceRow({
    connection,
    onDisconnect,
}: {
    connection: DeviceConnection;
    onDisconnect: (connectionId: string) => void;
}) {
    const connected = connection.state === "CONNECTED";
    const stateLabel = connectionStateLabel(connection.state);
    return (
        <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-white/[0.09] bg-white/[0.035] p-3.5 transition-colors hover:border-white/[0.18] hover:bg-white/[0.05]">
            <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-[#7e87ff]/15 text-[#aeb4ff]">
                <DeviceIcon transport={connection.device.transport} />
            </div>
            <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-bold text-white/90">
                    {connection.device.name}
                </p>
                <p className="mt-1 truncate text-xs text-white/35">
                    {transportLabel(connection.device.transport)}
                    <span className="mx-1.5 text-white/15">·</span>
                    {stateLabel}
                </p>
                {connected && connection.capabilities.length > 0 ? (
                    <div className="mt-1.5 flex flex-wrap gap-1.5">
                        {connection.capabilities.map((capability) => (
                            <span
                                key={capability}
                                className="rounded-lg bg-white/[0.06] px-2 py-1 text-[0.62rem] font-semibold text-white/45"
                            >
                                {capabilityLabel(capability)}
                            </span>
                        ))}
                    </div>
                ) : null}
            </div>
            <button
                type="button"
                onClick={() => onDisconnect(connection.id)}
                aria-label={`${connected ? "Disconnect" : "Remove"} ${connection.device.name}`}
                title={connected ? "Disconnect" : "Remove connection"}
                className={deviceActionButtonClass}
            >
                {connected ? (
                    <Unplug aria-hidden="true" className="size-3.5" />
                ) : (
                    <X aria-hidden="true" className="size-3.5" />
                )}
                {connected ? "Disconnect" : "Remove"}
            </button>
        </div>
    );
}

function DiscoveredDeviceRow({
    device,
    connection,
    isConnecting,
    disabled,
    onConnect,
}: {
    device: Device;
    connection: DeviceConnection | null;
    isConnecting: boolean;
    disabled: boolean;
    onConnect: (deviceId: string) => void;
}) {
    const connected = connection?.state === "CONNECTED";
    const connecting = connection?.state === "CONNECTING";
    const stateLabel =
        connection === null ? null : connectionStateLabel(connection.state);
    return (
        <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-white/[0.09] bg-white/[0.02] p-3.5 transition-colors hover:border-white/[0.18] hover:bg-white/[0.045]">
            <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-white/[0.06] text-white/45">
                <DeviceIcon transport={device.transport} />
            </div>
            <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-bold text-white/85">
                    {device.name}
                </p>
                <p className="mt-1 truncate text-xs text-white/35">
                    {transportLabel(device.transport)}
                    <span className="mx-1.5 text-white/15">·</span>
                    {deviceEndpointLabel(device)}
                </p>
            </div>
            {connected ? (
                <span className="inline-flex min-h-9 shrink-0 items-center gap-1.5 rounded-xl bg-[#73d6a1]/12 px-3 text-xs font-bold text-[#73d6a1]">
                    <Check aria-hidden="true" className="size-3.5" />
                    Connected
                </span>
            ) : connection !== null && connecting ? (
                <span className="inline-flex min-h-9 shrink-0 items-center gap-1.5 rounded-xl bg-[#f0b766]/12 px-3 text-xs font-bold text-[#f0b766]">
                    <LoaderCircle
                        aria-hidden="true"
                        className="size-3.5 animate-spin"
                    />
                    Connecting
                </span>
            ) : (
                <button
                    type="button"
                    onClick={() => onConnect(device.id)}
                    disabled={disabled}
                    className={deviceActionButtonClass}
                >
                    {isConnecting ? (
                        <LoaderCircle
                            aria-hidden="true"
                            className="size-3.5 animate-spin"
                        />
                    ) : null}
                    {isConnecting
                        ? "Connecting"
                        : stateLabel === null
                          ? "Connect"
                          : "Reconnect"}
                    {!isConnecting ? (
                        <ChevronRight aria-hidden="true" className="size-3.5" />
                    ) : null}
                </button>
            )}
        </div>
    );
}

function RoleIcon({ role }: { role: RideRole }) {
    switch (role) {
        case "RESISTANCE_CONTROL":
            return <Settings2 aria-hidden="true" className="size-4" />;
        case "POWER":
            return <Zap aria-hidden="true" className="size-4" />;
        case "CADENCE":
            return <Gauge aria-hidden="true" className="size-4" />;
        case "HEART_RATE":
            return <HeartPulse aria-hidden="true" className="size-4" />;
    }
}

interface SourceOption {
    id: string;
    name: string;
    unavailable: boolean;
}

function SourceSelectionSheet({
    role,
    sourceId,
    options,
    onSelect,
    onClose,
}: {
    role: RideRoleState;
    sourceId: string | null;
    options: SourceOption[];
    onSelect: (sourceId: string) => void;
    onClose: () => void;
}) {
    const listRef = useRef<HTMLDivElement>(null);
    const sheetId = `ride-source-sheet-${role.role.toLowerCase()}`;

    useEffect(() => {
        const focusHandle = window.setTimeout(() => {
            const selectedOption =
                listRef.current?.querySelector<HTMLButtonElement>(
                    '[data-source-option="true"][aria-checked="true"]:not(:disabled)',
                );
            const firstOption =
                listRef.current?.querySelector<HTMLButtonElement>(
                    '[data-source-option="true"]:not(:disabled)',
                );
            (selectedOption ?? firstOption)?.focus();
        }, 0);
        return () => {
            window.clearTimeout(focusHandle);
        };
    }, []);

    return (
        <div
            className="fixed inset-0 z-[120] flex items-end justify-center bg-[#05070c]/72 p-4 backdrop-blur-sm sm:items-center sm:p-6"
            role="presentation"
            onMouseDown={(event) => {
                if (event.target === event.currentTarget) {
                    onClose();
                }
            }}
        >
            <section
                id={sheetId}
                role="dialog"
                aria-modal="true"
                aria-labelledby={`${sheetId}-title`}
                onMouseDown={(event) => event.stopPropagation()}
                onKeyDown={(event) => {
                    if (event.key === "Escape") {
                        event.stopPropagation();
                        onClose();
                    }
                }}
                className="flex max-h-[calc(100dvh-2rem)] min-h-0 w-full max-w-[28rem] flex-col overflow-hidden rounded-[1.75rem] border border-white/[0.1] bg-[#171c27] text-white shadow-[0_28px_80px_rgba(0,0,0,0.5)] sm:max-h-[calc(100dvh-3rem)]"
            >
                <div className="flex items-center gap-3 border-b border-white/[0.08] px-4 py-3.5 sm:px-5 sm:py-4">
                    <button
                        type="button"
                        onClick={onClose}
                        aria-label="Back to equipment"
                        className="grid size-10 shrink-0 place-items-center rounded-xl text-white/50 transition-colors hover:bg-white/[0.08] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
                    >
                        <ArrowLeft aria-hidden="true" className="size-5" />
                    </button>
                    <h2
                        id={`${sheetId}-title`}
                        className="min-w-0 text-lg font-black tracking-[-0.04em]"
                    >
                        Choose {rideRoleLabel(role.role)} source
                    </h2>
                </div>

                <div
                    ref={listRef}
                    className="min-h-0 flex-1 overflow-y-auto p-3 sm:p-4"
                >
                    <div
                        role="radiogroup"
                        aria-label={`${rideRoleLabel(role.role)} sources`}
                        className="grid gap-2"
                    >
                        {options.map((option) => {
                            const selected = option.id === sourceId;
                            return (
                                <button
                                    key={option.id}
                                    type="button"
                                    role="radio"
                                    aria-checked={selected}
                                    data-source-option="true"
                                    disabled={option.unavailable}
                                    onClick={() => {
                                        onSelect(option.id);
                                        onClose();
                                    }}
                                    className={cn(
                                        "flex min-h-12 min-w-0 items-center gap-3 rounded-xl border px-3.5 text-left text-sm font-bold transition focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none",
                                        selected
                                            ? "border-[#7e87ff]/55 bg-[#7e87ff]/12 text-white"
                                            : option.unavailable
                                              ? "cursor-not-allowed border-white/[0.08] bg-white/[0.02] text-white/35"
                                              : "border-white/[0.08] bg-white/[0.035] text-white/75 hover:border-white/[0.18] hover:bg-white/[0.08]",
                                    )}
                                >
                                    <span className="min-w-0 flex-1 break-words">
                                        {option.name}
                                    </span>
                                    {selected ? (
                                        <Check
                                            aria-hidden="true"
                                            className="size-4 shrink-0 text-[#73d6a1]"
                                        />
                                    ) : null}
                                </button>
                            );
                        })}
                    </div>
                </div>
            </section>
        </div>
    );
}

function ClearSourceButton({
    role,
    onClear,
    className,
}: {
    role: RideRole;
    onClear: (role: RideRole) => void;
    className?: string;
}) {
    return (
        <button
            type="button"
            onClick={() => onClear(role)}
            aria-label={`Clear ${rideRoleLabel(role)} source`}
            title="Clear source"
            className={cn(
                "grid size-10 shrink-0 place-items-center rounded-xl text-white/35 transition hover:bg-white/[0.07] hover:text-white/70 focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none",
                className,
            )}
        >
            <X aria-hidden="true" className="size-4" />
        </button>
    );
}

function RoleAssignmentRow({
    equipment,
    role,
    onSelect,
    onClear,
}: {
    equipment: RideEquipmentResponse;
    role: RideRoleState;
    onSelect: (role: RideRole, sourceId: string) => void;
    onClear: (role: RideRole) => void;
}) {
    const [sourceSheetOpen, setSourceSheetOpen] = useState(false);
    const sourceTileRef = useRef<HTMLButtonElement>(null);
    const sourceIds = roleSourceIds(role);
    const assignedName = sourceName(equipment, role.sourceId);
    const sourceOptions = sourceIds.map((sourceId) => {
        const source = equipment.sources.find(
            (candidate) => candidate.id === sourceId,
        );
        return {
            id: sourceId,
            name: source?.name ?? "Unavailable source",
            unavailable: source === undefined,
        };
    });
    const roleValueLabel =
        sourceOptions.length === 0
            ? (assignedName ?? roleStatusLabel(role))
            : null;
    const closeSourceSheet = () => {
        setSourceSheetOpen(false);
        window.setTimeout(() => sourceTileRef.current?.focus(), 0);
    };

    return (
        <div className="rounded-2xl border border-white/[0.08] bg-white/[0.025] p-3">
            <div className="relative">
                {sourceOptions.length > 0 ? (
                    <button
                        ref={sourceTileRef}
                        type="button"
                        onClick={() => setSourceSheetOpen(true)}
                        aria-label={`Choose ${rideRoleLabel(role.role)} source`}
                        className="block w-full min-w-0 rounded-xl text-left outline-none focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:ring-offset-2 focus-visible:ring-offset-[#12161f]"
                    >
                        <span className="flex min-w-0 items-center gap-3 pr-8">
                            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-white/[0.06] text-white/50">
                                <RoleIcon role={role.role} />
                            </span>
                            <span className="min-w-0 flex-1">
                                <span className="block text-xs font-bold text-white/45">
                                    {rideRoleLabel(role.role)}
                                </span>
                            </span>
                        </span>
                        <span className="mt-2 block text-sm font-bold break-words text-white/85">
                            {assignedName ?? roleStatusLabel(role)}
                        </span>
                    </button>
                ) : (
                    <div className="pr-8">
                        <div className="flex min-w-0 items-center gap-3">
                            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-white/[0.06] text-white/50">
                                <RoleIcon role={role.role} />
                            </span>
                            <p className="min-w-0 text-xs font-bold text-white/45">
                                {rideRoleLabel(role.role)}
                            </p>
                        </div>
                        {roleValueLabel !== null ? (
                            <p className="mt-2 text-sm font-bold break-words text-white/85">
                                {roleValueLabel}
                            </p>
                        ) : null}
                    </div>
                )}
                {role.sourceId !== null ? (
                    <ClearSourceButton
                        role={role.role}
                        onClear={onClear}
                        className="absolute -top-1 -right-1 size-8"
                    />
                ) : null}
            </div>

            {sourceSheetOpen ? (
                <SourceSelectionSheet
                    role={role}
                    sourceId={role.sourceId}
                    options={sourceOptions}
                    onSelect={(sourceId) => {
                        onSelect(role.role, sourceId);
                    }}
                    onClose={closeSourceSheet}
                />
            ) : null}
        </div>
    );
}

function RoleAssignmentSection({
    equipment,
    onSelect,
    onClear,
}: {
    equipment: RideEquipmentResponse;
    onSelect: (role: RideRole, sourceId: string) => void;
    onClear: (role: RideRole) => void;
}) {
    const roles = rideRoleOrder
        .map((role) =>
            equipment.roles.find((candidate) => candidate.role === role),
        )
        .filter((role): role is RideRoleState => role !== undefined);
    const readinessReasons = visibleReadinessReasons(equipment);

    return (
        <section aria-labelledby="ride-sources-heading">
            <h3
                id="ride-sources-heading"
                className="text-xl font-black tracking-[-0.06em]"
            >
                Roles
            </h3>
            <div className="mt-4 grid grid-cols-2 gap-2.5">
                {roles.map((role) => (
                    <RoleAssignmentRow
                        key={role.role}
                        equipment={equipment}
                        role={role}
                        onSelect={onSelect}
                        onClear={onClear}
                    />
                ))}
            </div>
            {readinessReasons.length > 0 ? (
                <div className="mt-3 rounded-2xl border border-[#806335] bg-[#2b2418] p-3 text-xs leading-5 text-[#f5d28c]">
                    {readinessReasons.map((reason) => (
                        <p key={`${reason.code}-${reason.role}`}>
                            {reason.message}
                        </p>
                    ))}
                </div>
            ) : null}
        </section>
    );
}

export function EquipmentSheet() {
    const {
        connection,
        discovery,
        isDiscovering,
        connectingDeviceId,
        equipment,
        equipmentLoading,
        equipmentError,
        isEquipmentOpen,
        actionError,
        closeEquipment,
        discoverDevices,
        connectDevice,
        disconnectDevice,
        selectEquipmentRole,
        clearEquipmentRole,
    } = useAppShell();

    useEffect(() => {
        if (!isEquipmentOpen) {
            return;
        }

        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === "Escape") {
                closeEquipment();
            }
        };
        const previousOverflow = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        window.addEventListener("keydown", handleKeyDown);

        return () => {
            document.body.style.overflow = previousOverflow;
            window.removeEventListener("keydown", handleKeyDown);
        };
    }, [closeEquipment, isEquipmentOpen]);

    if (!isEquipmentOpen) {
        return null;
    }

    const connections = connection.connections;
    const devices = discovery?.devices ?? [];
    const hasFreshDiscovery = isFreshDiscovery(discovery);
    const scanIsPending = isDiscovering || discovery?.state === "DISCOVERING";
    const discoveryStatus = formatDiscoveryStatus(discovery, scanIsPending);
    const discoveryStatusHasDots = discoveryStatus === "Scanning";
    const orderedConnections = [...connections].sort(
        (left, right) =>
            connectionStateOrder(left.state) -
            connectionStateOrder(right.state),
    );
    const nearbyDevices = (hasFreshDiscovery ? devices : []).filter(
        (device) => {
            const deviceConnection = connectionForDevice(device, connections);
            return (
                deviceConnection === null ||
                (deviceConnection.state !== "CONNECTED" &&
                    deviceConnection.state !== "CONNECTING")
            );
        },
    );
    const isConnecting = connectingDeviceId !== null;

    return (
        <div
            className="fixed inset-0 z-[100] flex items-center justify-center bg-[#05070c]/78 p-4 backdrop-blur-sm sm:p-6"
            role="presentation"
            onMouseDown={(event) => {
                if (event.target === event.currentTarget) {
                    closeEquipment();
                }
            }}
        >
            <section
                role="dialog"
                aria-modal="true"
                aria-labelledby="equipment-modal-title"
                className="relative flex h-[calc(100dvh-2rem)] min-h-0 w-full max-w-[46rem] flex-col overflow-hidden rounded-[2rem] border border-white/[0.1] bg-[#11151e] text-white shadow-[0_28px_80px_rgba(0,0,0,0.45)] sm:h-[calc(100dvh-3rem)]"
                onMouseDown={(event) => event.stopPropagation()}
            >
                <div className="flex items-center justify-between border-b border-white/[0.08] px-5 py-4 sm:px-7 sm:py-5">
                    <div>
                        <h2
                            id="equipment-modal-title"
                            className="text-2xl font-black tracking-[-0.06em]"
                        >
                            Equipment
                        </h2>
                    </div>
                    <button
                        type="button"
                        onClick={closeEquipment}
                        aria-label="Close equipment modal"
                        className="grid size-10 place-items-center rounded-2xl text-white/40 transition-colors hover:bg-white/[0.08] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
                    >
                        <X aria-hidden="true" className="size-5" />
                    </button>
                </div>

                <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-7 sm:py-6">
                    {equipment !== null ? (
                        <RoleAssignmentSection
                            equipment={equipment}
                            onSelect={(role, sourceId) =>
                                void selectEquipmentRole(role, sourceId)
                            }
                            onClear={(role) => void clearEquipmentRole(role)}
                        />
                    ) : (
                        <div className="rounded-2xl border border-dashed border-white/[0.14] bg-white/[0.025] p-5 text-sm text-white/42">
                            {equipmentLoading
                                ? "Loading connected source assignments…"
                                : (equipmentError ??
                                  "Ride source assignments are unavailable.")}
                        </div>
                    )}

                    {actionError ? (
                        <div className="mt-4 flex items-start gap-2 rounded-2xl border border-[#703e49] bg-[#2a1821] p-3 text-xs leading-5 text-[#ffc4c5]">
                            <AlertTriangle
                                aria-hidden="true"
                                className="mt-0.5 size-4 shrink-0"
                            />
                            <span>{actionError}</span>
                        </div>
                    ) : null}

                    <section className="mt-8" aria-labelledby="devices-heading">
                        <div className="flex items-end justify-between gap-3">
                            <h3
                                id="devices-heading"
                                className="text-xl font-black tracking-[-0.06em]"
                            >
                                Devices
                            </h3>
                            {discoveryStatus ? (
                                <p className="text-[0.68rem] font-semibold text-white/35">
                                    {discoveryStatusHasDots ? (
                                        <>
                                            {discoveryStatus}
                                            <span
                                                aria-hidden="true"
                                                className="discovery-status-dots"
                                            >
                                                <span>.</span>
                                                <span className="discovery-status-dot-second">
                                                    .
                                                </span>
                                                <span className="discovery-status-dot-third">
                                                    .
                                                </span>
                                            </span>
                                        </>
                                    ) : (
                                        discoveryStatus
                                    )}
                                </p>
                            ) : null}
                            <button
                                type="button"
                                onClick={() => void discoverDevices()}
                                disabled={scanIsPending}
                                className="inline-flex min-h-10 items-center gap-1.5 rounded-xl border border-white/[0.12] bg-white/[0.05] px-3 text-xs font-bold text-white/65 transition-colors hover:border-white/[0.22] hover:bg-white/[0.09] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-60"
                            >
                                <ScanLine
                                    aria-hidden="true"
                                    className="size-3.5"
                                />
                                Scan
                            </button>
                        </div>

                        {discovery?.failure ? (
                            <div className="mt-3 flex items-start gap-2 rounded-2xl border border-[#806335] bg-[#2b2418] p-3 text-xs leading-5 text-[#f5d28c]">
                                <CircleHelp
                                    aria-hidden="true"
                                    className="mt-0.5 size-4 shrink-0"
                                />
                                <span>{discovery.failure.message}</span>
                            </div>
                        ) : null}

                        {orderedConnections.length > 0 ||
                        nearbyDevices.length > 0 ? (
                            <div className="mt-4 grid gap-2.5">
                                {orderedConnections.map((item) => (
                                    <ConnectedDeviceRow
                                        key={`connection-${item.id}`}
                                        connection={item}
                                        onDisconnect={(connectionId) =>
                                            void disconnectDevice(connectionId)
                                        }
                                    />
                                ))}
                                {nearbyDevices.map((device) => {
                                    const deviceConnection =
                                        connectionForDevice(
                                            device,
                                            connections,
                                        );
                                    return (
                                        <DiscoveredDeviceRow
                                            key={`nearby-${device.id}`}
                                            device={device}
                                            connection={deviceConnection}
                                            isConnecting={
                                                connectingDeviceId === device.id
                                            }
                                            disabled={
                                                isConnecting ||
                                                deviceConnection?.state ===
                                                    "CONNECTED" ||
                                                deviceConnection?.state ===
                                                    "CONNECTING"
                                            }
                                            onConnect={(deviceId) =>
                                                void connectDevice(deviceId)
                                            }
                                        />
                                    );
                                })}
                            </div>
                        ) : (
                            <div className="mt-4 rounded-2xl border border-dashed border-white/[0.14] bg-white/[0.025] p-6 text-center">
                                <Radar
                                    aria-hidden="true"
                                    className="mx-auto size-7 text-white/25"
                                />
                                <p className="mt-3 text-sm font-bold text-white/65">
                                    {scanIsPending
                                        ? "Scanning nearby devices…"
                                        : discovery?.state === "FAILED"
                                          ? "Scan failed"
                                          : discovery
                                            ? "Nothing found nearby"
                                            : "Scan when your trainer is awake"}
                                </p>
                                {discovery ? (
                                    <p className="mt-1 text-xs leading-5 text-white/35">
                                        Make sure it is powered and reachable
                                        from this machine.
                                    </p>
                                ) : null}
                            </div>
                        )}
                    </section>
                </div>
            </section>
        </div>
    );
}
