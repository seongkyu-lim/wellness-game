// iOS 앱과 동일한 게스트 식별 체계: "guest:{uuid}" 를 로컬에 보존한다.
const STORAGE_KEY = 'wellness.userId'

export function loadUserId(): string {
  const existing = localStorage.getItem(STORAGE_KEY)
  if (existing) {
    return existing
  }
  const generated = `guest:${crypto.randomUUID()}`
  localStorage.setItem(STORAGE_KEY, generated)
  return generated
}

export function saveUserId(userId: string): void {
  localStorage.setItem(STORAGE_KEY, userId)
}
