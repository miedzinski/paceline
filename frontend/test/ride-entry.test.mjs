import assert from "node:assert/strict";
import test from "node:test";
import { workoutEntryMode } from "../src/lib/ride-entry.ts";

const workoutSelection = {
    provider: "intervals.icu",
    sourceType: "SCHEDULED",
    sourceId: "workout-123",
};

function readiness(overrides = {}) {
    return {
        sessionLoaded: true,
        equipmentLoaded: true,
        sessionState: "NOT_STARTED",
        workoutSelection,
        hasErgControl: true,
        setupRequired: false,
        ...overrides,
    };
}

test("starts a workout directly when control is already available", () => {
    // given the initial session and equipment snapshots with trainer control:
    // when the workout entry mode is resolved:
    const mode = workoutEntryMode(readiness());

    // then the workout starts without showing session setup:
    assert.equal(mode, "AUTO_STARTING");
});

test("opens setup when the workout starts without trainer control", () => {
    // given the initial equipment snapshot has no controllable source:
    // when the workout entry mode is resolved:
    const mode = workoutEntryMode(readiness({ hasErgControl: false }));

    // then equipment setup is required:
    assert.equal(mode, "SETUP");
});

test("keeps setup open after control becomes available", () => {
    // given setup was entered because no control source was connected:
    // when a trainer becomes available while setup is open:
    const mode = workoutEntryMode(
        readiness({ setupRequired: true, hasErgControl: true }),
    );

    // then connecting the trainer does not start the workout:
    assert.equal(mode, "SETUP");
});

test("waits for the initial snapshots before choosing a workout entry", () => {
    // given a selected workout while the initial session response is pending:
    // when the workout entry mode is resolved:
    const mode = workoutEntryMode(readiness({ sessionLoaded: false }));

    // then neither setup nor automatic start is chosen prematurely:
    assert.equal(mode, "CHECKING");
});
