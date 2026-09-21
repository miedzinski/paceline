import assert from "node:assert/strict";
import test from "node:test";
import { formatDurationSeconds } from "../src/lib/connection.ts";

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
