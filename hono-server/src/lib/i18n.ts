import { EN, KO } from './messages-data.js';

export type Locale = 'ko' | 'en';

/**
 * Spring AcceptHeaderLocaleResolver(지원 ko·en, 기본 ko) + Messages.currentLocale 와 같은 규칙.
 * q 값 순서대로 보며 정확히 "ko"/"en" 인 첫 항목을 고르고, 없으면 언어가 맞는 첫 항목, 그것도 없으면 ko.
 * (예: "en-US, ko" → ko, "en-US" → en, "fr" → ko)
 */
export function resolveLocale(acceptLanguage: string | null | undefined): Locale {
  if (!acceptLanguage || !acceptLanguage.trim()) return 'ko';
  const ranges = acceptLanguage
    .split(',')
    .map((part, index) => {
      const [tag, ...params] = part.trim().split(';');
      const q = params.map((p) => p.trim()).find((p) => p.startsWith('q='));
      return { tag: (tag ?? '').trim().toLowerCase(), q: q ? Number(q.slice(2)) : 1, index };
    })
    .filter((r) => r.tag && r.tag !== '*' && !(r.q <= 0) && !Number.isNaN(r.q))
    .sort((a, b) => b.q - a.q || a.index - b.index);
  let languageMatch: Locale | null = null;
  for (const { tag } of ranges) {
    if (tag === 'ko' || tag === 'en') return tag;
    const language = tag.split('-')[0];
    if (languageMatch === null && (language === 'ko' || language === 'en')) languageMatch = language;
  }
  return languageMatch ?? 'ko';
}

export type MessageArg = string | number | null | undefined;

/**
 * Messages.get(code, args...). 인자가 없으면 문구를 그대로, 있으면 java.text.MessageFormat 부분 집합으로 서식화한다.
 * 지원: {n}, {n,number,#}, {n,number,integer}, {n,choice,...}
 */
export function message(locale: Locale, code: string, ...args: MessageArg[]): string {
  const table = locale === 'en' ? EN : KO;
  const pattern = table[code] ?? KO[code];
  if (pattern === undefined) return code;
  if (args.length === 0) return pattern;
  return formatMessage(pattern, args, locale);
}

export function formatMessage(pattern: string, args: MessageArg[], locale: Locale): string {
  let out = '';
  let i = 0;
  while (i < pattern.length) {
    const ch = pattern[i]!;
    if (ch === "'") {
      // MessageFormat 인용: '' → ', '...' → 그대로
      if (pattern[i + 1] === "'") {
        out += "'";
        i += 2;
        continue;
      }
      const end = pattern.indexOf("'", i + 1);
      out += end < 0 ? pattern.slice(i + 1) : pattern.slice(i + 1, end);
      i = end < 0 ? pattern.length : end + 1;
      continue;
    }
    if (ch === '{') {
      const end = findClosingBrace(pattern, i);
      out += formatArgument(pattern.slice(i + 1, end), args, locale);
      i = end + 1;
      continue;
    }
    out += ch;
    i += 1;
  }
  return out;
}

function findClosingBrace(pattern: string, start: number): number {
  let depth = 0;
  for (let i = start; i < pattern.length; i++) {
    if (pattern[i] === '{') depth++;
    else if (pattern[i] === '}') {
      depth--;
      if (depth === 0) return i;
    }
  }
  throw new Error(`닫는 괄호가 없는 메시지 패턴: ${pattern}`);
}

function formatArgument(spec: string, args: MessageArg[], locale: Locale): string {
  const firstComma = spec.indexOf(',');
  const index = Number((firstComma < 0 ? spec : spec.slice(0, firstComma)).trim());
  const value = args[index];
  if (firstComma < 0) {
    if (value === undefined) return `{${spec}}`;
    return typeof value === 'number' ? groupedNumber(value) : String(value);
  }
  const rest = spec.slice(firstComma + 1);
  const secondComma = rest.indexOf(',');
  const type = (secondComma < 0 ? rest : rest.slice(0, secondComma)).trim();
  const style = secondComma < 0 ? '' : rest.slice(secondComma + 1);
  const num = typeof value === 'number' ? value : Number(value);
  if (type === 'number') {
    if (style.trim() === '#') return String(Math.round(num));
    if (style.trim() === 'integer') return groupedNumber(Math.round(num));
    return groupedNumber(num);
  }
  if (type === 'choice') {
    const chosen = choose(style, num);
    return chosen.includes('{') ? formatMessage(chosen, args, locale) : chosen;
  }
  return String(value);
}

/** java.text.ChoiceFormat: "1#a|1<b" — 조건을 만족하는 마지막 항목, 없으면 첫 항목 */
function choose(style: string, value: number): string {
  const options = style.split('|').map((part) => {
    const m = /^\s*(-?[\d.]+|∞|-∞)\s*([#<≤])(.*)$/s.exec(part);
    if (!m) throw new Error(`ChoiceFormat 해석 실패: ${style}`);
    const limit = m[1] === '∞' ? Infinity : m[1] === '-∞' ? -Infinity : Number(m[1]);
    return { limit, exclusive: m[2] === '<', text: m[3] ?? '' };
  });
  let chosen = options[0]!.text;
  for (const option of options) {
    const ok = option.exclusive ? value > option.limit : value >= option.limit;
    if (ok) chosen = option.text;
  }
  return chosen;
}

/** NumberFormat.getInstance(ko|en): 세 자리 쉼표, 소수 최대 3자리 */
function groupedNumber(value: number): string {
  return value.toLocaleString('en-US', { maximumFractionDigits: 3 });
}
