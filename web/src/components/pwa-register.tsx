"use client";

import { useEffect } from "react";

// Registers the service worker so the app is installable (Android "Add to Home
// screen", iOS Safari "Share → Add to Home Screen") and loads offline.
export function PwaRegister() {
  useEffect(() => {
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.register("/sw.js").catch(() => {
        /* PWA is progressive enhancement — ignore registration failures */
      });
    }
  }, []);
  return null;
}
