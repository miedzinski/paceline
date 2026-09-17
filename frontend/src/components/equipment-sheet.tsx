import {
    AlertTriangle,
    Bluetooth,
    Check,
    ChevronRight,
    CircleHelp,
    LoaderCircle,
    Radar,
    ScanLine,
    Unplug,
    Wifi,
    X,
} from "lucide-react";
import { useEffect } from "react";
import { useAppShell } from "@/lib/app-shell";
import {
    deviceEndpointLabel,
    sameDevice,
    transportLabel,
} from "@/lib/connection";
import type { ConnectedConnection, Device } from "@/types";

function capabilityLabel(capability: string): string {
    switch (capability) {
        case "INDOOR_BIKE_TELEMETRY":
            return "Bike data";
        case "ERG_POWER_CONTROL":
            return "ERG control";
        case "HEART_RATE":
            return "Heart rate";
        default:
            return capability.replaceAll("_", " ").toLowerCase();
    }
}

function DeviceIcon({ transport }: { transport: Device["transport"] }) {
    const Icon = transport === "BLUETOOTH" ? Bluetooth : Wifi;
    return <Icon aria-hidden="true" className="size-5" />;
}

function connectionForDevice(
    device: Device,
    connections: ConnectedConnection[],
): ConnectedConnection | null {
    return (
        connections.find(
            (connection) =>
                connection.state === "CONNECTED" &&
                sameDevice(connection.device, device),
        ) ?? null
    );
}

function ConnectedDeviceRow({
    connection,
    onDisconnect,
}: {
    connection: ConnectedConnection;
    onDisconnect: (connectionId: string) => void;
}) {
    return (
        <div className="rounded-2xl border border-white/[0.09] bg-white/[0.035] p-4">
            <div className="flex items-start gap-3">
                <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-[#7e87ff]/15 text-[#aeb4ff]">
                    <DeviceIcon transport={connection.device.transport} />
                </div>
                <div className="min-w-0 flex-1">
                    <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                            <p className="truncate text-sm font-bold text-white/90">
                                {connection.device.name}
                            </p>
                            <p className="mt-1 text-xs text-white/35">
                                {transportLabel(connection.device.transport)} ·
                                Live
                            </p>
                        </div>
                        <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-[#73d6a1]/12 px-2.5 py-1 text-[0.62rem] font-bold text-[#73d6a1]">
                            <span className="size-1.5 rounded-full bg-[#73d6a1]" />
                            Connected
                        </span>
                    </div>
                    <div className="mt-3 flex flex-wrap gap-1.5">
                        {connection.capabilities.map((capability) => (
                            <span
                                key={capability}
                                className="rounded-lg bg-white/[0.06] px-2 py-1 text-[0.62rem] font-semibold text-white/45"
                            >
                                {capabilityLabel(capability)}
                            </span>
                        ))}
                    </div>
                    <button
                        type="button"
                        onClick={() => onDisconnect(connection.id)}
                        className="mt-3 inline-flex min-h-8 items-center gap-1.5 rounded-lg px-2 text-xs font-semibold text-[#ffaaa1] transition-colors hover:bg-[#ff8068]/10 focus-visible:ring-2 focus-visible:ring-[#ff8068] focus-visible:outline-none"
                    >
                        <Unplug aria-hidden="true" className="size-3.5" />
                        Disconnect
                    </button>
                </div>
            </div>
        </div>
    );
}

function DiscoveredDeviceRow({
    device,
    connected,
    isConnecting,
    disabled,
    onConnect,
}: {
    device: Device;
    connected: boolean;
    isConnecting: boolean;
    disabled: boolean;
    onConnect: (deviceId: string) => void;
}) {
    return (
        <div className="flex items-center gap-3 rounded-2xl border border-white/[0.09] bg-white/[0.02] p-3.5 transition-colors hover:border-white/[0.18] hover:bg-white/[0.045]">
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
            ) : (
                <button
                    type="button"
                    onClick={() => onConnect(device.id)}
                    disabled={disabled}
                    className="inline-flex min-h-9 shrink-0 items-center gap-1.5 rounded-xl bg-[#7e87ff] px-3 text-xs font-bold text-[#0b0d14] transition-colors hover:bg-[#aeb4ff] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-50"
                >
                    {isConnecting ? (
                        <LoaderCircle
                            aria-hidden="true"
                            className="size-3.5 animate-spin"
                        />
                    ) : null}
                    {isConnecting ? "Connecting" : "Connect"}
                    {!isConnecting ? (
                        <ChevronRight aria-hidden="true" className="size-3.5" />
                    ) : null}
                </button>
            )}
        </div>
    );
}

export function EquipmentSheet() {
    const {
        connection,
        discovery,
        isDiscovering,
        connectingDeviceId,
        isEquipmentOpen,
        actionError,
        closeEquipment,
        discoverDevices,
        connectDevice,
        disconnectDevice,
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

    const connected = connection.connections.filter(
        (item) => item.state === "CONNECTED",
    );
    const devices = discovery?.devices ?? [];
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
                className="relative flex max-h-[calc(100dvh-2rem)] w-full max-w-[34rem] flex-col overflow-hidden rounded-[2rem] border border-white/[0.1] bg-[#11151e] text-white shadow-[0_28px_80px_rgba(0,0,0,0.45)] sm:max-h-[calc(100dvh-3rem)]"
                onMouseDown={(event) => event.stopPropagation()}
            >
                <div className="flex items-center justify-between border-b border-white/[0.08] px-5 py-4 sm:px-7 sm:py-5">
                    <div>
                        <p className="text-[0.6rem] font-bold tracking-[0.2em] text-[#8f98ad] uppercase">
                            Local connections
                        </p>
                        <h2
                            id="equipment-modal-title"
                            className="mt-2 text-2xl font-black tracking-[-0.06em]"
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

                <div className="overflow-y-auto px-5 py-5 sm:px-7 sm:py-6">
                    <section
                        className="mt-1"
                        aria-labelledby="connected-heading"
                    >
                        <div className="flex items-end justify-between gap-3">
                            <div>
                                <p className="text-[0.6rem] font-bold tracking-[0.2em] text-[#8f98ad] uppercase">
                                    Ready now
                                </p>
                                <h3
                                    id="connected-heading"
                                    className="mt-2 text-xl font-black tracking-[-0.06em]"
                                >
                                    Connected equipment
                                </h3>
                            </div>
                            <span className="rounded-full bg-white/[0.07] px-2.5 py-1 text-xs font-bold text-white/45">
                                {connected.length}
                            </span>
                        </div>
                        {connected.length > 0 ? (
                            <div className="mt-3 grid gap-2.5">
                                {connected.map((item) => (
                                    <ConnectedDeviceRow
                                        key={item.id}
                                        connection={item}
                                        onDisconnect={(connectionId) =>
                                            void disconnectDevice(connectionId)
                                        }
                                    />
                                ))}
                            </div>
                        ) : (
                            <div className="mt-3 rounded-2xl border border-dashed border-white/[0.14] bg-white/[0.025] p-5 text-sm text-white/42">
                                No trainer or heart-rate sensor is connected.
                            </div>
                        )}
                    </section>

                    <section
                        className="mt-8"
                        aria-labelledby="discover-heading"
                    >
                        <div className="flex items-end justify-between gap-3">
                            <div>
                                <p className="text-[0.6rem] font-bold tracking-[0.2em] text-[#8f98ad] uppercase">
                                    Local network & Bluetooth
                                </p>
                                <h3
                                    id="discover-heading"
                                    className="mt-2 text-xl font-black tracking-[-0.06em]"
                                >
                                    Find equipment
                                </h3>
                            </div>
                            <button
                                type="button"
                                onClick={() => void discoverDevices()}
                                disabled={isDiscovering}
                                className="inline-flex min-h-10 items-center gap-1.5 rounded-xl border border-white/[0.12] bg-white/[0.05] px-3 text-xs font-bold text-white/65 transition hover:border-white/[0.22] hover:bg-white/[0.09] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-60"
                            >
                                {isDiscovering ? (
                                    <LoaderCircle
                                        aria-hidden="true"
                                        className="size-3.5 animate-spin"
                                    />
                                ) : (
                                    <ScanLine
                                        aria-hidden="true"
                                        className="size-3.5"
                                    />
                                )}
                                {isDiscovering ? "Scanning" : "Scan"}
                            </button>
                        </div>

                        {actionError ? (
                            <div className="mt-3 flex items-start gap-2 rounded-2xl border border-[#703e49] bg-[#2a1821] p-3 text-xs leading-5 text-[#ffc4c5]">
                                <AlertTriangle
                                    aria-hidden="true"
                                    className="mt-0.5 size-4 shrink-0"
                                />
                                <span>{actionError}</span>
                            </div>
                        ) : null}

                        {discovery?.failure ? (
                            <div className="mt-3 flex items-start gap-2 rounded-2xl border border-[#806335] bg-[#2b2418] p-3 text-xs leading-5 text-[#f5d28c]">
                                <CircleHelp
                                    aria-hidden="true"
                                    className="mt-0.5 size-4 shrink-0"
                                />
                                <span>{discovery.failure.message}</span>
                            </div>
                        ) : null}

                        {devices.length > 0 ? (
                            <div className="mt-3 grid gap-2.5">
                                {devices.map((device) => {
                                    const connectedDevice = connectionForDevice(
                                        device,
                                        connection.connections,
                                    );
                                    return (
                                        <DiscoveredDeviceRow
                                            key={device.id}
                                            device={device}
                                            connected={connectedDevice !== null}
                                            isConnecting={
                                                connectingDeviceId === device.id
                                            }
                                            disabled={
                                                isConnecting ||
                                                connectedDevice !== null
                                            }
                                            onConnect={(deviceId) =>
                                                void connectDevice(deviceId)
                                            }
                                        />
                                    );
                                })}
                            </div>
                        ) : (
                            <div className="mt-3 rounded-2xl border border-dashed border-white/[0.14] bg-white/[0.025] p-6 text-center">
                                <Radar
                                    aria-hidden="true"
                                    className="mx-auto size-7 text-white/25"
                                />
                                <p className="mt-3 text-sm font-bold text-white/65">
                                    {discovery
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
