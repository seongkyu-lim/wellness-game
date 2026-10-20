import type { Db } from '../db/client.js';
import { pgErrorCode } from '../db/client.js';
import { randomUuid } from '../lib/crypto.js';
import { Instant } from '../lib/instant.js';

const INT_MAX = 2_147_483_647;

export interface CharacterStats {
  str: number;
  vit: number;
  intStat: number;
  discipline: number;
  recovery: number;
}

/** 캐릭터 상태(Spring UserCharacter). 레벨업 규칙: 다음 레벨 필요 XP = 100 + (level - 1) × 50 */
export class UserCharacter {
  constructor(
    readonly id: string,
    readonly userId: string,
    public level: number,
    public currentXp: number,
    public totalXp: number,
    readonly stats: CharacterStats,
  ) {}

  static create(userId: string): UserCharacter {
    return new UserCharacter(randomUuid(), userId, 1, 0, 0, { str: 0, vit: 0, intStat: 0, discipline: 0, recovery: 0 });
  }

  /** XP 를 더하고 레벨업했으면 true. int 범위를 넘으면 Integer.MAX_VALUE 로 포화 */
  addXp(gainedXp: number): boolean {
    if (gainedXp <= 0) return false;
    this.totalXp = saturatedAdd(this.totalXp, gainedXp);
    this.currentXp = saturatedAdd(this.currentXp, gainedXp);
    let leveledUp = false;
    while (this.currentXp >= this.nextLevelXp()) {
      this.currentXp -= this.nextLevelXp();
      this.level += 1;
      leveledUp = true;
    }
    return leveledUp;
  }

  nextLevelXp(): number {
    return Math.min(100 + (this.level - 1) * 50, INT_MAX);
  }
}

export function saturatedAdd(a: number, b: number): number {
  const sum = a + b;
  return sum > INT_MAX ? INT_MAX : sum;
}

/** API 응답의 character 객체(HealthActivitySyncResponse.CharacterResponse) */
export function characterResponse(character: UserCharacter) {
  return {
    level: character.level,
    currentXp: character.currentXp,
    totalXp: character.totalXp,
    nextLevelXp: character.nextLevelXp(),
    stats: {
      str: character.stats.str,
      vit: character.stats.vit,
      intStat: character.stats.intStat,
      discipline: character.stats.discipline,
      recovery: character.stats.recovery,
    },
  };
}

interface CharacterRow {
  id: string;
  user_id: string;
  level: number;
  current_xp: number;
  total_xp: number;
  str: number | null;
  vit: number | null;
  int_stat: number | null;
  discipline: number | null;
  recovery: number | null;
}

function fromRow(row: CharacterRow): UserCharacter {
  return new UserCharacter(row.id, row.user_id, row.level, row.current_xp, row.total_xp, {
    str: row.str ?? 0,
    vit: row.vit ?? 0,
    intStat: row.int_stat ?? 0,
    discipline: row.discipline ?? 0,
    recovery: row.recovery ?? 0,
  });
}

export const characters = {
  async findByUserId(db: Db, userId: string): Promise<UserCharacter | undefined> {
    const rows = await db<CharacterRow[]>`
      select id::text, user_id, level, current_xp, total_xp, str, vit, int_stat, discipline, recovery
      from user_character where user_id = ${userId}`;
    return rows[0] ? fromRow(rows[0]) : undefined;
  },

  /** 동기화 중 같은 사용자의 동시 요청을 직렬화하기 위한 행 잠금 조회. 트랜잭션 안에서만 부른다 */
  async findByUserIdForUpdate(db: Db, userId: string): Promise<UserCharacter | undefined> {
    const rows = await db<CharacterRow[]>`
      select id::text, user_id, level, current_xp, total_xp, str, vit, int_stat, discipline, recovery
      from user_character where user_id = ${userId} for update`;
    return rows[0] ? fromRow(rows[0]) : undefined;
  },

  async update(db: Db, c: UserCharacter, now: Instant): Promise<void> {
    await db`
      update user_character
      set level = ${c.level}, current_xp = ${c.currentXp}, total_xp = ${c.totalXp},
          str = ${c.stats.str}, vit = ${c.stats.vit}, int_stat = ${c.stats.intStat},
          discipline = ${c.stats.discipline}, recovery = ${c.stats.recovery},
          updated_at = ${now.toString()}::timestamptz
      where id = ${c.id}::uuid`;
  },

  /**
   * 캐릭터가 없으면 만든다(Spring UserCharacterProvisioner). 호출자 트랜잭션 밖에서, 잠금 조회보다 먼저 부른다.
   * 동시 요청이 먼저 만들었으면(uk_character_user 위반 23505) 무시한다. 단일 문장이라 자동 커밋된다.
   */
  async createIfAbsent(db: Db, userId: string, now: Instant): Promise<void> {
    if (await characters.findByUserId(db, userId)) return;
    const c = UserCharacter.create(userId);
    try {
      await db`
        insert into user_character (id, user_id, level, current_xp, total_xp, str, vit, int_stat, discipline, recovery, created_at, updated_at)
        values (${c.id}::uuid, ${userId}, 1, 0, 0, 0, 0, 0, 0, 0, ${now.toString()}::timestamptz, ${now.toString()}::timestamptz)`;
    } catch (error) {
      if (pgErrorCode(error) !== '23505') throw error;
    }
  },
};

export const nowInstant = (clock: () => number) => Instant.ofEpochMilli(clock());
