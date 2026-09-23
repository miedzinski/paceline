import type { Device, DeviceEndpoint, DeviceIdentity } from "@/types";

const phaseLabels: Record<string, string> = {
    READY: "Ready",
    DISCOVERING: "Scanning",
    DISCOVERED: "Devices found",
    CONNECTING: "Connecting",
    CONNECTED: "Connected",
    NOT_STARTED: "Not started",
    ACTIVE: "Live",
    PAUSED: "Paused",
    STOPPED: "Stopped",
    COMPLETED: "Completed",
    UNAVAILABLE: "Unavailable",
    FAILED: "Needs attention",
    DISCONNECTED: "Disconnected",
};

export function phaseLabel(phase: string): string {
    return phaseLabels[phase] ?? phase.replaceAll("_", " ");
}

export function phaseHasPulse(phase: string): boolean {
    return ["DISCOVERING", "CONNECTING"].includes(phase);
}

export function shouldShowConnectionBanner({
    hasTrainer,
    telemetryAvailable,
    status,
    paused,
    postRideOpen,
}: {
    hasTrainer: boolean;
    telemetryAvailable: boolean;
    status: string;
    paused: boolean;
    postRideOpen: boolean;
}): boolean {
    if (postRideOpen) {
        return false;
    }

    if (hasTrainer && telemetryAvailable && status === "CONNECTED") {
        return false;
    }

    return !(paused && hasTrainer && status === "CONNECTED");
}

export function formatTimestamp(timestamp: string): string {
    const date = new Date(timestamp);
    if (Number.isNaN(date.getTime())) {
        return "Unknown time";
    }

    return new Intl.DateTimeFormat(undefined, {
        hour: "numeric",
        minute: "2-digit",
        second: "2-digit",
    }).format(date);
}

export function formatTimeOfDay(timestamp: string | null): string | null {
    if (timestamp === null) {
        return null;
    }

    const date = new Date(timestamp);
    if (Number.isNaN(date.getTime())) {
        return null;
    }

    return new Intl.DateTimeFormat(undefined, {
        hour: "numeric",
        minute: "2-digit",
    }).format(date);
}

export function transportLabel(transport: DeviceEndpoint["transport"]): string {
    return transport === "BLUETOOTH" ? "Bluetooth LE" : "LAN";
}

export function deviceEndpointLabel(endpoint: DeviceEndpoint): string {
    if (endpoint.transport === "BLUETOOTH") {
        return endpoint.address ?? "Bluetooth address unavailable";
    }

    if (endpoint.host !== null && endpoint.port !== null) {
        return `${endpoint.host}:${endpoint.port}`;
    }

    return "Local network endpoint unavailable";
}

export function sameDevice(
    connectedDevice: DeviceIdentity | null,
    discoveredDevice: Device,
): boolean {
    if (
        connectedDevice === null ||
        connectedDevice.transport !== discoveredDevice.transport
    ) {
        return false;
    }

    if (connectedDevice.transport === "BLUETOOTH") {
        return (
            connectedDevice.address !== null &&
            connectedDevice.address === discoveredDevice.address
        );
    }

    return (
        connectedDevice.host !== null &&
        connectedDevice.port !== null &&
        connectedDevice.host === discoveredDevice.host &&
        connectedDevice.port === discoveredDevice.port
    );
}

export function formatElapsed(
    startedAt: string | null,
    now = Date.now(),
): string {
    if (startedAt === null) {
        return "00:00";
    }

    const start = new Date(startedAt).getTime();
    if (Number.isNaN(start)) {
        return "00:00";
    }

    return formatDurationSeconds((now - start) / 1000);
}

export function formatDurationSeconds(durationSeconds: number | null): string {
    if (durationSeconds === null || !Number.isFinite(durationSeconds)) {
        return "00:00";
    }

    const elapsedSeconds = Math.max(0, Math.floor(durationSeconds));
    const hours = Math.floor(elapsedSeconds / 3600);
    const minutes = Math.floor((elapsedSeconds % 3600) / 60);
    const seconds = elapsedSeconds % 60;

    if (hours > 0) {
        return `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
    }

    return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}
