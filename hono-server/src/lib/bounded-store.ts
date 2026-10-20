/**
 * 만료 시각이 있는 값을 키별로 담는 인메모리 저장소(Spring common/BoundedExpiringStore 와 같은 정책).
 * - 만료 항목은 purgeIntervalMs 마다 한 번 요청 경로에서 지운다.
 * - 키 수가 maxKeys 에 닿은 상태에서 새 키가 오면 만료 항목을 먼저 지우고, 그래도 가득 차면 만료가 가장 이른 항목부터
 *   (상한의 1% 이상) 내보낸다. 단 isPinned 가 true 인 항목(예: 잠금 중인 로그인 실패 기록)은 내보내지 않는다.
 *   모두 고정이면 상한을 잠시 넘더라도 내보내지 않는다(잠금을 풀어 주는 쪽이 더 위험하다).
 * - 축출 순서: 만료 시각이 바뀔 때 항목을 Map 끝으로 옮겨 삽입 순서 = 만료 순서로 유지한다. 모든 호출자가
 *   "지금 + 고정 기간"으로 만료 시각을 정하므로 성립하고, 축출 때 전체를 정렬하지 않고 앞에서부터 본다.
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
    private readonly isPinned: (value: V) => boolean = () => false,
  ) {
    if (purgeIntervalMs < 1 || maxKeys < 1) throw new Error('purgeIntervalMs and maxKeys must be positive');
    this.nextPurgeAt = clock() + purgeIntervalMs;
  }

  /** Map.compute: remapping 이 undefined 를 돌려주면 지운다 */
  compute(key: string, remapping: (existing: V | undefined) => V | undefined): V | undefined {
    const now = this.clock();
    this.purgeIfDue(now);
    if (!this.entries.has(key)) this.ensureCapacity(now);
    const existing = this.entries.get(key);
    const next = remapping(existing);
    if (next === undefined) {
      this.entries.delete(key);
    } else {
      // 만료 시각이 바뀌면 끝으로 옮겨 삽입 순서 = 만료 순서를 유지한다
      if (existing !== undefined && this.expiresAt(existing) !== this.expiresAt(next)) this.entries.delete(key);
      this.entries.set(key, next);
    }
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
    let toEvict = Math.max(overflow, Math.floor(this.maxKeys / 100));
    for (const [key, value] of this.entries) {
      if (toEvict <= 0) break;
      if (this.isPinned(value)) continue;
      this.entries.delete(key);
      toEvict--;
    }
  }
}
