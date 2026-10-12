// A specific label for this browser, so temptations record which one I was in.
// Build target separates the Firefox and Chromium families; on Chromium the
// userAgentData brand tells Chromium from Chrome/Brave/Edge. Two installs of
// the same brand still collide — an options-page override could fix that later.

interface UADataBrand {
  brand: string;
  version: string;
}

function chromiumBrand(): string {
  const brands = (navigator as unknown as { userAgentData?: { brands?: UADataBrand[] } })
    .userAgentData?.brands;
  if (!brands) return "chrome";
  const names = brands.map((b) => b.brand.toLowerCase());
  // Order matters: the distinctive brand wins over the generic "chromium".
  for (const known of ["brave", "microsoft edge", "google chrome", "chromium"]) {
    const hit = names.find((n) => n.includes(known));
    if (hit) {
      if (known === "microsoft edge") return "edge";
      if (known === "google chrome") return "chrome";
      return known; // "brave" | "chromium"
    }
  }
  return "chrome";
}

export function browserSource(): string {
  if (import.meta.env.BROWSER === "firefox") {
    return navigator.userAgent.includes("Android") ? "firefox-android" : "firefox";
  }
  return chromiumBrand();
}
