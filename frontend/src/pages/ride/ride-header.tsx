import { EquipmentButton } from "@/components/equipment-button";
import { PacelineLogo } from "@/components/paceline-logo";
import type { DeviceConnectionsResponse, RideEquipmentResponse } from "@/types";

export function RideHeader({
    connection,
    equipment,
    onLogoClick,
    onOpenEquipment,
}: {
    connection: DeviceConnectionsResponse;
    equipment: RideEquipmentResponse | null;
    onLogoClick?: () => void;
    onOpenEquipment: () => void;
}) {
    return (
        <div className="flex items-center justify-between gap-4">
            <PacelineLogo onClick={onLogoClick} />
            <EquipmentButton
                connection={connection}
                equipment={equipment}
                onClick={onOpenEquipment}
            />
        </div>
    );
}
