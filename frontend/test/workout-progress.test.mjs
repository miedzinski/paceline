import assert from "node:assert/strict";
import test from "node:test";
import {
    workoutProgressPercent,
    workoutRemainingSeconds,
    workoutStepProgressPercent,
} from "../src/lib/workout-progress.ts";

function timedWorkout(overrides = {}) {
    return {
        completed: false,
        completion: { kind: "TIME", value: 120 },
        stepStartedAt: "2026-09-17T10:00:00.000Z",
        ...overrides,
    };
}

test("reports elapsed percentage for a timed step", () => {
    const workout = timedWorkout();
    const startedAt = Date.parse(workout.stepStartedAt);

    assert.equal(workoutStepProgressPercent(workout, startedAt + 60_000), 50);
});

test("clamps timed-step progress to its bounds", () => {
    const workout = timedWorkout();
    const startedAt = Date.parse(workout.stepStartedAt);

    assert.equal(workoutStepProgressPercent(workout, startedAt - 1), 0);
    assert.equal(workoutStepProgressPercent(workout, startedAt + 180_000), 100);
});

test("does not show a progress bar for non-timed or completed steps", () => {
    assert.equal(
        workoutStepProgressPercent(
            timedWorkout({ completion: { kind: "MANUAL", value: null } }),
            Date.now(),
        ),
        null,
    );
    assert.equal(
        workoutStepProgressPercent(
            timedWorkout({ completed: true }),
            Date.now(),
        ),
        null,
    );
});

test("maps the current step progress onto the complete workout", () => {
    const workout = timedWorkout({ currentStep: 2 });
    const startedAt = Date.parse(workout.stepStartedAt);

    assert.equal(
        workoutProgressPercent(workout, [10, 20, 10], startedAt + 60_000),
        50,
    );
});

test("places non-timed steps at the start of their segment", () => {
    const workout = timedWorkout({
        currentStep: 2,
        completion: { kind: "MANUAL", value: null },
    });

    assert.equal(workoutProgressPercent(workout, [10, 20, 10], Date.now()), 25);
});

test("counts down the remaining time across the complete timed workout", () => {
    const workout = timedWorkout({ currentStep: 2 });
    const startedAt = Date.parse(workout.stepStartedAt);
    const steps = [
        { durationSeconds: 120, distanceMeters: null },
        { durationSeconds: 180, distanceMeters: null },
        { durationSeconds: 60, distanceMeters: null },
    ];

    assert.equal(
        workoutRemainingSeconds(workout, steps, startedAt + 60_000),
        180,
    );
});

test("does not estimate a total timer for distance-based steps", () => {
    const workout = timedWorkout({ currentStep: 1 });

    assert.equal(
        workoutRemainingSeconds(
            workout,
            [
                { durationSeconds: null, distanceMeters: 1000 },
                { durationSeconds: 120, distanceMeters: null },
            ],
            Date.now(),
        ),
        null,
    );
});
