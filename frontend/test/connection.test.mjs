import assert from "node:assert/strict";
import test from "node:test";
import {
    formatDurationSeconds,
    shouldShowConnectionBanner,
} from "../src/lib/connection.ts";

test("formats the backend activity duration for the stopped summary", () => {
    // given a backend duration that spans more than one hour:
    // when the duration is formatted for the post-ride sheet:
    const formatted = formatDurationSeconds(3_661.9);

    // then fractional seconds are omitted while hours remain explicit:
    assert.equal(formatted, "1:01:01");
});

test("uses the empty duration label when the summary has no duration", () => {
    // given a missing backend summary duration:
    // when the duration is formatted for the post-ride sheet:
    const formatted = formatDurationSeconds(null);

    // then the UI remains safe to render:
    assert.equal(formatted, "00:00");
});

test("hides the stale-reading banner while a connected session is paused", () => {
    // given a paused session whose trainer is connected but has no fresh reading:
    // when the banner visibility is resolved:
    const visible = shouldShowConnectionBanner({
        hasTrainer: true,
        telemetryAvailable: false,
        status: "CONNECTED",
        paused: true,
        postRideOpen: false,
    });

    // then the generic waiting message is hidden:
    assert.equal(visible, false);
});

test("keeps a real trainer interruption visible during pause", () => {
    // given a paused session whose trainer connection is interrupted:
    // when the banner visibility is resolved:
    const visible = shouldShowConnectionBanner({
        hasTrainer: true,
        telemetryAvailable: false,
        status: "INTERRUPTED",
        paused: true,
        postRideOpen: false,
    });

    // then the actual connection issue remains visible:
    assert.equal(visible, true);
});

test("hides the connection banner under the post-ride upload sheet", () => {
    // given a stopped activity with its post-ride upload sheet open:
    // when the banner visibility is resolved:
    const visible = shouldShowConnectionBanner({
        hasTrainer: true,
        telemetryAvailable: false,
        status: "CONNECTED",
        paused: false,
        postRideOpen: true,
    });

    // then the blurred page does not show the stale-reading banner underneath:
    assert.equal(visible, false);
});
