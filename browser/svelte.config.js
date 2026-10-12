export default {
  vitePlugin: {
    // Force runes mode for our own components: legacy syntax ($:, export let)
    // becomes a compile error instead of silently flipping a component into
    // legacy mode — whose templates don't subscribe to runes-class signals.
    // (The settings header once froze on CoachState exactly that way.)
    // node_modules (lucide-svelte) keep whatever mode they were written for.
    dynamicCompileOptions({ filename }) {
      if (!filename.includes('node_modules')) {
        return { runes: true };
      }
    }
  }
};
