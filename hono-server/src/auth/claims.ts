/** JWT aud 클레임(문자열 또는 배열)을 배열로. 없으면 빈 배열 */
export function audienceOf(payload: { aud?: unknown }): string[] {
  const aud = payload.aud;
  if (aud === undefined || aud === null) return [];
  return (Array.isArray(aud) ? aud : [aud]).map(String);
}
