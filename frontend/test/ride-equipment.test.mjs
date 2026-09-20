import assert from "node:assert/strict";
import test from "node:test";
import {
    controlSourceConnection,
    equipmentPillLabel,
    isResistanceSelected,
    liveSessionTelemetry,
    readinessMessages,
    rolesAssignedToSource,
    roleSourceIds,
    roleSourceName,
    roleStatusLabel,
    selectedSourceCount,
    sourceName,
    visibleReadinessReasons,
} from "../src/lib/ride-equipment.ts";

function role(roleName, sourceId, status, compatibleSourceIds) {
    return {
        role: roleName,
        sourceId,
        status,
        compatibleSourceIds,
    };
}

function source(
    id,
    name,
    capabilities = ["POWER", "CADENCE", "RESISTANCE_CONTROL"],
) {
    return {
        id,
        state: "CONNECTED",
        name,
        capabilities,
    };
}

function equipment(overrides = {}) {
    return {
        readiness: "READY",
        ready: true,
        readinessReasons: [],
        assignments: {
            controlSourceId: "trainer-1",
            powerSourceId: "trainer-1",
            cadenceSourceId: "trainer-1",
            heartRateSourceId: null,
        },
        roles: [
            role("RESISTANCE_CONTROL", "trainer-1", "SELECTED", ["trainer-1"]),
            role("POWER", "trainer-1", "SELECTED", ["trainer-1"]),
            role("CADENCE", "trainer-1", "SELECTED", ["trainer-1"]),
            role("HEART_RATE", null, "OPTIONAL", []),
        ],
        sources: [source("trainer-1", "KICKR CORE")],
        ...overrides,
    };
}

function connection(overrides = {}) {
    return {
        connections: [
            {
                id: "trainer-1",
                state: "CONNECTED",
                changedAt: "2026-09-19T10:00:00.000Z",
                device: {
                    name: "KICKR CORE",
                    transport: "BLUETOOTH",
                    host: null,
                    port: null,
                    address: "AA:AA:AA:AA:AA:01",
                },
                failure: null,
                capabilities: ["CYCLING_TELEMETRY", "ERG_POWER_CONTROL"],
                telemetry: {
                    availability: "CURRENT",
                    sample: {
                        powerWatts: 180,
                        cadenceRpm: 90,
                        speedKph: 30,
                        distanceMeters: 1000,
                        receivedAt: "2026-09-19T10:00:01.000Z",
                    },
                    lastReceivedAt: "2026-09-19T10:00:01.000Z",
                },
                heartRate: {
                    availability: "UNAVAILABLE",
                    sample: null,
                    lastReceivedAt: null,
                },
            },
        ],
        ...overrides,
    };
}

test("a single compatible trainer is ready without an extra source choice", () => {
    // given one connected trainer that supplies every compatible role:
    const readyEquipment = equipment();

    // when the role labels are projected:
    // then the ride is ready and the selected source is shown once:
    assert.equal(readyEquipment.ready, true);
    assert.equal(roleStatusLabel(readyEquipment.roles[0]), "Selected");
    assert.equal(isResistanceSelected(readyEquipment), true);
});

test("resistance is not selected when its role is unresolved", () => {
    // given equipment without a selected resistance source:
    const unresolvedEquipment = equipment({
        ready: false,
        roles: [role("RESISTANCE_CONTROL", null, "AMBIGUOUS", ["trainer-1"])],
    });

    // when the pill status is resolved:
    // then resistance is treated as not selected:
    assert.equal(isResistanceSelected(unresolvedEquipment), false);
});

test("a control-only trainer is ready without power or cadence sources", () => {
    // given an FTMS source that exposes only resistance control:
    const controlOnly = equipment({
        assignments: {
            controlSourceId: "ftms-control",
            powerSourceId: null,
            cadenceSourceId: null,
            heartRateSourceId: null,
        },
        roles: [
            role("RESISTANCE_CONTROL", "ftms-control", "SELECTED", [
                "ftms-control",
            ]),
            role("POWER", null, "OPTIONAL", []),
            role("CADENCE", null, "OPTIONAL", []),
            role("HEART_RATE", null, "OPTIONAL", []),
        ],
        sources: [
            source("ftms-control", "FTMS control", ["RESISTANCE_CONTROL"]),
        ],
    });

    // when the role labels are projected:
    // then control is sufficient and the missing telemetry is unassigned:
    assert.equal(controlOnly.ready, true);
    assert.equal(roleStatusLabel(controlOnly.roles[1]), "Not selected");
    assert.equal(roleStatusLabel(controlOnly.roles[2]), "Not selected");
});

test("marks an unresolved control source as required", () => {
    // given a control role without a connected compatible source:
    const controlRole = role("RESISTANCE_CONTROL", null, "UNAVAILABLE", []);
    const ambiguousControlRole = role("RESISTANCE_CONTROL", null, "AMBIGUOUS", [
        "trainer-1",
        "trainer-2",
    ]);

    // when the control tile status is projected:
    // then both unresolved control states use the concise required label:
    assert.equal(roleStatusLabel(controlRole), "Required");
    assert.equal(roleStatusLabel(ambiguousControlRole), "Required");
});

test("does not expose a raw source ID when source metadata is gone", () => {
    // given a role whose selected source is no longer in the equipment catalog:
    const unavailableEquipment = equipment({ sources: [] });
    const unavailableRole = role("POWER", "trainer-1", "UNAVAILABLE", []);

    // when the UI resolves the source label:
    // then it uses the role status instead of displaying the opaque source ID:
    assert.equal(sourceName(unavailableEquipment, "trainer-1"), null);
    assert.equal(
        roleStatusLabel(unavailableRole),
        "Assigned source unavailable",
    );
});

test("finds every ride role assigned to an explicitly disconnected source", () => {
    // given one connection selected for control, power, and cadence:
    const assignedEquipment = equipment();

    // when the disconnect flow identifies the roles owned by that source:
    // then every matching role is cleared together:
    assert.deepEqual(rolesAssignedToSource(assignedEquipment, "trainer-1"), [
        "RESISTANCE_CONTROL",
        "POWER",
        "CADENCE",
    ]);
    assert.deepEqual(rolesAssignedToSource(assignedEquipment, "missing"), []);
});

test("hides control readiness reasons because the control tile carries the requirement", () => {
    // given equipment with a control warning and a separate telemetry warning:
    const controlReason = {
        code: "REQUIRED_ROLE_UNAVAILABLE",
        role: "RESISTANCE_CONTROL",
        sourceId: null,
        compatibleSourceIds: [],
        message:
            "No connected source can provide the RESISTANCE_CONTROL ride role",
    };
    const telemetryReason = {
        code: "ROLE_SELECTION_REQUIRED",
        role: "POWER",
        sourceId: null,
        compatibleSourceIds: ["trainer-1", "trainer-2"],
        message: "Select a source for the POWER ride role",
    };
    const equipmentWithReasons = equipment({
        readinessReasons: [controlReason, telemetryReason],
    });

    // when visible readiness reasons are projected:
    // then only non-control warnings remain:
    assert.deepEqual(visibleReadinessReasons(equipmentWithReasons), [
        telemetryReason,
    ]);
    assert.deepEqual(readinessMessages(equipmentWithReasons), [
        telemetryReason.message,
    ]);
});

test("multiple telemetry sources do not block readiness", () => {
    // given two compatible power sources and no power assignment:
    const ambiguousPower = role("POWER", null, "AMBIGUOUS", [
        "trainer-1",
        "trainer-2",
    ]);
    const ambiguousEquipment = equipment({
        readiness: "READY",
        ready: true,
        readinessReasons: [],
        roles: [
            role("RESISTANCE_CONTROL", "trainer-1", "SELECTED", ["trainer-1"]),
            ambiguousPower,
            role("CADENCE", "trainer-1", "SELECTED", ["trainer-1"]),
            role("HEART_RATE", null, "OPTIONAL", []),
        ],
        sources: [
            source("trainer-1", "Trainer A"),
            source("trainer-2", "Trainer B"),
        ],
    });

    // when the role status and server readiness are shown:
    // then the UI leaves the unresolved telemetry role unassigned without blocking the ride:
    assert.equal(ambiguousEquipment.ready, true);
    assert.equal(roleStatusLabel(ambiguousPower), "Not selected");
    assert.deepEqual(readinessMessages(ambiguousEquipment), []);
});

test("the selected control source drives the equipment header", () => {
    // given two connected ERG-capable trainers and trainer-2 selected for control:
    const selectedEquipment = equipment({
        assignments: {
            ...equipment().assignments,
            controlSourceId: "trainer-2",
        },
        sources: [
            source("trainer-1", "Trainer A"),
            source("trainer-2", "Trainer B"),
        ],
    });
    const currentConnections = connection({
        connections: [
            connection().connections[0],
            {
                ...connection().connections[0],
                id: "trainer-2",
                device: {
                    ...connection().connections[0].device,
                    id: "trainer-2",
                    name: "Trainer B",
                    address: "AA:AA:AA:AA:AA:02",
                },
            },
        ],
    });

    // when the header resolves its control source:
    // then it uses trainer-2 instead of the first connected trainer:
    assert.equal(
        controlSourceConnection(currentConnections, selectedEquipment)?.id,
        "trainer-2",
    );
    assert.equal(
        equipmentPillLabel(currentConnections, selectedEquipment),
        "Trainer B",
    );
});

test("counts each selected equipment source once", () => {
    // given one trainer selected for control, power, and cadence:
    const singleSourceEquipment = equipment();

    // when the selected source count is calculated:
    // then the shared source is counted once rather than once per role:
    assert.equal(selectedSourceCount(singleSourceEquipment), 1);

    // given a separate heart-rate source is also selected:
    const dualSourceEquipment = equipment({
        assignments: {
            ...singleSourceEquipment.assignments,
            heartRateSourceId: "heart-rate-1",
        },
    });

    // when the selected source count is calculated:
    // then both distinct sources are counted:
    assert.equal(selectedSourceCount(dualSourceEquipment), 2);
});

test("an explicitly cleared control source is not shown on the equipment pill", () => {
    // given a connected trainer whose control role has been explicitly cleared:
    const clearedEquipment = equipment({
        assignments: {
            ...equipment().assignments,
            controlSourceId: null,
        },
        roles: [
            role("RESISTANCE_CONTROL", null, "AMBIGUOUS", ["trainer-1"]),
            role("POWER", "trainer-1", "SELECTED", ["trainer-1"]),
            role("CADENCE", "trainer-1", "SELECTED", ["trainer-1"]),
            role("HEART_RATE", null, "OPTIONAL", []),
        ],
    });

    // when the equipment pill resolves its label:
    // then the pill prompts the rider to connect or select equipment:
    assert.equal(controlSourceConnection(connection(), clearedEquipment), null);
    assert.equal(
        equipmentPillLabel(connection(), clearedEquipment),
        "Tap to connect",
    );
    assert.equal(
        equipmentPillLabel(connection({ connections: [] }), clearedEquipment),
        "Tap to connect",
    );
});

test("heart rate stays unassigned when no source is selected", () => {
    // given a ride with no connected heart-rate source:
    const unassignedRole = role("HEART_RATE", null, "OPTIONAL", []);
    const unassignedEquipment = equipment({
        roles: [
            role("RESISTANCE_CONTROL", "trainer-1", "SELECTED", ["trainer-1"]),
            role("POWER", "trainer-1", "SELECTED", ["trainer-1"]),
            role("CADENCE", "trainer-1", "SELECTED", ["trainer-1"]),
            unassignedRole,
        ],
    });

    // when the role is rendered:
    // then it remains unassigned without being treated as unavailable:
    assert.equal(roleStatusLabel(unassignedRole), "Not selected");
    assert.equal(roleSourceName(unassignedEquipment, "HEART_RATE"), null);
});

test("keeps a disconnected assignment visible without adding it as a new option", () => {
    // given a selected source that is no longer among the compatible sources:
    const disconnectedRole = role("POWER", "trainer-1", "UNAVAILABLE", [
        "trainer-2",
    ]);

    // when the equipment chooser builds the role options:
    // then the unavailable assignment remains inspectable beside current sources:
    assert.deepEqual(roleSourceIds(disconnectedRole), [
        "trainer-1",
        "trainer-2",
    ]);
});

test("uses the backend-filtered session telemetry for live metrics", () => {
    // given the backend has retained fresh control data but removed stale independent fields:
    const filteredTelemetry = {
        cycling: {
            availability: "CURRENT",
            sample: {
                powerWatts: null,
                cadenceRpm: 90,
                speedKph: 30,
                distanceMeters: 1000,
                receivedAt: "2026-09-19T10:00:04.000Z",
            },
            lastReceivedAt: "2026-09-19T10:00:04.000Z",
        },
        heartRate: {
            availability: "INTERRUPTED",
            sample: null,
            lastReceivedAt: "2026-09-19T10:00:01.000Z",
        },
    };

    // when the active ride selects its live telemetry:
    // then it uses the sparse backend snapshot and does not reconstruct raw source values:
    assert.deepEqual(
        liveSessionTelemetry("CONNECTED", filteredTelemetry),
        filteredTelemetry.cycling,
    );
    assert.equal(liveSessionTelemetry("INTERRUPTED", filteredTelemetry), null);
});
