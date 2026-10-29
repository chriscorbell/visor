import '@fontsource-variable/ibm-plex-sans/wght.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App.tsx';
import './style.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

// Keeps the screen on where the browser allows it: installed, or over HTTPS.
async function stayAwake(): Promise<void> {
  if (document.visibilityState !== 'visible' || !('wakeLock' in navigator)) return;
  await navigator.wakeLock.request('screen').catch(() => {});
}
void stayAwake();
document.addEventListener('visibilitychange', () => void stayAwake());
