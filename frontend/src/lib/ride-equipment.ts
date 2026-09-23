import type {
    DeviceConnection,
    DeviceConnectionsResponse,
    CyclingTelemetry,
    RideEquipmentResponse,
    RideRole,
    RideRoleState,
    RideReadinessReason,
    TelemetryProjection,
    TrainerConnectionStatus,
    TrainingSessionState,
    TrainingSessionTelemetry,
} from "@/types";

export const rideRoleOrder: RideRole[] = [
    "RESISTANCE_CONTROL",
    "POWER",
    "CADENCE",
    "HEART_RATE",
];

export function rideRoleLabel(role: RideRole): string {
    switch (role) {
        case "RESISTANCE_CONTROL":
            return "Resistance";
        case "POWER":
            return "Power";
        case "CADENCE":
            return "Cadence";
        case "HEART_RATE":
            return "Heart rate";
    }
}

export function roleStatusLabel(role: RideRoleState): string {
    switch (role.status) {
        case "OPTIONAL":
            return "Not selected";
        case "AMBIGUOUS":
            return role.role === "RESISTANCE_CONTROL"
                ? "Required"
                : "Not selected";
        case "UNAVAILABLE":
            if (role.role === "HEART_RATE" && role.sourceId === null) {
                return "Not selected";
            }
            if (role.role === "RESISTANCE_CONTROL" && role.sourceId === null) {
                return "Required";
            }
            return role.sourceId === null
                ? "Unavailable"
                : "Assigned source unavailable";
        case "SELECTED":
            return "Selected";
    }
}

export function visibleReadinessReasons(
    equipment: RideEquipmentResponse,
): RideReadinessReason[] {
    return equipment.readinessReasons.filter(
        (reason) => reason.role !== "RESISTANCE_CONTROL",
    );
}

export function roleState(
    equipment: RideEquipmentResponse,
    role: RideRole,
): RideRoleState | null {
    return equipment.roles.find((candidate) => candidate.role === role) ?? null;
}

export function isResistanceSelected(
    equipment: RideEquipmentResponse | null,
): boolean {
    return (
        equipment?.roles.some(
            (role) =>
                role.role === "RESISTANCE_CONTROL" &&
                role.status === "SELECTED",
        ) ?? false
    );
}

export function selectedSourceCount(
    equipment: RideEquipmentResponse | null,
): number {
    if (equipment === null) {
        return 0;
    }

    return new Set(
        Object.values(equipment.assignments).filter(
            (sourceId): sourceId is string => sourceId !== null,
        ),
    ).size;
}

export function roleSourceIds(role: RideRoleState): string[] {
    return Array.from(
        new Set([
            ...(role.sourceId !== null &&
            !role.compatibleSourceIds.includes(role.sourceId)
                ? [role.sourceId]
                : []),
            ...role.compatibleSourceIds,
        ]),
    );
}

export function sourceName(
    equipment: RideEquipmentResponse | null,
    sourceId: string | null,
): string | null {
    if (equipment === null || sourceId === null) {
        return null;
    }

    return (
        equipment.sources.find((source) => source.id === sourceId)?.name ?? null
    );
}

export function rolesAssignedToSource(
    equipment: RideEquipmentResponse | null,
    sourceId: string,
): RideRole[] {
    return (
        equipment?.roles
            .filter((role) => role.sourceId === sourceId)
            .map((role) => role.role) ?? []
    );
}

export function roleSourceName(
    equipment: RideEquipmentResponse,
    role: RideRole,
): string | null {
    const assignment = roleState(equipment, role);
    return sourceName(equipment, assignment?.sourceId ?? null);
}

export function sourceConnection(
    connection: DeviceConnectionsResponse,
    sourceId: string | null,
): DeviceConnection | null {
    if (sourceId === null) {
        return null;
    }

    return connection.connections.find((item) => item.id === sourceId) ?? null;
}

export function controlSourceConnection(
    connection: DeviceConnectionsResponse,
    equipment: RideEquipmentResponse | null,
): DeviceConnection | null {
    const controlSourceId = equipment?.assignments.controlSourceId ?? null;
    return sourceConnection(connection, controlSourceId);
}

export function equipmentPillLabel(
    connection: DeviceConnectionsResponse,
    equipment: RideEquipmentResponse | null,
): string {
    const controlSourceId = equipment?.assignments.controlSourceId ?? null;
    const controlDevice = controlSourceConnection(connection, equipment);
    if (controlDevice !== null) {
        return controlDevice.device.name;
    }

    if (controlSourceId !== null) {
        return sourceName(equipment, controlSourceId) ?? "Control unavailable";
    }

    return "Tap to connect";
}

export function liveSessionTelemetry(
    sessionState: TrainingSessionState,
    trainerConnection: TrainerConnectionStatus,
    telemetry: TrainingSessionTelemetry | null,
): TelemetryProjection<CyclingTelemetry> | null {
    const sessionIsLive =
        sessionState === "ACTIVE" || sessionState === "PAUSED";
    return sessionIsLive && trainerConnection === "CONNECTED"
        ? (telemetry?.cycling ?? null)
        : null;
}

export function readinessMessages(equipment: RideEquipmentResponse): string[] {
    return visibleReadinessReasons(equipment).map((reason) => reason.message);
}
