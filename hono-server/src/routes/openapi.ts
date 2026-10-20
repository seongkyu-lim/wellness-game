import { swaggerUI } from '@hono/swagger-ui';
import { createRoute, z, type OpenAPIHono } from '@hono/zod-openapi';
import { ACTIVITY_TYPES, WORKOUT_TYPES } from '../activity/types.js';
import type { AppEnv } from '../http/env.js';

/**
 * API 문서(#93): /v3/api-docs(JSON), /swagger-ui/index.html. APP_API_DOCS_ENABLED=true 일 때만 등록한다(기본 꺼짐).
 * 요청 처리·검증은 Spring 과 같은 순서·문구를 지키려고 routes/*.ts 에서 직접 하고, 여기서는 계약만 문서로 등록한다.
 */

const ErrorResponse = z
  .object({
    timestamp: z.string().openapi({ example: '2026-10-20T01:02:03.456Z' }),
    status: z.number().int().openapi({ example: 400 }),
    error: z.string().openapi({ example: 'Bad Request' }),
    message: z.string().openapi({ example: 'username: 비어 있을 수 없습니다.' }),
  })
  .openapi('ErrorResponse');

const TokenFields = {
  accessToken: z.string(),
  tokenType: z.string().openapi({ example: 'Bearer' }),
  expiresIn: z.number().int().openapi({ description: '액세스 토큰 유효 기간(초)' }),
  refreshToken: z.string().openapi({ description: '회전 리프레시 토큰(43자). 갱신마다 새 값' }),
  refreshExpiresIn: z.number().int().openapi({ description: '리프레시 토큰 유효 기간(초)' }),
};

const SocialLoginResponse = z
  .object({ userId: z.string().openapi({ example: 'google:1234567890' }), displayName: z.string().nullable(), ...TokenFields })
  .openapi('SocialLoginResponse');

const PasswordAuthResponse = z
  .object({
    userIdentifier: z.string(),
    userId: z.string().openapi({ example: 'password:7f1c…' }),
    displayName: z.string().nullable(),
    ...TokenFields,
  })
  .openapi('PasswordAuthResponse');

const RefreshResponse = z.object({ userId: z.string(), ...TokenFields }).openapi('RefreshResponse');

const IssuedToken = z
  .object({ accessToken: z.string(), tokenType: z.string(), expiresIn: z.number().int() })
  .openapi('IssuedToken');

const Stats = z
  .object({ str: z.number().int(), vit: z.number().int(), intStat: z.number().int(), discipline: z.number().int(), recovery: z.number().int() })
  .openapi('StatsResponse');

const Character = z
  .object({ level: z.number().int(), currentXp: z.number().int(), totalXp: z.number().int(), nextLevelXp: z.number().int(), stats: Stats })
  .openapi('CharacterResponse');

const CharacterSummary = z.object({ userId: z.string(), character: Character }).openapi('CharacterSummaryResponse');

const ActivityType = z.enum(ACTIVITY_TYPES);
const WorkoutType = z.enum(WORKOUT_TYPES);

const ActivityPayload = z
  .object({
    type: ActivityType,
    workoutType: WorkoutType.nullish(),
    durationMinutes: z.number().int().min(0).nullish().openapi({ description: 'WORKOUT: 비우면 시작~종료 구간(분). 구간보다 길 수 없다' }),
    calories: z.number().min(0).nullish(),
    distanceMeters: z.number().min(0).nullish(),
    steps: z.number().int().min(0).nullish(),
    sleepMinutes: z.number().int().min(0).nullish(),
    sleepScore: z.number().int().min(0).nullish(),
    startedAt: z.string().nullish().openapi({ format: 'date-time', example: '2026-10-20T00:00:00Z' }),
    endedAt: z.string().nullish().openapi({ format: 'date-time', example: '2026-10-20T00:30:00Z' }),
  })
  .openapi('ActivityPayload');

const SyncRequest = z
  .object({
    userId: z.string().nullish().openapi({ description: '선택. 있으면 토큰 주체와 같아야 한다(다르면 403)' }),
    date: z.string().openapi({ format: 'date', example: '2026-10-20' }),
    activities: z.array(ActivityPayload).min(1).max(50),
  })
  .openapi('HealthActivitySyncRequest');

const SyncResponse = z
  .object({
    userId: z.string(),
    date: z.string().openapi({ format: 'date' }),
    gainedXp: z.number().int(),
    levelUp: z.boolean(),
    character: Character,
    activityResults: z.array(
      z.object({ type: ActivityType, source: z.string(), gainedXp: z.number().int(), message: z.string(), duplicate: z.boolean() }),
    ),
    goals: z.array(
      z.object({ type: ActivityType, target: z.number().int(), current: z.number().int(), unit: z.string(), achieved: z.boolean() }),
    ),
  })
  .openapi('HealthActivitySyncResponse');

const DailyActivities = z
  .object({
    userId: z.string(),
    date: z.string().openapi({ format: 'date' }),
    activities: z.array(
      z.object({
        type: ActivityType,
        source: z.string().nullable(),
        durationMinutes: z.number().int().nullable(),
        calories: z.number().nullable(),
        distanceMeters: z.number().nullable(),
        steps: z.number().int().nullable(),
        sleepMinutes: z.number().int().nullable(),
        sleepScore: z.number().int().nullable(),
        gainedXp: z.number().int(),
        startedAt: z.string().nullable().openapi({ format: 'date-time' }),
        endedAt: z.string().nullable().openapi({ format: 'date-time' }),
      }),
    ),
  })
  .openapi('DailyActivitiesResponse');

const json = <T extends z.ZodType>(schema: T) => ({ content: { 'application/json': { schema } } });
const errors = {
  400: { description: '요청 값 오류', ...json(ErrorResponse) },
  401: { description: '인증 실패', ...json(ErrorResponse) },
  429: { description: '요청 빈도 초과(Retry-After)', ...json(ErrorResponse) },
};
const bearer = [{ bearerAuth: [] }];
const tokenBody = (field: string, max: number) => ({
  body: { required: true, ...json(z.object({ [field]: z.string().min(1).max(max) })) },
});

const routes = [
  createRoute({
    method: 'post',
    path: '/api/auth/signup',
    tags: ['auth'],
    summary: '아이디·비밀번호 가입',
    request: {
      body: {
        required: true,
        ...json(
          z.object({
            username: z.string().regex(/^[A-Za-z0-9_]{4,32}$/),
            password: z.string().min(8).max(72),
            displayName: z.string().max(64).nullish(),
          }),
        ),
      },
    },
    responses: { 200: { description: '가입·로그인 완료', ...json(PasswordAuthResponse) }, ...errors },
  }),
  createRoute({
    method: 'post',
    path: '/api/auth/login',
    tags: ['auth'],
    summary: '아이디·비밀번호 로그인',
    request: { body: { required: true, ...json(z.object({ username: z.string().min(1), password: z.string().min(1) })) } },
    responses: { 200: { description: '로그인 완료', ...json(PasswordAuthResponse) }, ...errors },
  }),
  createRoute({
    method: 'post',
    path: '/api/auth/google/native',
    tags: ['auth'],
    summary: 'iOS 구글 ID 토큰 교환',
    request: tokenBody('idToken', 4096),
    responses: { 200: { description: '로그인 완료', ...json(SocialLoginResponse) }, ...errors },
  }),
  createRoute({
    method: 'post',
    path: '/api/auth/kakao/native',
    tags: ['auth'],
    summary: 'iOS 카카오 액세스 토큰 교환',
    request: tokenBody('accessToken', 4096),
    responses: { 200: { description: '로그인 완료', ...json(SocialLoginResponse) }, ...errors },
  }),
  createRoute({
    method: 'post',
    path: '/api/auth/naver/native',
    tags: ['auth'],
    summary: 'iOS 네이버 토큰 교환(refreshToken 으로 앱 소유 확인)',
    request: {
      body: { required: true, ...json(z.object({ accessToken: z.string().min(1).max(4096), refreshToken: z.string().min(1).max(4096) })) },
    },
    responses: { 200: { description: '로그인 완료', ...json(SocialLoginResponse) }, ...errors },
  }),
  createRoute({
    method: 'post',
    path: '/api/auth/apple/native',
    tags: ['auth'],
    summary: 'iOS Sign in with Apple identity token 교환',
    request: {
      body: {
        required: true,
        ...json(
          z.object({
            identityToken: z.string().min(1).max(4096),
            fullName: z.string().nullish(),
            nonce: z.string().max(256).nullish().openapi({ description: 'SHA-256 전 nonce 원문' }),
          }),
        ),
      },
    },
    responses: { 200: { description: '로그인 완료', ...json(SocialLoginResponse) }, ...errors },
  }),
  createRoute({
    method: 'post',
    path: '/api/auth/refresh',
    tags: ['auth'],
    summary: '리프레시 토큰 회전(재사용 감지 시 family 폐기)',
    request: tokenBody('refreshToken', 256),
    responses: { 200: { description: '새 토큰 쌍', ...json(RefreshResponse) }, ...errors },
  }),
  createRoute({
    method: 'post',
    path: '/api/auth/logout',
    tags: ['auth'],
    summary: '이 기기 로그아웃(리프레시 family 폐기 + 조회 전용 토큰 무효화)',
    request: tokenBody('refreshToken', 256),
    responses: { 204: { description: '완료(토큰 유효 여부와 무관)' }, ...errors },
  }),
  createRoute({
    method: 'post',
    path: '/api/auth/logout-all',
    tags: ['auth'],
    summary: '모든 기기 로그아웃',
    security: bearer,
    responses: { 204: { description: '완료' }, ...errors },
  }),
  createRoute({
    method: 'post',
    path: '/api/auth/watch-token',
    tags: ['auth'],
    summary: 'Watch 전용 조회 토큰(scope=read) 발급',
    security: bearer,
    responses: { 200: { description: '조회 전용 토큰', ...json(IssuedToken) }, ...errors },
  }),
  createRoute({
    method: 'post',
    path: '/api/auth/{provider}',
    tags: ['auth'],
    summary: '웹 authorization code 교환(kakao·naver·google). 조회 전용(read) 토큰을 준다',
    request: {
      params: z.object({ provider: z.enum(['kakao', 'naver', 'google']) }),
      body: {
        required: true,
        ...json(
          z.object({
            code: z.string().min(1),
            redirectUri: z.string().min(1),
            codeVerifier: z
              .string()
              .min(43)
              .max(128)
              .regex(/^[A-Za-z0-9\-._~]+$/)
              .nullish()
              .openapi({ description: 'PKCE code_verifier(구글·카카오에 전달)' }),
          }),
        ),
      },
    },
    responses: { 200: { description: '로그인 완료', ...json(SocialLoginResponse) }, ...errors },
  }),
  createRoute({
    method: 'get',
    path: '/api/characters/me',
    tags: ['characters'],
    summary: '토큰 주체의 캐릭터',
    security: bearer,
    responses: { 200: { description: '캐릭터', ...json(CharacterSummary) }, 404: { description: '캐릭터 없음' }, ...errors },
  }),
  createRoute({
    method: 'get',
    path: '/api/characters/{userId}',
    tags: ['characters'],
    summary: '캐릭터(userId 는 토큰 주체와 같아야 함)',
    security: bearer,
    request: { params: z.object({ userId: z.string() }) },
    responses: {
      200: { description: '캐릭터', ...json(CharacterSummary) },
      403: { description: '다른 사용자', ...json(ErrorResponse) },
      404: { description: '캐릭터 없음' },
      ...errors,
    },
  }),
  createRoute({
    method: 'post',
    path: '/api/health-activities/sync',
    tags: ['health-activities'],
    summary: '건강 활동 동기화(XP·레벨·능력치 반영)',
    security: bearer,
    request: { body: { required: true, ...json(SyncRequest) } },
    responses: { 200: { description: '동기화 결과', ...json(SyncResponse) }, 403: { description: '다른 사용자', ...json(ErrorResponse) }, ...errors },
  }),
  createRoute({
    method: 'get',
    path: '/api/health-activities',
    tags: ['health-activities'],
    summary: '날짜별 활동 기록',
    security: bearer,
    request: { query: z.object({ userId: z.string().optional(), date: z.string().openapi({ format: 'date', example: '2026-10-20' }) }) },
    responses: { 200: { description: '활동 기록', ...json(DailyActivities) }, 403: { description: '다른 사용자', ...json(ErrorResponse) }, ...errors },
  }),
  createRoute({
    method: 'get',
    path: '/healthz',
    tags: ['health'],
    summary: '헬스체크(DB 미확인)',
    responses: { 200: { description: 'UP', ...json(z.object({ status: z.literal('UP') })) } },
  }),
];

export function registerApiDocs(app: OpenAPIHono<AppEnv>) {
  app.openAPIRegistry.registerComponent('securitySchemes', 'bearerAuth', { type: 'http', scheme: 'bearer', bearerFormat: 'JWT' });
  for (const route of routes) app.openAPIRegistry.registerPath(route);
  // 상대 주소: 프록시가 TLS 를 종료해도 문서를 연 출처로 호출한다
  app.doc('/v3/api-docs', { openapi: '3.0.3', info: { title: 'Wellness Game API', version: 'v1' }, servers: [{ url: '/' }] });
  // persistAuthorization 은 끈다(#104: 브라우저 localStorage 에 토큰을 남기지 않음)
  app.get('/swagger-ui/index.html', swaggerUI({ url: '/v3/api-docs', persistAuthorization: false }));
  app.get('/swagger-ui.html', (c) => c.redirect('/swagger-ui/index.html'));
  app.get('/swagger-ui', (c) => c.redirect('/swagger-ui/index.html'));
}
