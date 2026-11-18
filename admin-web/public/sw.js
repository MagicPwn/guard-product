const CACHE = "guard-shell-v3";
const SHELL = ["/", "/index.html", "/manifest.webmanifest", "/icon.svg", "/icon-192.png", "/icon-512.png"];
self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});
self.addEventListener("activate", (event) => {
  event.waitUntil(caches.keys().then((keys) =>
    Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key)))));
  self.clients.claim();
});
self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET" || event.request.url.includes("/api/")) return;
  if (event.request.mode === "navigate") {
    event.respondWith(fetch(event.request).then((response) => {
      if (response.ok) {
        const copy = response.clone();
        void caches.open(CACHE).then((cache) => cache.put("/", copy));
      }
      return response;
    }).catch(() => caches.match("/")));
    return;
  }
  event.respondWith(caches.match(event.request).then((cached) => cached || fetch(event.request).then((response) => {
    if (response.ok) {
      const copy = response.clone();
      void caches.open(CACHE).then((cache) => cache.put(event.request, copy));
    }
    return response;
  })));
});
