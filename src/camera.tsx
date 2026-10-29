import { VideoOff } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Feed } from './feed.ts';
import type { FeedState } from './feed.ts';
import { Tile } from './ui.tsx';

const LABELS: Record<FeedState, string> = {
  live: 'Live',
  connecting: 'Connecting',
  reconnecting: 'Reconnecting',
};

/**
 * The camera's picture, with its name and state under it. Nothing covers the
 * picture while it's live. It stops while the page is hidden.
 */
export function CameraTile({ name, order }: { name: string; order: number }) {
  const video = useRef<HTMLVideoElement>(null);
  const [state, setState] = useState<FeedState>('connecting');
  // The picture fades in the first time it plays, then keeps its last frame through a reconnect.
  const [seen, setSeen] = useState(false);

  useEffect(() => {
    const feed = new Feed(video.current!, '/api/feed', setState);
    const follow = () => (document.hidden ? feed.stop() : feed.start());
    follow();
    document.addEventListener('visibilitychange', follow);
    return () => {
      document.removeEventListener('visibilitychange', follow);
      feed.stop();
    };
  }, []);

  useEffect(() => {
    if (state === 'live') setSeen(true);
  }, [state]);

  return (
    <Tile order={order} className={`camera ${state}${seen ? ' seen' : ''}`}>
      <div className="picture">
        <video ref={video} muted playsInline disablePictureInPicture disableRemotePlayback />
        {state !== 'live' && (
          <div className="veil">
            <VideoOff />
          </div>
        )}
      </div>
      <div className="caption">
        <span>{name}</span>
        <span className="feed-state">{LABELS[state]}</span>
      </div>
    </Tile>
  );
}
