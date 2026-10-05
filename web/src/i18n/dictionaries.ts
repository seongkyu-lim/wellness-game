export type Language = 'ko' | 'en'

const ko = {
  'app.name': 'Wellness Game',
  'header.title': '오늘도 한 뼘 성장해요 🌱',
  'lang.toggle': '언어',
  'lang.ko': '한국어',
  'lang.en': 'English',

  'error.sessionExpired': '로그인이 만료되었습니다. 다시 로그인해 주세요.',
  'error.loginFailed': '로그인에 실패했습니다.',
  'error.network': '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인해 주세요.',
  'error.request': '요청에 실패했습니다. ({status})',
  'error.noToken': '서버가 인증 토큰을 반환하지 않았습니다.',
  'error.stateMismatch': '로그인 상태 검증에 실패했습니다. 다시 시도해 주세요.',
  'error.unsupportedProvider': '지원하지 않는 로그인 방식입니다.',

  'account.label': '계정',
  'account.title': '👤 계정',
  'account.signedIn': '{provider} 로그인',
  'account.signInRequired': '로그인 필요',
  'account.defaultName': '회원',
  'account.signedInHint':
    '{name}님, 이 계정의 캐릭터 기록을 조회하고 있어요. 기록은 iPhone 앱에서 같은 계정으로 로그인해 동기화하면 쌓입니다.',
  'account.logout': '로그아웃',
  'account.signInHint':
    '내 캐릭터와 활동 기록을 보려면 iPhone 앱과 같은 계정으로 로그인해 주세요. 로그인한 본인의 데이터만 볼 수 있어요.',
  'account.signInWith': '{provider}로 로그인',
  'account.disabledHint':
    '비활성화된 버튼은 provider 키가 설정되지 않은 것입니다. 설정 방법은 web/README.md를 참고하세요. (Apple 로그인은 Apple Developer 유료 계정과 HTTPS 도메인이 필요해 웹에서는 아직 지원하지 않아요.)',

  'activity.label': '활동 기록',
  'activity.title': '☀️ 활동 기록',
  'activity.dateLabel': '조회 날짜',
  'activity.refresh': '새로고침',
  'activity.emptyTitle': '아직 동기화된 기록이 없어요',
  'activity.emptyBody':
    'iPhone 앱에서 로그인하면 HealthKit 데이터가 자동으로 동기화되어 이곳에 표시돼요.',
  'activity.steps': '걸음 수',
  'activity.sleep': '수면',
  'activity.noRecord': '기록 없음',
  'activity.sleepDuration': '{hours}시간 {minutes}분',
  'activity.minutes': '{n}분',
  'activity.totalXp': '이 날짜에 얻은 XP',
  'workout.SWIMMING': '수영',
  'workout.RUNNING': '달리기',
  'workout.WALKING': '걷기',
  'workout.CYCLING': '자전거',
  'workout.STRENGTH_TRAINING': '근력 운동',
  'workout.OTHER': '운동',

  'character.label': '내 캐릭터',
  'character.emptyTitle': '새싹이가 기다리고 있어요',
  'character.emptyBody': 'iPhone 앱에서 건강 데이터를 동기화하면 XP를 얻고 씨앗이 자라나요.',
  'character.stageBadge': 'Lv.{level} · {stage} 단계',
  'character.nextStage': 'Lv.{level}이 되면 {stage} 단계로 자라나요',
  'character.maxStage': '마지막 단계까지 모두 자랐어요 🌸',
  'character.avatarLabel': '{stage} 단계 캐릭터',

  'stage.seed': '씨앗',
  'stage.sprout': '새싹',
  'stage.sapling': '줄기',
  'stage.young': '어린나무',
  'stage.tree': '나무',
  'stage.blossom': '개화',

  'stat.str': '근력',
  'stat.vit': '활력',
  'stat.discipline': '절제',
  'stat.recovery': '회복',
} as const

export type MessageKey = keyof typeof ko

const en: Record<MessageKey, string> = {
  'app.name': 'Wellness Game',
  'header.title': 'Grow a little today 🌱',
  'lang.toggle': 'Language',
  'lang.ko': '한국어',
  'lang.en': 'English',

  'error.sessionExpired': 'Your session has expired. Please sign in again.',
  'error.loginFailed': 'Sign-in failed.',
  'error.network': "Can't reach the server. Make sure the backend is running.",
  'error.request': 'Request failed ({status}).',
  'error.noToken': "The server didn't return an auth token.",
  'error.stateMismatch': "We couldn't verify your sign-in. Please try again.",
  'error.unsupportedProvider': "That sign-in method isn't supported.",

  'account.label': 'Account',
  'account.title': '👤 Account',
  'account.signedIn': 'Signed in with {provider}',
  'account.signInRequired': 'Sign-in required',
  'account.defaultName': 'there',
  'account.signedInHint':
    "Hi {name}, you're viewing this account's character. Records build up when you sign in with the same account in the iPhone app and sync.",
  'account.logout': 'Sign out',
  'account.signInHint':
    'Sign in with the same account as the iPhone app to see your character and activity. You can only see your own data.',
  'account.signInWith': 'Sign in with {provider}',
  'account.disabledHint':
    "Disabled buttons mean the provider key isn't configured. See web/README.md for setup. (Sign in with Apple needs a paid Apple Developer account and an HTTPS domain, so it isn't available on the web yet.)",

  'activity.label': 'Activity',
  'activity.title': '☀️ Activity',
  'activity.dateLabel': 'Date',
  'activity.refresh': 'Refresh',
  'activity.emptyTitle': 'Nothing synced yet',
  'activity.emptyBody':
    'Sign in on the iPhone app and your HealthKit data syncs automatically and shows up here.',
  'activity.steps': 'Steps',
  'activity.sleep': 'Sleep',
  'activity.noRecord': 'No data',
  'activity.sleepDuration': '{hours}h {minutes}m',
  'activity.minutes': '{n} min',
  'activity.totalXp': 'XP earned on this day',
  'workout.SWIMMING': 'Swimming',
  'workout.RUNNING': 'Running',
  'workout.WALKING': 'Walking',
  'workout.CYCLING': 'Cycling',
  'workout.STRENGTH_TRAINING': 'Strength training',
  'workout.OTHER': 'Workout',

  'character.label': 'My character',
  'character.emptyTitle': 'Sprouty is waiting for you',
  'character.emptyBody': 'Sync your health data from the iPhone app to earn XP and watch your seed grow.',
  'character.stageBadge': 'Lv.{level} · {stage} stage',
  'character.nextStage': 'Reach Lv.{level} to grow into {stage}',
  'character.maxStage': "Fully grown — you've reached the final stage 🌸",
  'character.avatarLabel': '{stage} stage character',

  'stage.seed': 'Seed',
  'stage.sprout': 'Sprout',
  'stage.sapling': 'Stem',
  'stage.young': 'Sapling',
  'stage.tree': 'Tree',
  'stage.blossom': 'Blossom',

  'stat.str': 'Strength',
  'stat.vit': 'Vitality',
  'stat.discipline': 'Discipline',
  'stat.recovery': 'Recovery',
}

export const DICTIONARIES: Record<Language, Record<MessageKey, string>> = { ko, en }
