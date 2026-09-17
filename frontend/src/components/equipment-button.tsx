import { ChevronDown, Radio } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ConnectedConnection, DeviceConnectionResponse } from "@/types";

function connectedTrainer(
    connections: ConnectedConnection[],
): ConnectedConnection | null {
    return (
        connections.find(
            (connection) =>
                connection.state === "CONNECTED" &&
                connection.capabilities.includes("INDOOR_BIKE_TELEMETRY"),
        ) ?? null
    );
}

function equipmentLabel(connection: DeviceConnectionResponse): string {
    return (
        connectedTrainer(connection.connections)?.device.name ??
        connection.connections.find((item) => item.state === "CONNECTED")
            ?.device.name ??
        "Connect equipment"
    );
}

export function EquipmentButton({
    connection,
    onClick,
}: {
    connection: DeviceConnectionResponse;
    onClick: () => void;
}) {
    const trainer = connectedTrainer(connection.connections);
    const connected = connection.connections.some(
        (item) => item.state === "CONNECTED",
    );

    return (
        <button
            type="button"
            onClick={onClick}
            aria-label={
                connected
                    ? `Equipment connected: ${equipmentLabel(connection)}`
                    : "Connect equipment"
            }
            className="group inline-flex min-h-12 max-w-[17rem] min-w-0 items-center gap-3 rounded-2xl border border-white/[0.1] bg-white/[0.055] px-3 text-left transition-colors hover:border-white/[0.18] hover:bg-white/[0.09] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
        >
            <span className="relative grid size-8 shrink-0 place-items-center rounded-xl bg-white/[0.08] text-white/65">
                <Radio aria-hidden="true" className="size-4" />
                <span
                    className={cn(
                        "absolute -right-0.5 -bottom-0.5 size-2.5 rounded-full border-2 border-[#11141b]",
                        connected ? "bg-[#73d6a1]" : "bg-[#f0b766]",
                    )}
                />
            </span>
            <span className="min-w-0 flex-1">
                <span className="block truncate text-xs font-bold text-white/85">
                    {trainer?.device.name ??
                        (connected ? equipmentLabel(connection) : "Equipment")}
                </span>
                <span className="mt-0.5 block truncate text-[0.62rem] font-semibold text-white/35">
                    {connected ? "Ready to ride" : "Tap to connect"}
                </span>
            </span>
            <ChevronDown
                aria-hidden="true"
                className="size-4 shrink-0 text-white/30 transition-transform group-hover:translate-y-0.5"
            />
        </button>
    );
}
