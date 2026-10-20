/**
 * 만료 시각이 있는 값을 키별로 담는 인메모리 저장소(Spring common/BoundedExpiringStore 와 같은 정책).
 * - 만료 항목은 purgeIntervalMs 마다 한 번 요청 경로에서 지운다.
 * - 키 수가 maxKeys 에 닿은 상태에서 새 키가 오면 만료 항목을 먼저 지우고, 그래도 가득 차면 만료가 가장 이른 항목부터
 *   (상한의 1% 이상) 내보낸다.
 * JS 는 단일 스레드라 compute 는 원자적이다. 인스턴스(Workers 에서는 isolate)마다 따로다.
 */
export class BoundedExpiringStore<V> {
  private readonly entries = new Map<string, V>();
  private nextPurgeAt: number;

  constructor(
    private readonly clock: () => number,
    private readonly purgeIntervalMs: number,
    private readonly maxKeys: number,
    private readonly expiresAt: (value: V) => number,
  ) {
    if (purgeIntervalMs < 1 || maxKeys < 1) throw new Error('purgeIntervalMs and maxKeys must be positive');
    this.nextPurgeAt = clock() + purgeIntervalMs;
  }

  /** Map.compute: remapping 이 undefined 를 돌려주면 지운다 */
  compute(key: string, remapping: (existing: V | undefined) => V | undefined): V | undefined {
    const now = this.clock();
    this.purgeIfDue(now);
    if (!this.entries.has(key)) this.ensureCapacity(now);
    const next = remapping(this.entries.get(key));
    if (next === undefined) this.entries.delete(key);
    else this.entries.set(key, next);
    return next;
  }

  get(key: string): V | undefined {
    this.purgeIfDue(this.clock());
    return this.entries.get(key);
  }

  delete(key: string): void {
    this.entries.delete(key);
  }

  get size(): number {
    return this.entries.size;
  }

  private purgeIfDue(now: number) {
    if (now >= this.nextPurgeAt) {
      this.nextPurgeAt = now + this.purgeIntervalMs;
      this.purgeExpired(now);
    }
  }

  private purgeExpired(now: number) {
    for (const [key, value] of this.entries) {
      if (now >= this.expiresAt(value)) this.entries.delete(key);
    }
  }

  private ensureCapacity(now: number) {
    if (this.entries.size < this.maxKeys) return;
    this.purgeExpired(now);
    const overflow = this.entries.size - this.maxKeys + 1;
    if (overflow <= 0) return;
    const evictCount = Math.max(overflow, Math.floor(this.maxKeys / 100));
    [...this.entries.entries()]
      .sort((a, b) => this.expiresAt(a[1]) - this.expiresAt(b[1]))
      .slice(0, evictCount)
      .forEach(([key]) => this.entries.delete(key));
  }
}
