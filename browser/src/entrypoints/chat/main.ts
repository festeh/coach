import './app.css';
import App from './App.svelte';
import { mount } from 'svelte';

// Touchpad pinch arrives as ctrl+wheel. Left alone it zooms into a region of
// the page and carries the composer out of the window — cancel it. Scaling
// the whole chat stays available through regular page zoom (Ctrl +/-).
window.addEventListener(
  'wheel',
  (event) => {
    if (event.ctrlKey) event.preventDefault();
  },
  { passive: false }
);

const app = mount(App, {
  target: document.getElementById('app')!,
});

export default app;
