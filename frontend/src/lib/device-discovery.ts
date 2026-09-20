import type { DeviceDiscoveryResponse } from "@/types";

export const deviceDiscoveryFreshnessMs = 2 * 60 * 1000;

function scanAgeMs(
    discovery: DeviceDiscoveryResponse | null,
    now: number,
): number | null {
    if (discovery?.lastScanAt === null || discovery?.lastScanAt === undefined) {
        return null;
    }

    const scannedAt = Date.parse(discovery.lastScanAt);
    if (!Number.isFinite(scannedAt)) {
        return null;
    }

    return Math.max(0, now - scannedAt);
}

export function isFreshDiscovery(
    discovery: DeviceDiscoveryResponse | null,
    now = Date.now(),
): boolean {
    if (discovery === null) {
        return false;
    }

    if (
        discovery.state !== "DISCOVERED" &&
        discovery.state !== "UNAVAILABLE" &&
        discovery.state !== "DISCOVERING"
    ) {
        return false;
    }

    if (discovery.state === "DISCOVERING" && discovery.devices.length > 0) {
        return true;
    }

    const age = scanAgeMs(discovery, now);
    return age !== null && age < deviceDiscoveryFreshnessMs;
}

export function shouldRefreshDiscovery(
    discovery: DeviceDiscoveryResponse | null,
    now = Date.now(),
): boolean {
    if (discovery === null || discovery.state === "DISCOVERING") {
        return false;
    }

    return (
        (discovery.state === "DISCOVERED" ||
            discovery.state === "UNAVAILABLE") &&
        !isFreshDiscovery(discovery, now)
    );
}

export function shouldScanOnOpen(
    discovery: DeviceDiscoveryResponse | null,
    now = Date.now(),
): boolean {
    if (discovery === null || discovery.state === "DISCOVERING") {
        return false;
    }

    return !isFreshDiscovery(discovery, now);
}

export function formatDiscoveryAge(
    discovery: DeviceDiscoveryResponse | null,
    now = Date.now(),
): string | null {
    const age = scanAgeMs(discovery, now);
    if (age === null) {
        return null;
    }

    const seconds = Math.floor(age / 1000);
    if (seconds < 5) {
        return "Scanned just now";
    }

    if (seconds < 60) {
        return `Scanned ${seconds}s ago`;
    }

    const minutes = Math.floor(seconds / 60);
    return `Scanned ${minutes}m ago`;
}

export function formatDiscoveryStatus(
    discovery: DeviceDiscoveryResponse | null,
    isScanning: boolean,
    now = Date.now(),
): string | null {
    if (isScanning) {
        return "Scanning";
    }

    return formatDiscoveryAge(discovery, now);
}
