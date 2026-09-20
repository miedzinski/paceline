import { ChevronDown, Radio } from "lucide-react";
import {
    equipmentPillLabel,
    isResistanceSelected,
    selectedSourceCount,
} from "@/lib/ride-equipment";
import { cn } from "@/lib/utils";
import type { DeviceConnectionsResponse, RideEquipmentResponse } from "@/types";

export function EquipmentButton({
    connection,
    equipment,
    onClick,
}: {
    connection: DeviceConnectionsResponse;
    equipment: RideEquipmentResponse | null;
    onClick: () => void;
}) {
    const connected = connection.connections.some(
        (item) => item.state === "CONNECTED",
    );
    const resistanceSelected = isResistanceSelected(equipment);
    const label = equipmentPillLabel(connection, equipment);
    const selectedCount = selectedSourceCount(equipment);

    return (
        <button
            type="button"
            onClick={onClick}
            aria-label={connected ? `Equipment connected: ${label}` : label}
            className="group inline-flex min-h-12 max-w-[17rem] min-w-0 items-center gap-3 rounded-2xl border border-white/[0.1] bg-white/[0.055] px-3 text-left transition-colors hover:border-white/[0.18] hover:bg-white/[0.09] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
        >
            <span className="relative grid size-8 shrink-0 place-items-center rounded-xl bg-white/[0.08] text-white/65">
                <Radio aria-hidden="true" className="size-4" />
                <span
                    className={cn(
                        "absolute -right-0.5 -bottom-0.5 size-2.5 rounded-full border-2 border-[#11141b]",
                        resistanceSelected ? "bg-[#73d6a1]" : "bg-[#ff8068]",
                    )}
                />
            </span>
            <span className="min-w-0 flex-1">
                <span className="block truncate text-xs font-bold text-white/85">
                    {label}
                </span>
                {connected && equipment !== null ? (
                    <span className="mt-0.5 block truncate text-[0.62rem] font-semibold text-white/35">
                        {selectedCount} connected
                    </span>
                ) : null}
            </span>
            <ChevronDown
                aria-hidden="true"
                className="size-4 shrink-0 text-white/30 transition-transform group-hover:translate-y-0.5"
            />
        </button>
    );
}
