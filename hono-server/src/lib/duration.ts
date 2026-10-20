/**
 * Spring Boot 의 Duration 바인딩(DurationStyle)과 같은 형식을 밀리초로 바꾼다.
 * - 단순형: "500ms", "30s", "15m", "1h", "7d" (단위 없는 숫자는 밀리초, 음수 허용)
 * - ISO-8601: "PT1H", "P7D", "PT0.5S"
 */
export function parseDurationMs(text: string): number {
  const value = text.trim();
  const simple = /^([+-]?\d+)(ns|us|ms|s|m|h|d)?$/i.exec(value);
  if (simple) {
    const n = Number(simple[1]);
    switch ((simple[2] ?? 'ms').toLowerCase()) {
      case 'ns':
        return n / 1_000_000;
      case 'us':
        return n / 1000;
      case 'ms':
        return n;
      case 's':
        return n * 1000;
      case 'm':
        return n * 60_000;
      case 'h':
        return n * 3_600_000;
      default:
        return n * 86_400_000;
    }
  }
  const iso = /^([+-])?P(?:([+-]?\d+)D)?(?:T(?:([+-]?\d+)H)?(?:([+-]?\d+)M)?(?:([+-]?\d+(?:[.,]\d{1,9})?)S)?)?$/i.exec(value);
  if (iso && value.toUpperCase() !== 'P' && !value.toUpperCase().endsWith('T')) {
    const sign = iso[1] === '-' ? -1 : 1;
    const days = Number(iso[2] ?? 0);
    const hours = Number(iso[3] ?? 0);
    const minutes = Number(iso[4] ?? 0);
    const seconds = Number((iso[5] ?? '0').replace(',', '.'));
    return sign * (days * 86_400_000 + hours * 3_600_000 + minutes * 60_000 + seconds * 1000);
  }
  throw new Error(`'${text}' 은(는) 올바른 기간 형식이 아닙니다(예: 30s, 1h, 7d, PT1H).`);
}
