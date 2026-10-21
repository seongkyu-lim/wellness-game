/**
 * Spring 서버(backend/)의 실제 구현으로 만든 고정 값.
 * jjwt 0.12.3 JwtTokenService 와 같은 방식(Jwts.builder().subject().issuer().audience().add().and()...signWith(HS256))으로
 * 만들었고, 비밀번호 해시는 Spring Security BCryptPasswordEncoder(6.3.0)가, SHA-256 은 JDK MessageDigest 가 만들었다.
 * 시크릿은 test/support/fixtures.ts 의 TEST_SECRET, 발급 시각(iat)은 T0(2026-10-20T03:00:00Z)이다.
 */
export const SPRING_FULL_TOKEN =
  'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJrYWthbzoxMjM0NSIsImlzcyI6IndlbGxuZXNzLWdhbWUiLCJhdWQiOlsid2VsbG5lc3MtZ2FtZS1hcGkiXSwiaWF0IjoxNzkyNDY1MjAwLCJleHAiOjE3OTI0Njg4MDB9.kUj_Y14PCMNqX6sDEWRoMxzqvwfn0IgbGv07EdyMYIQ';

/** scope=read, sub=password:abc-123, 유효 7일 */
export const SPRING_READ_TOKEN =
  'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJwYXNzd29yZDphYmMtMTIzIiwiaXNzIjoid2VsbG5lc3MtZ2FtZSIsImF1ZCI6WyJ3ZWxsbmVzcy1nYW1lLWFwaSJdLCJpYXQiOjE3OTI0NjUyMDAsImV4cCI6MTc5MzA3MDAwMCwic2NvcGUiOiJyZWFkIn0.BH6VvkS5HPK92SrVpO-5q8Y4a4eQqJPoxDnq_r7Nv0A';

/** #55 이전 형식(iss·aud 없음), iat 는 T0 - 30일 */
export const SPRING_LEGACY_TOKEN =
  'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJnb29nbGU6bGVnYWN5IiwiaWF0IjoxNzg5ODczMjAwLCJleHAiOjE3OTI0Njg4MDB9.nRtWkrRogK6uvRgTFUVpp6IqcABF1d-iOsy2MJ81c20';

export const SPRING_BCRYPT = {
  'password123': '$2a$10$Geor2FqTyQrRCMjeXaRf3ui8LYVJzs9RCc33ZDpD2pvDdH7zxb2z6',
  '비밀번호1234': '$2a$10$SmNDVwr.Q3BZqjI2IxPLRuugNys92dZMTy08YxxMEFTHTmgJ5FsqC',
  ['a'.repeat(72)]: '$2a$10$bQWfm.F1ch2fWacGzUvj0uZN5O89YQBboHGFp1Ms1FnTNVH4XkNDy',
} as const;

/** JDK MessageDigest SHA-256 → HexFormat 소문자 hex */
export const SPRING_SHA256 = {
  abc: 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
  '한글 토큰': 'b2859654546dd1fd7f615301b4fe8cb00377540feee2756f7e1abd1940a81e7b',
  Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWo: '682f43eecbd7c0f3bc40dbc0a051d70ec90e1b674dd46b151cc84eeb48ec40fa',
} as const;
