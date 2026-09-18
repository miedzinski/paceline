import assert from "node:assert/strict";
import test from "node:test";
import { canAutoStartSelectedWorkout } from "../src/lib/ride-entry.ts";

const workoutSelection = {
    provider: "intervals.icu",
    sourceType: "SCHEDULED",
    sourceId: "workout-123",
};

function readiness(overrides = {}) {
    return {
        sessionLoaded: true,
        sessionState: "NOT_STARTED",
        workoutSelection,
        hasErgControl: true,
        heartRateSourceCount: 1,
        selectedHeartRateSourceId: "hr-1",
        ...overrides,
    };
}

test("auto-starts a selected workout when the trainer is ready", () => {
    // given a loaded not-started session with trainer control available:
    // when the selected workout is checked for direct start:
    const canStart = canAutoStartSelectedWorkout(readiness());

    // then the setup screen can be skipped:
    assert.equal(canStart, true);
});

test("keeps setup visible until a trainer with ERG control is connected", () => {
    // given a selected workout without a controllable trainer:
    // when the selected workout is checked for direct start:
    const canStart = canAutoStartSelectedWorkout(
        readiness({ hasErgControl: false }),
    );

    // then the connection setup remains necessary:
    assert.equal(canStart, false);
});

test("keeps setup visible when multiple heart-rate sources need a choice", () => {
    // given multiple connected heart-rate sources and no selected source:
    // when the selected workout is checked for direct start:
    const canStart = canAutoStartSelectedWorkout(
        readiness({ heartRateSourceCount: 2, selectedHeartRateSourceId: null }),
    );

    // then the source-selection step remains necessary:
    assert.equal(canStart, false);
});

test("does not auto-start before the current session has loaded", () => {
    // given an apparently ready trainer before the current session response arrives:
    // when the selected workout is checked for direct start:
    const canStart = canAutoStartSelectedWorkout(
        readiness({ sessionLoaded: false }),
    );

    // then the app waits for the session state before starting:
    assert.equal(canStart, false);
});
