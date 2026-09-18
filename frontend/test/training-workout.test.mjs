import assert from "node:assert/strict";
import test from "node:test";
import { workoutDefinitionFromSession } from "../src/lib/training-workout.ts";

test("rehydrates the complete executable workout structure from a session", () => {
    const definition = workoutDefinitionFromSession({
        provider: "intervals.icu",
        sourceId: "event-1",
        name: "Threshold build",
        currentStep: 2,
        totalSteps: 3,
        steps: [
            {
                text: "Warm up",
                intensity: "warmup",
                completion: { kind: "TIME", value: 300 },
                target: {
                    kind: "POWER",
                    lowWatts: 120,
                    highWatts: 120,
                    startWatts: null,
                    endWatts: null,
                },
            },
            {
                text: "Build",
                intensity: null,
                completion: { kind: "TIME", value: 600 },
                target: {
                    kind: "RAMP",
                    lowWatts: 150,
                    highWatts: 220,
                    startWatts: 150,
                    endWatts: 220,
                },
            },
            {
                text: "Free spin",
                intensity: "cooldown",
                completion: { kind: "MANUAL", value: null },
                target: {
                    kind: "OPEN",
                    lowWatts: null,
                    highWatts: null,
                    startWatts: null,
                    endWatts: null,
                },
            },
        ],
        stepText: "Build",
        stepStartedAt: "2026-09-18T10:00:00.000Z",
        completion: { kind: "TIME", value: 600 },
        target: {
            kind: "RAMP",
            lowWatts: 150,
            highWatts: 220,
            startWatts: 150,
            endWatts: 220,
        },
        completed: false,
    });

    assert.equal(definition?.steps.length, 3);
    assert.deepEqual(
        definition?.steps.map((step) => ({
            text: step.text,
            durationSeconds: step.durationSeconds,
            ramp: step.ramp,
            freeRide: step.freeRide,
            intensity: step.intensity,
            power: step.power,
        })),
        [
            {
                text: "Warm up",
                durationSeconds: 300,
                ramp: false,
                freeRide: false,
                intensity: "warmup",
                power: {
                    value: 120,
                    start: 120,
                    end: 120,
                    units: "watts",
                    target: null,
                },
            },
            {
                text: "Build",
                durationSeconds: 600,
                ramp: true,
                freeRide: false,
                intensity: null,
                power: {
                    value: null,
                    start: 150,
                    end: 220,
                    units: "watts",
                    target: null,
                },
            },
            {
                text: "Free spin",
                durationSeconds: null,
                ramp: false,
                freeRide: true,
                intensity: "cooldown",
                power: null,
            },
        ],
    );
});

test("returns no definition for a manual session", () => {
    assert.equal(workoutDefinitionFromSession(null), null);
});
