import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { Feed } from './feed.ts';
import type { FeedState } from './feed.ts';
import { Dot, Panel } from './ui.tsx';

const LABELS: Record<FeedState, [string, string]> = {
  live: ['LIVE', 'hi'],
  connecting: ['LINKING', 'muted'],
  reconnecting: ['SIGNAL LOST', 'red'],
};

/** The Camera's picture, with HUD brackets on its corners. It stops while the page is hidden. */
export function CameraTile({ name, area, order }: { name: string; area: CSSProperties; order: number }) {
  const video = useRef<HTMLVideoElement>(null);
  const [state, setState] = useState<FeedState>('connecting');

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

  const [label, color] = LABELS[state];
  return (
    <Panel area={area} order={order} className={`camera feed-${state}`}>
      <div className="tile-head">
        <span className="title">CAM-01 // {name}</span>
        <span className="state">
          <Dot color={color} />
          {label}
        </span>
      </div>
      <div className="picture-area">
        <div className="picture">
          <video ref={video} muted playsInline disablePictureInPicture disableRemotePlayback />
          <i className="corner tl" />
          <i className="corner tr" />
          <i className="corner bl" />
          <i className="corner br" />
        </div>
      </div>
    </Panel>
  );
}
