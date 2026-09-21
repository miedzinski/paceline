/* global caches, fetch, self, URL */

const cacheName = "paceline-shell-v2";
const nonCacheablePathPrefixes = [
    "/api",
    "/devices",
    "/workouts",
    "/profile",
    "/training-sessions",
];

function hasPathPrefix(pathname, prefix) {
    return pathname === prefix || pathname.startsWith(`${prefix}/`);
}

function isApiRequest(requestUrl) {
    return nonCacheablePathPrefixes.some((prefix) =>
        hasPathPrefix(requestUrl.pathname, prefix),
    );
}

function isEventStreamRequest(request, requestUrl) {
    return (
        requestUrl.pathname.endsWith("/events") ||
        (request.headers.get("accept") || "")
            .toLowerCase()
            .includes("text/event-stream")
    );
}

self.addEventListener("install", (event) => {
    event.waitUntil(
        caches
            .open(cacheName)
            .then((cache) =>
                cache.addAll([
                    "/",
                    "/index.html",
                    "/manifest.webmanifest",
                    "/favicon.svg",
                    "/apple-touch-icon.png",
                    "/pwa-icon-192.png",
                    "/pwa-icon-512.png",
                ]),
            ),
    );
});

self.addEventListener("activate", (event) => {
    event.waitUntil(
        caches
            .keys()
            .then((keys) =>
                Promise.all(
                    keys
                        .filter((key) => key !== cacheName)
                        .map((key) => caches.delete(key)),
                ),
            ),
    );
});

self.addEventListener("fetch", (event) => {
    const requestUrl = new URL(event.request.url);

    if (
        event.request.method !== "GET" ||
        requestUrl.origin !== self.location.origin ||
        isApiRequest(requestUrl) ||
        isEventStreamRequest(event.request, requestUrl)
    ) {
        return;
    }

    event.respondWith(
        caches.match(event.request).then((cachedResponse) => {
            if (cachedResponse) {
                return cachedResponse;
            }

            return fetch(event.request).then((response) => {
                const responseToCache = response.clone();
                void caches
                    .open(cacheName)
                    .then((cache) => cache.put(event.request, responseToCache));
                return response;
            });
        }),
    );
});
