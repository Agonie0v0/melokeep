let currentTimeSec = 0;
const listeners = new Set<() => void>();

export const playbackClock = {
  get: (): number => currentTimeSec,
  set: (next: number) => {
    const clean = Number.isFinite(next) && next >= 0 ? next : 0;
    if (
      clean !== 0 &&
      Math.abs(clean - currentTimeSec) < 0.05 &&
      currentTimeSec !== 0
    ) {
      return;
    }
    if (clean === currentTimeSec) return;
    currentTimeSec = clean;
    for (const listener of listeners) {
      listener();
    }
  },
  subscribe: (listener: () => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
};
