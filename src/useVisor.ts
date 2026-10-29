import { useEffect, useState } from 'react';
import type { Hello, Snapshot } from '../shared/types.ts';

export type ServerLink = 'connecting' | 'open' | 'lost';

/**
 * The server's once-a-second snapshot. When the server comes back with a new
 * build, the page reloads to pick it up.
 */
export function useVisor(): { snapshot: Snapshot | null; server: ServerLink; hello: Hello | null } {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [hello, setHello] = useState<Hello | null>(null);
  const [server, setServer] = useState<ServerLink>('connecting');

  useEffect(() => {
    let build: string | null = null;
    let source: EventSource | null = null;
    let retry = 0;
    const open = () => {
      source = new EventSource('/api/state');
      source.addEventListener('hello', (e) => {
        const hello: Hello = JSON.parse(e.data);
        if (build !== null && hello.build !== build) location.reload();
        build = hello.build;
        setHello(hello);
        setServer('open');
      });
      source.addEventListener('state', (e) => setSnapshot(JSON.parse(e.data)));
      source.onerror = () => {
        setServer('lost');
        // EventSource retries a dropped connection itself, but gives up on an HTTP error.
        if (source?.readyState === EventSource.CLOSED) {
          source = null;
          retry = window.setTimeout(open, 3000);
        }
      };
    };
    open();
    return () => {
      window.clearTimeout(retry);
      source?.close();
    };
  }, []);

  return { snapshot, server, hello };
}
