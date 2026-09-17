import { EquipmentButton } from "@/components/equipment-button";
import { PacelineLogo } from "@/components/paceline-logo";
import type { DeviceConnectionResponse } from "@/types";

export function RideHeader({
    connection,
    onLogoClick,
    onOpenEquipment,
}: {
    connection: DeviceConnectionResponse;
    onLogoClick: () => void;
    onOpenEquipment: () => void;
}) {
    return (
        <div className="flex items-center justify-between gap-4">
            <PacelineLogo onClick={onLogoClick} />
            <EquipmentButton
                connection={connection}
                onClick={onOpenEquipment}
            />
        </div>
    );
}
