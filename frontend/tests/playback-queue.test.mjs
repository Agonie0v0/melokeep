import assert from "node:assert/strict";
import test from "node:test";
import {
  buildShuffledTracks,
  playbackSignature,
  resolvePlaybackStep,
} from "../src/playback-queue.ts";

function makeTrack(id) {
  return {
    id,
    source: "local",
    source_id: id,
    title: `Track ${id}`,
    artist: "Artist",
    album: "Album",
    duration: 180,
    cover: null,
    qualities: [],
    playback: {
      state: "ready",
      url: `/audio/${id}.mp3`,
      codec: "mp3",
      bitrate: 320,
      detail: null,
      preview_only: false,
    },
  };
}

const emptyShuffle = () => ({ signature: "", tracks: [], cursor: -1 });
const idsOf = (list) => list.map((t) => t.id);

test("playbackSignature distinguishes queue vs context lists", () => {
  const a = makeTrack("a");
  const b = makeTrack("b");
  assert.equal(playbackSignature([a, b], true), "q:a\0b");
  assert.equal(playbackSignature([a, b], false), "l:a\0b");
});

test("buildShuffledTracks anchors current track or avoids first track", () => {
  const tracks = ["a", "b", "c", "d"].map(makeTrack);
  const anchored = buildShuffledTracks(tracks, "c", undefined, () => 0);
  assert.equal(anchored[0].id, "c");
  assert.deepEqual(idsOf(anchored).sort(), ["a", "b", "c", "d"]);

  // With randomFn = () => 0, Fisher-Yates rotates pool so first element is "b";
  // with avoidFirstId="b", it swaps index 0 with index 1.
  const avoided = buildShuffledTracks(tracks, undefined, "b", () => 0);
  assert.notEqual(avoided[0].id, "b");
  assert.deepEqual(idsOf(avoided).sort(), ["a", "b", "c", "d"]);
});

test("single-track context with pending playlist when X is not in playlist (shuffle=false)", () => {
  const x = makeTrack("x");
  const a = makeTrack("a");
  const b = makeTrack("b");
  const c = makeTrack("c");
  const playlist = [a, b, c];
  const playlistSnapshot = [...playlist];

  for (const repeat of [false, true]) {
    for (const autoAdvance of [false, true]) {
      const next = resolvePlaybackStep({
        direction: 1,
        autoAdvance,
        current: x,
        fromQueue: false,
        contextTracks: [x],
        playlistTracks: playlist,
        repeat,
        shuffle: false,
        shuffleState: emptyShuffle(),
      });
      assert.ok(next);
      assert.equal(next.fromQueue, true);
      assert.equal(next.index, 0);
      assert.equal(next.list[next.index].id, "a");

      const prev = resolvePlaybackStep({
        direction: -1,
        autoAdvance,
        current: x,
        fromQueue: false,
        contextTracks: [x],
        playlistTracks: playlist,
        repeat,
        shuffle: false,
        shuffleState: emptyShuffle(),
      });
      assert.ok(prev);
      assert.equal(prev.fromQueue, true);
      assert.equal(prev.index, 2);
      assert.equal(prev.list[prev.index].id, "c");
    }
  }
  assert.deepEqual(idsOf(playlist), idsOf(playlistSnapshot));
});

test("single-track context with pending playlist when X is in middle of playlist (shuffle=false)", () => {
  const a = makeTrack("a");
  const x = makeTrack("x");
  const b = makeTrack("b");
  const playlist = [a, x, b];

  for (const repeat of [false, true]) {
    for (const autoAdvance of [false, true]) {
      const next = resolvePlaybackStep({
        direction: 1,
        autoAdvance,
        current: x,
        fromQueue: false,
        contextTracks: [x],
        playlistTracks: playlist,
        repeat,
        shuffle: false,
        shuffleState: emptyShuffle(),
      });
      assert.ok(next);
      assert.equal(next.fromQueue, true);
      assert.equal(next.index, 2);
      assert.equal(next.list[next.index].id, "b");

      const prev = resolvePlaybackStep({
        direction: -1,
        autoAdvance,
        current: x,
        fromQueue: false,
        contextTracks: [x],
        playlistTracks: playlist,
        repeat,
        shuffle: false,
        shuffleState: emptyShuffle(),
      });
      assert.ok(prev);
      assert.equal(prev.fromQueue, true);
      assert.equal(prev.index, 0);
      assert.equal(prev.list[prev.index].id, "a");
    }
  }
});

test("single-track context with pending playlist when X is at end of playlist (shuffle=false)", () => {
  const a = makeTrack("a");
  const b = makeTrack("b");
  const x = makeTrack("x");
  const playlist = [a, b, x];

  const autoEndStop = resolvePlaybackStep({
    direction: 1,
    autoAdvance: true,
    current: x,
    fromQueue: false,
    contextTracks: [x],
    playlistTracks: playlist,
    repeat: false,
    shuffle: false,
    shuffleState: emptyShuffle(),
  });
  assert.equal(autoEndStop, null);

  const manualNextWrap = resolvePlaybackStep({
    direction: 1,
    autoAdvance: false,
    current: x,
    fromQueue: false,
    contextTracks: [x],
    playlistTracks: playlist,
    repeat: false,
    shuffle: false,
    shuffleState: emptyShuffle(),
  });
  assert.ok(manualNextWrap);
  assert.equal(manualNextWrap.fromQueue, true);
  assert.equal(manualNextWrap.index, 0);
  assert.equal(manualNextWrap.list[0].id, "a");

  const repeatAutoWrap = resolvePlaybackStep({
    direction: 1,
    autoAdvance: true,
    current: x,
    fromQueue: false,
    contextTracks: [x],
    playlistTracks: playlist,
    repeat: true,
    shuffle: false,
    shuffleState: emptyShuffle(),
  });
  assert.ok(repeatAutoWrap);
  assert.equal(repeatAutoWrap.fromQueue, true);
  assert.equal(repeatAutoWrap.index, 0);
  assert.equal(repeatAutoWrap.list[0].id, "a");

  const prev = resolvePlaybackStep({
    direction: -1,
    autoAdvance: false,
    current: x,
    fromQueue: false,
    contextTracks: [x],
    playlistTracks: playlist,
    repeat: false,
    shuffle: false,
    shuffleState: emptyShuffle(),
  });
  assert.ok(prev);
  assert.equal(prev.fromQueue, true);
  assert.equal(prev.index, 1);
  assert.equal(prev.list[1].id, "b");
});

test("single-track context with pending playlist under shuffle=true", () => {
  const a = makeTrack("a");
  const b = makeTrack("b");
  const c = makeTrack("c");
  const x = makeTrack("x");
  const rng = () => 0.5;

  // X not in playlist: builds shuffled playlist avoiding X, next=0, prev=length-1
  const notInQueue = [a, b, c];
  const nextOutside = resolvePlaybackStep({
    direction: 1,
    autoAdvance: true,
    current: x,
    fromQueue: false,
    contextTracks: [x],
    playlistTracks: notInQueue,
    repeat: false,
    shuffle: true,
    shuffleState: emptyShuffle(),
    randomFn: rng,
  });
  assert.ok(nextOutside);
  assert.equal(nextOutside.fromQueue, true);
  assert.equal(nextOutside.index, 0);
  assert.equal(nextOutside.canonicalList, notInQueue);
  assert.equal(
    nextOutside.nextShuffleState?.signature,
    playbackSignature(notInQueue, true),
  );
  assert.equal(nextOutside.nextShuffleState?.cursor, 0);

  const prevOutside = resolvePlaybackStep({
    direction: -1,
    autoAdvance: false,
    current: x,
    fromQueue: false,
    contextTracks: [x],
    playlistTracks: notInQueue,
    repeat: false,
    shuffle: true,
    shuffleState: emptyShuffle(),
    randomFn: rng,
  });
  assert.ok(prevOutside);
  assert.equal(prevOutside.fromQueue, true);
  assert.equal(prevOutside.index, 2);
  assert.equal(prevOutside.nextShuffleState?.cursor, 2);

  // X in middle or end of playlist: anchored at 0, next=1, prev=length-1
  for (const queue of [
    [a, x, b],
    [a, b, x],
  ]) {
    const nextIn = resolvePlaybackStep({
      direction: 1,
      autoAdvance: true,
      current: x,
      fromQueue: false,
      contextTracks: [x],
      playlistTracks: queue,
      repeat: false,
      shuffle: true,
      shuffleState: emptyShuffle(),
      randomFn: rng,
    });
    assert.ok(nextIn);
    assert.equal(nextIn.fromQueue, true);
    assert.equal(nextIn.list[0].id, "x");
    assert.equal(nextIn.index, 1);
    assert.notEqual(nextIn.list[nextIn.index].id, "x");
    assert.equal(nextIn.nextShuffleState?.cursor, 1);

    const prevIn = resolvePlaybackStep({
      direction: -1,
      autoAdvance: false,
      current: x,
      fromQueue: false,
      contextTracks: [x],
      playlistTracks: queue,
      repeat: true,
      shuffle: true,
      shuffleState: emptyShuffle(),
      randomFn: rng,
    });
    assert.ok(prevIn);
    assert.equal(prevIn.fromQueue, true);
    assert.equal(prevIn.list[0].id, "x");
    assert.equal(prevIn.index, queue.length - 1);
    assert.notEqual(prevIn.list[prevIn.index].id, "x");
    assert.equal(prevIn.nextShuffleState?.cursor, queue.length - 1);
  }
});

test("multi-track context steps normally and continues to pending queue at end", () => {
  const x = makeTrack("x");
  const y = makeTrack("y");
  const a = makeTrack("a");
  const b = makeTrack("b");
  const context = [x, y];
  const playlist = [a, b];

  // Middle of context stays in context
  const midNext = resolvePlaybackStep({
    direction: 1,
    autoAdvance: true,
    current: x,
    fromQueue: false,
    contextTracks: context,
    playlistTracks: playlist,
    repeat: false,
    shuffle: false,
    shuffleState: emptyShuffle(),
  });
  assert.ok(midNext);
  assert.equal(midNext.fromQueue, false);
  assert.equal(midNext.index, 1);
  assert.equal(midNext.list[1].id, "y");

  // Prev from start of context wraps in context when shuffle=false
  const startPrev = resolvePlaybackStep({
    direction: -1,
    current: x,
    fromQueue: false,
    contextTracks: context,
    playlistTracks: playlist,
    repeat: false,
    shuffle: false,
    shuffleState: emptyShuffle(),
  });
  assert.ok(startPrev);
  assert.equal(startPrev.fromQueue, false);
  assert.equal(startPrev.index, 1);

  // End of context with repeat=false continues to pending playlist
  const endContinue = resolvePlaybackStep({
    direction: 1,
    autoAdvance: true,
    current: y,
    fromQueue: false,
    contextTracks: context,
    playlistTracks: playlist,
    repeat: false,
    shuffle: false,
    shuffleState: emptyShuffle(),
  });
  assert.ok(endContinue);
  assert.equal(endContinue.fromQueue, true);
  assert.equal(endContinue.index, 0);
  assert.equal(endContinue.list[0].id, "a");

  // End of context with repeat=true wraps inside context
  const endRepeat = resolvePlaybackStep({
    direction: 1,
    autoAdvance: true,
    current: y,
    fromQueue: false,
    contextTracks: context,
    playlistTracks: playlist,
    repeat: true,
    shuffle: false,
    shuffleState: emptyShuffle(),
  });
  assert.ok(endRepeat);
  assert.equal(endRepeat.fromQueue, false);
  assert.equal(endRepeat.index, 0);
  assert.equal(endRepeat.list[0].id, "x");

  // Multi-track shuffle: advancing past shuffled end hands off to playlist
  const contextSig = playbackSignature(context, false);
  const shuffledEndContinue = resolvePlaybackStep({
    direction: 1,
    autoAdvance: true,
    current: y,
    fromQueue: false,
    contextTracks: context,
    playlistTracks: playlist,
    repeat: false,
    shuffle: true,
    shuffleState: { signature: contextSig, tracks: [x, y], cursor: 1 },
    randomFn: () => 0.25,
  });
  assert.ok(shuffledEndContinue);
  assert.equal(shuffledEndContinue.fromQueue, true);
  assert.equal(shuffledEndContinue.canonicalList, playlist);
  assert.equal(shuffledEndContinue.index, 0);

  // Multi-track shuffle: going backwards at cursor 0 with repeat=false stops (no handoff)
  const shuffledStartPrev = resolvePlaybackStep({
    direction: -1,
    autoAdvance: false,
    current: x,
    fromQueue: false,
    contextTracks: context,
    playlistTracks: playlist,
    repeat: false,
    shuffle: true,
    shuffleState: { signature: contextSig, tracks: [x, y], cursor: 0 },
  });
  assert.equal(shuffledStartPrev, null);
});

test("empty playlist fallback and single-track queue without other tracks", () => {
  const x = makeTrack("x");

  // Empty playlist, single-track context
  assert.equal(
    resolvePlaybackStep({
      direction: 1,
      autoAdvance: true,
      current: x,
      fromQueue: false,
      contextTracks: [x],
      playlistTracks: [],
      repeat: false,
      shuffle: false,
      shuffleState: emptyShuffle(),
    }),
    null,
  );

  const repeatSingle = resolvePlaybackStep({
    direction: 1,
    autoAdvance: true,
    current: x,
    fromQueue: false,
    contextTracks: [x],
    playlistTracks: [],
    repeat: true,
    shuffle: false,
    shuffleState: emptyShuffle(),
  });
  assert.ok(repeatSingle);
  assert.equal(repeatSingle.fromQueue, false);
  assert.equal(repeatSingle.index, 0);

  // Playlist only contains X -> no other queue track -> falls back to context rules
  assert.equal(
    resolvePlaybackStep({
      direction: 1,
      autoAdvance: true,
      current: x,
      fromQueue: false,
      contextTracks: [x],
      playlistTracks: [x],
      repeat: false,
      shuffle: false,
      shuffleState: emptyShuffle(),
    }),
    null,
  );

  // Empty context and empty playlist -> null
  assert.equal(
    resolvePlaybackStep({
      direction: 1,
      current: null,
      fromQueue: false,
      contextTracks: [],
      playlistTracks: [],
      repeat: true,
      shuffle: false,
      shuffleState: emptyShuffle(),
    }),
    null,
  );
});

test("normal fromQueue=true stepping in sequential and shuffle modes", () => {
  const a = makeTrack("a");
  const b = makeTrack("b");
  const c = makeTrack("c");
  const playlist = [a, b, c];

  assert.equal(
    resolvePlaybackStep({
      direction: 1,
      current: a,
      fromQueue: true,
      contextTracks: [],
      playlistTracks: [],
      repeat: true,
      shuffle: false,
      shuffleState: emptyShuffle(),
    }),
    null,
  );

  const seqNext = resolvePlaybackStep({
    direction: 1,
    current: a,
    fromQueue: true,
    contextTracks: [],
    playlistTracks: playlist,
    repeat: false,
    shuffle: false,
    shuffleState: emptyShuffle(),
  });
  assert.ok(seqNext);
  assert.equal(seqNext.fromQueue, true);
  assert.equal(seqNext.index, 1);

  assert.equal(
    resolvePlaybackStep({
      direction: 1,
      current: c,
      fromQueue: true,
      contextTracks: [],
      playlistTracks: playlist,
      repeat: false,
      shuffle: false,
      shuffleState: emptyShuffle(),
    }),
    null,
  );

  const seqRepeatWrap = resolvePlaybackStep({
    direction: 1,
    current: c,
    fromQueue: true,
    contextTracks: [],
    playlistTracks: playlist,
    repeat: true,
    shuffle: false,
    shuffleState: emptyShuffle(),
  });
  assert.ok(seqRepeatWrap);
  assert.equal(seqRepeatWrap.index, 0);

  const seqPrevWrap = resolvePlaybackStep({
    direction: -1,
    current: a,
    fromQueue: true,
    contextTracks: [],
    playlistTracks: playlist,
    repeat: false,
    shuffle: false,
    shuffleState: emptyShuffle(),
  });
  assert.ok(seqPrevWrap);
  assert.equal(seqPrevWrap.index, 2);

  // Shuffle mode: initializes and reuses shuffleState
  const firstShuffle = resolvePlaybackStep({
    direction: 1,
    current: b,
    fromQueue: true,
    contextTracks: [],
    playlistTracks: playlist,
    repeat: false,
    shuffle: true,
    shuffleState: emptyShuffle(),
    randomFn: () => 0,
  });
  assert.ok(firstShuffle);
  assert.equal(firstShuffle.fromQueue, true);
  assert.equal(firstShuffle.index, 1);
  assert.equal(firstShuffle.canonicalList, playlist);
  assert.equal(firstShuffle.list[0].id, "b");
  assert.equal(firstShuffle.nextShuffleState?.cursor, 1);

  const secondShuffle = resolvePlaybackStep({
    direction: 1,
    current: firstShuffle.list[1],
    fromQueue: true,
    contextTracks: [],
    playlistTracks: playlist,
    repeat: false,
    shuffle: true,
    shuffleState: firstShuffle.nextShuffleState,
  });
  assert.ok(secondShuffle);
  assert.equal(secondShuffle.index, 2);
  assert.equal(secondShuffle.list, firstShuffle.list);
  assert.equal(secondShuffle.nextShuffleState?.cursor, 2);

  // At end of shuffle with repeat=false -> null
  assert.equal(
    resolvePlaybackStep({
      direction: 1,
      current: secondShuffle.list[2],
      fromQueue: true,
      contextTracks: [],
      playlistTracks: playlist,
      repeat: false,
      shuffle: true,
      shuffleState: secondShuffle.nextShuffleState,
    }),
    null,
  );

  // At end of shuffle with repeat=true -> reshuffles avoiding last track
  const endId = secondShuffle.list[2].id;
  const reshuffled = resolvePlaybackStep({
    direction: 1,
    current: secondShuffle.list[2],
    fromQueue: true,
    contextTracks: [],
    playlistTracks: playlist,
    repeat: true,
    shuffle: true,
    shuffleState: secondShuffle.nextShuffleState,
    randomFn: () => 0,
  });
  assert.ok(reshuffled);
  assert.equal(reshuffled.index, 0);
  assert.notEqual(reshuffled.list[0].id, endId);
  assert.equal(reshuffled.nextShuffleState?.cursor, 0);

  // At start of shuffle with prev & repeat=false -> null; repeat=true -> last index
  const qSig = playbackSignature(playlist, true);
  assert.equal(
    resolvePlaybackStep({
      direction: -1,
      current: a,
      fromQueue: true,
      contextTracks: [],
      playlistTracks: playlist,
      repeat: false,
      shuffle: true,
      shuffleState: { signature: qSig, tracks: [a, b, c], cursor: 0 },
    }),
    null,
  );

  const prevShuffleWrap = resolvePlaybackStep({
    direction: -1,
    current: a,
    fromQueue: true,
    contextTracks: [],
    playlistTracks: playlist,
    repeat: true,
    shuffle: true,
    shuffleState: { signature: qSig, tracks: [a, b, c], cursor: 0 },
  });
  assert.ok(prevShuffleWrap);
  assert.equal(prevShuffleWrap.index, 2);
  assert.equal(prevShuffleWrap.nextShuffleState?.cursor, 2);
});
