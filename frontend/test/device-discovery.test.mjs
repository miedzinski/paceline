import assert from "node:assert/strict";
import test from "node:test";
import {
    deviceDiscoveryFreshnessMs,
    formatDiscoveryAge,
    formatDiscoveryStatus,
    isFreshDiscovery,
    shouldRefreshDiscovery,
    shouldScanOnOpen,
} from "../src/lib/device-discovery.ts";

const now = Date.parse("2026-09-20T10:00:00.000Z");

function discovery(overrides = {}) {
    return {
        state: "DISCOVERED",
        changedAt: "2026-09-20T09:59:00.000Z",
        lastScanAt: "2026-09-20T09:59:00.000Z",
        devices: [],
        failure: null,
        ...overrides,
    };
}

test("treats a recent completed scan as fresh", () => {
    // given a scan completed within the freshness window:
    const result = discovery({
        lastScanAt: new Date(
            now - deviceDiscoveryFreshnessMs + 1,
        ).toISOString(),
    });

    // when freshness is evaluated:
    // then the result remains displayable:
    assert.equal(isFreshDiscovery(result, now), true);
    assert.equal(shouldRefreshDiscovery(result, now), false);
});

test("hides a stale result and requests a refresh", () => {
    // given a completed scan older than the freshness window:
    const result = discovery({
        lastScanAt: new Date(now - deviceDiscoveryFreshnessMs).toISOString(),
    });

    // when the page synchronizes discovery:
    // then it starts another scan instead of presenting the old devices:
    assert.equal(isFreshDiscovery(result, now), false);
    assert.equal(shouldRefreshDiscovery(result, now), true);
});

test("keeps a fresh device list visible while a manual refresh runs", () => {
    // given a scan in progress whose previous result is still fresh:
    const result = discovery({
        state: "DISCOVERING",
        lastScanAt: new Date(now - 30_000).toISOString(),
    });

    // when the page evaluates what it can display:
    // then the current list remains usable during the refresh:
    assert.equal(isFreshDiscovery(result, now), true);
    assert.equal(shouldRefreshDiscovery(result, now), false);
});

test("shows streamed candidates during an initial scan", () => {
    // given the first scan has found a candidate but has not completed:
    const result = discovery({
        state: "DISCOVERING",
        lastScanAt: null,
        devices: [{ id: "device-1" }],
    });

    // when the current scan is evaluated:
    // then the streamed candidate is displayable:
    assert.equal(isFreshDiscovery(result, now), true);
    assert.equal(formatDiscoveryStatus(result, true, now), "Scanning");
});

test("does not automatically retry a failed scan", () => {
    // given a failed scan with no retained result:
    const result = discovery({
        state: "FAILED",
        lastScanAt: null,
        failure: { code: "DISCOVERY_ERROR", message: "Scan failed" },
    });

    // when the page is polling the shared status:
    // then the user can retry explicitly without an automatic loop:
    assert.equal(shouldRefreshDiscovery(result, now), false);
    assert.equal(formatDiscoveryAge(result, now), null);
});

test("starts discovery on an initially ready page", () => {
    // given a backend that has not completed a scan yet:
    const result = discovery({
        state: "READY",
        lastScanAt: null,
    });

    // when the app opens:
    // then it requests the first background scan:
    assert.equal(shouldScanOnOpen(result, now), true);
});

test("uses the scanning status while a refresh runs", () => {
    // given a fresh result while a scan is in progress:
    const result = discovery({
        state: "DISCOVERING",
        lastScanAt: new Date(now - 30_000).toISOString(),
    });

    // when the discovery status is formatted:
    // then the stable scan button can be accompanied by the scanning label:
    assert.equal(formatDiscoveryStatus(result, true, now), "Scanning");
    assert.equal(formatDiscoveryStatus(result, false, now), "Scanned 30s ago");
});

test("reports scanning when no fresh result is available", () => {
    // given a scan in progress without a fresh completed result:
    const result = discovery({
        state: "DISCOVERING",
        lastScanAt: new Date(now - deviceDiscoveryFreshnessMs).toISOString(),
    });

    // when the discovery status is formatted:
    // then it reports the active initial or stale-result scan:
    assert.equal(formatDiscoveryStatus(result, true, now), "Scanning");
});
