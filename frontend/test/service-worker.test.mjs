import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";
import { runInNewContext } from "node:vm";

const serviceWorkerSource = readFileSync(
    new URL("../public/sw.js", import.meta.url),
    "utf8",
);

function fetchHandler() {
    let handler;

    runInNewContext(serviceWorkerSource, {
        URL,
        caches: {
            match: async () => null,
            open: async () => ({ put: async () => {} }),
        },
        fetch: async () => ({ clone: () => ({}) }),
        self: {
            location: { origin: "https://paceline.test" },
            addEventListener(type, listener) {
                if (type === "fetch") {
                    handler = listener;
                }
            },
        },
    });

    return handler;
}

function serviceWorkerIntercepts(pathname, accept = "application/json") {
    let responded = false;
    const request = {
        method: "GET",
        url: `https://paceline.test${pathname}`,
        headers: {
            get: (name) => (name.toLowerCase() === "accept" ? accept : null),
        },
    };

    fetchHandler()({
        request,
        respondWith() {
            responded = true;
        },
    });

    return responded;
}

test("does not intercept API responses for caching", () => {
    // given the production API namespaces and the development proxy namespace:
    const apiPaths = [
        "/api/profile",
        "/devices/discovery",
        "/workouts/today",
        "/profile",
        "/training-sessions/current",
    ];

    // when the service worker evaluates each request:
    // then no API response is handled by its cache strategy:
    for (const path of apiPaths) {
        assert.equal(serviceWorkerIntercepts(path), false, path);
    }
});

test("does not intercept server-sent event streams for caching", () => {
    // given an event stream outside the known API path list:
    // when the service worker evaluates the stream request:
    // then the live response remains on the network:
    assert.equal(
        serviceWorkerIntercepts("/events", "text/event-stream"),
        false,
    );
});
