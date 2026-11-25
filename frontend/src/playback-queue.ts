import type { Track } from "./types";

export interface ShuffleState {
  signature: string;
  tracks: Track[];
  cursor: number;
}

export interface PlaybackStepInput {
  direction: number;
  autoAdvance?: boolean;
  current: Track | null;
  fromQueue: boolean;
  contextTracks: Track[];
  playlistTracks: Track[];
  repeat: boolean;
  shuffle: boolean;
  shuffleState: ShuffleState;
  randomFn?: () => number;
}

export interface PlaybackStepResult {
  index: number;
  list: Track[];
  fromQueue: boolean;
  canonicalList?: Track[];
  nextShuffleState?: ShuffleState;
}

export function playbackSignature(list: Track[], fromQueue: boolean): string {
  return `${fromQueue ? "q" : "l"}:${list.map((t) => t.id).join("\0")}`;
}

export function buildShuffledTracks(
  list: Track[],
  anchorId?: string,
  avoidFirstId?: string,
  randomFn: () => number = Math.random,
): Track[] {
  const pool = [...list];
  let anchor: Track | undefined;
  if (anchorId) {
    const idx = pool.findIndex((t) => t.id === anchorId);
    if (idx >= 0) {
      [anchor] = pool.splice(idx, 1);
    }
  }
  for (let i = pool.length - 1; i > 0; i--) {
    const j = Math.floor(randomFn() * (i + 1));
    [pool[i], pool[j]] = [pool[j], pool[i]];
  }
  if (anchor) {
    return [anchor, ...pool];
  }
  if (avoidFirstId && pool.length > 1 && pool[0].id === avoidFirstId) {
    const swapIdx = 1 + Math.floor(randomFn() * (pool.length - 1));
    [pool[0], pool[swapIdx]] = [pool[swapIdx], pool[0]];
  }
  return pool;
}

function stepWithinList(
  list: Track[],
  targetFromQueue: boolean,
  dir: 1 | -1,
  current: Track | null,
  repeat: boolean,
  shuffle: boolean,
  shuffleState: ShuffleState,
  randomFn: () => number,
): PlaybackStepResult | null {
  if (!list.length) return null;
  if (shuffle) {
    const sig = playbackSignature(list, targetFromQueue);
    const canReuse =
      shuffleState.signature === sig &&
      shuffleState.tracks.length === list.length;
    let tracks = canReuse
      ? shuffleState.tracks
      : buildShuffledTracks(list, current?.id, undefined, randomFn);
    const cursor = canReuse ? shuffleState.cursor : 0;
    let next = cursor + dir;
    if (next >= tracks.length) {
      if (!repeat) return null;
      const lastId = current?.id || tracks[tracks.length - 1]?.id;
      tracks = buildShuffledTracks(list, undefined, lastId, randomFn);
      next = 0;
    } else if (next < 0) {
      if (!repeat) return null;
      next = tracks.length - 1;
    }
    return {
      index: next,
      list: tracks,
      fromQueue: targetFromQueue,
      canonicalList: list,
      nextShuffleState: {
        signature: sig,
        tracks,
        cursor: next,
      },
    };
  }

  const idx = list.findIndex((t) => t.id === current?.id);
  let next = idx + dir;
  if (next >= list.length) {
    if (!repeat) return null;
    next = 0;
  }
  if (next < 0) {
    next = list.length - 1;
  }
  return {
    index: next,
    list,
    fromQueue: targetFromQueue,
  };
}

export function resolvePlaybackStep(
  input: PlaybackStepInput,
): PlaybackStepResult | null {
  const dir: 1 | -1 = input.direction >= 0 ? 1 : -1;
  const autoAdvance = Boolean(input.autoAdvance);
  const randomFn = input.randomFn ?? Math.random;
  const {
    current,
    fromQueue,
    contextTracks,
    playlistTracks,
    repeat,
    shuffle,
    shuffleState,
  } = input;

  if (fromQueue) {
    return stepWithinList(
      playlistTracks,
      true,
      dir,
      current,
      repeat,
      shuffle,
      shuffleState,
      randomFn,
    );
  }

  const hasOtherContextTrack = contextTracks.some((t) => t.id !== current?.id);
  const hasOtherQueueTrack = playlistTracks.some((t) => t.id !== current?.id);

  function handoffToQueue(): PlaybackStepResult | null {
    const qIdx = current
      ? playlistTracks.findIndex((t) => t.id === current.id)
      : -1;
    if (shuffle) {
      const sig = playbackSignature(playlistTracks, true);
      if (qIdx >= 0 && current) {
        const tracks = buildShuffledTracks(
          playlistTracks,
          current.id,
          undefined,
          randomFn,
        );
        const next = dir > 0 ? 1 : tracks.length - 1;
        return {
          index: next,
          list: tracks,
          fromQueue: true,
          canonicalList: playlistTracks,
          nextShuffleState: {
            signature: sig,
            tracks,
            cursor: next,
          },
        };
      }
      const tracks = buildShuffledTracks(
        playlistTracks,
        undefined,
        current?.id,
        randomFn,
      );
      const next = dir > 0 ? 0 : tracks.length - 1;
      return {
        index: next,
        list: tracks,
        fromQueue: true,
        canonicalList: playlistTracks,
        nextShuffleState: {
          signature: sig,
          tracks,
          cursor: next,
        },
      };
    }

    if (qIdx < 0) {
      const next = dir > 0 ? 0 : playlistTracks.length - 1;
      return {
        index: next,
        list: playlistTracks,
        fromQueue: true,
      };
    }

    let next = qIdx + dir;
    if (next >= playlistTracks.length) {
      if (repeat || !autoAdvance) {
        next = 0;
      } else {
        return null;
      }
    }
    if (next < 0) {
      next = playlistTracks.length - 1;
    }
    return {
      index: next,
      list: playlistTracks,
      fromQueue: true,
    };
  }

  if (!hasOtherContextTrack && hasOtherQueueTrack) {
    return handoffToQueue();
  }

  if (!contextTracks.length) {
    return null;
  }

  const contextResult = stepWithinList(
    contextTracks,
    false,
    dir,
    current,
    repeat,
    shuffle,
    shuffleState,
    randomFn,
  );

  if (!contextResult && !repeat && dir > 0 && hasOtherQueueTrack) {
    return handoffToQueue();
  }

  return contextResult;
}
