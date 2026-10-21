import { describe, expect, it } from 'vitest'
import { GROWTH_STAGES } from '../lib/growthStage'
import { LEGAL_DOCUMENTS } from '../legal/legal'
import { format, translate } from './core'
import { DICTIONARIES, type MessageKey } from './dictionaries'

const ko = DICTIONARIES.ko
const en = DICTIONARIES.en
const keys = Object.keys(ko) as MessageKey[]
const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort()

describe('ko/en 사전 짝 맞춤', () => {
  it('두 언어의 키 집합이 같다(타입 검사를 우회한 추가·누락도 잡는다)', () => {
    expect(Object.keys(en).sort()).toEqual([...keys].sort())
  })

  it('모든 문구가 비어 있지 않다', () => {
    const empty = keys.flatMap((key) => ['ko', 'en'].filter((lang) => !DICTIONARIES[lang as 'ko' | 'en'][key].trim()).map((lang) => `${lang}:${key}`))
    expect(empty).toEqual([])
  })

  it('같은 키의 자리표시자({name})가 두 언어에서 같다', () => {
    const mismatched = keys.filter((key) => placeholders(ko[key]).join() !== placeholders(en[key]).join())
    expect(mismatched).toEqual([])
  })

  it('영어 사전에 번역되지 않은 한글이 남아 있지 않다(언어 이름 표기 제외)', () => {
    const untranslated = keys.filter((key) => key !== 'lang.ko' && /[가-힣]/.test(en[key]))
    expect(untranslated).toEqual([])
  })

  it('코드가 조합해 쓰는 동적 키(성장 단계·스탯·운동 종류·법적 고지)가 모두 사전에 있다', () => {
    const dynamic: string[] = [
      ...GROWTH_STAGES.map((s) => `stage.${s.key}`),
      ...['str', 'vit', 'discipline', 'recovery'].map((s) => `stat.${s}`),
      ...['SWIMMING', 'RUNNING', 'WALKING', 'CYCLING', 'STRENGTH_TRAINING', 'OTHER'].map((w) => `workout.${w}`),
      ...Object.values(LEGAL_DOCUMENTS).flatMap((d) => [d.title, d.intro, ...d.sections.flatMap((s) => [s.title, s.body])]),
    ]
    expect(dynamic.filter((key) => !(key in ko))).toEqual([])
  })
})

describe('보간', () => {
  it('같은 자리표시자가 여러 번 나와도 모두 치환하고 숫자 0도 그대로 넣는다', () => {
    expect(format('{n}/{n} ({m})', { n: 0, m: 'x' })).toBe('0/0 (x)')
  })

  it('정규식 특수 문자나 $ 패턴이 든 값도 문자 그대로 넣는다', () => {
    expect(format('{a}', { a: '$& $1 \\d+' })).toBe('$& $1 \\d+')
  })

  it.each([
    ['ko', 'error.request', { status: 503 }, '요청에 실패했습니다. (503)'],
    ['en', 'error.request', { status: 503 }, 'Request failed (503).'],
    ['ko', 'character.bubbleNext', { level: 6, stage: '줄기' }, 'Lv.6이 되면 줄기 단계로 자랄 거야!'],
    ['en', 'character.bubbleNext', { level: 6, stage: 'Stem' }, "Reach Lv.6 and I'll grow into the Stem stage!"],
    ['ko', 'activity.sleepDuration', { hours: 7, minutes: 0 }, '7시간 0분'],
  ] as const)('%s %s에 파라미터를 넣은 전체 문구', (lang, key, params, expected) => {
    expect(translate(lang, key, params)).toBe(expected)
  })
})
