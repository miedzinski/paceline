import assert from "node:assert/strict";
import test from "node:test";
import {
    powerZoneColors,
    powerZoneForStep,
    stepTarget,
    workoutStepColor,
} from "../src/lib/workouts.ts";

const profile = {
    athleteId: "athlete-1",
    name: "Rider",
    ftpWatts: 250,
    indoorFtpWatts: null,
    powerZones: [
        {
            number: 1,
            name: "Recovery",
            minPercent: 0,
            maxPercent: 55,
            minWatts: 0,
            maxWatts: 137,
        },
        {
            number: 2,
            name: "Endurance",
            minPercent: 55,
            maxPercent: 75,
            minWatts: 138,
            maxWatts: 187,
        },
        {
            number: 3,
            name: "Tempo",
            minPercent: 75,
            maxPercent: 90,
            minWatts: 188,
            maxWatts: 225,
        },
    ],
};

function workoutStep(overrides = {}) {
    return {
        text: null,
        durationSeconds: 60,
        distanceMeters: null,
        repeats: null,
        warmup: null,
        cooldown: null,
        intensity: null,
        ramp: null,
        freeRide: null,
        power: null,
        resolvedPower: null,
        heartRate: null,
        pace: null,
        cadence: null,
        steps: [],
        ...overrides,
    };
}

test("prefers the raw FTP percentage at a rounded zone boundary", () => {
    // given a 75% FTP target rounded up to the first watt of Z3:
    const step = workoutStep({
        power: {
            value: 75,
            start: null,
            end: null,
            units: "%ftp",
            target: "POWER",
        },
        resolvedPower: {
            value: 188,
            start: 188,
            end: 188,
            units: "W",
            target: null,
        },
    });

    // when the ride classifies and labels the step:
    const zone = powerZoneForStep(step, profile);

    // then the source percentage controls the boundary classification while the label retains both values:
    assert.equal(zone?.number, 2);
    assert.equal(workoutStepColor(step, 0, profile), powerZoneColors[1]);
    assert.equal(stepTarget(step, profile), "75% FTP · 188 W");
});

test("shows zone percentage and watt ranges together", () => {
    // given a source power-zone target with its resolved watt range:
    const step = workoutStep({
        power: {
            value: 2,
            start: null,
            end: null,
            units: "power_zone",
            target: null,
        },
        resolvedPower: {
            value: null,
            start: 138,
            end: 187,
            units: "W",
            target: null,
        },
    });

    // when the step label is formatted with the athlete profile:
    // then the declared zone, percentage boundaries, and absolute range are all visible:
    assert.equal(stepTarget(step, profile), "Z2 · 55–75% FTP · 138–187 W");
});

test("uses the declared zone range instead of a rounded watt midpoint", () => {
    // given a Z1-Z2 target whose resolved watt range has a midpoint in Z3:
    const step = workoutStep({
        power: {
            value: null,
            start: 1,
            end: 2,
            units: "power_zone",
            target: null,
        },
        resolvedPower: {
            value: null,
            start: 0,
            end: 187,
            units: "W",
            target: null,
        },
    });

    // when the ride classifies the source zone range:
    const zone = powerZoneForStep(step, profile);

    // then the zone semantics remain authoritative for the bar color:
    assert.equal(zone?.number, 1);
    assert.equal(workoutStepColor(step, 0, profile), powerZoneColors[0]);
});

test("does not infer a percentage label from an absolute watt target", () => {
    // given a step defined strictly in watts:
    const step = workoutStep({
        power: {
            value: 188,
            start: null,
            end: null,
            units: "W",
            target: "POWER",
        },
        resolvedPower: {
            value: 188,
            start: 188,
            end: 188,
            units: "W",
            target: null,
        },
    });

    // when the step label is formatted with an FTP-bearing profile:
    // then the source unit remains authoritative and no inferred percentage is shown:
    assert.equal(stepTarget(step, profile), "188 W");
});
