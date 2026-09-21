import assert from "node:assert/strict";
import test from "node:test";
import { resolveApiBaseUrl } from "../src/lib/api-base.ts";

test("uses same-origin API paths in production", () => {
    // given a production build with a development proxy value present:
    // when the API base URL is resolved:
    const baseUrl = resolveApiBaseUrl(true, "/api");

    // then production requests use the backend's same-origin routes:
    assert.equal(baseUrl, "");
});

test("keeps the Vite proxy base in development", () => {
    // given a development build configured for the Vite API proxy:
    // when the API base URL is resolved:
    const baseUrl = resolveApiBaseUrl(false, "/api/");

    // then development requests retain the proxy prefix:
    assert.equal(baseUrl, "/api");
});

test("defaults development requests to the Vite proxy", () => {
    // given a development build without an explicit API base:
    // when the API base URL is resolved:
    const baseUrl = resolveApiBaseUrl(false, undefined);

    // then the development proxy remains the fallback:
    assert.equal(baseUrl, "/api");
});
