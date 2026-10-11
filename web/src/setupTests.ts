import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// vitest globals를 켜지 않았으므로 Testing Library의 자동 cleanup이 동작하지 않는다. 직접 등록한다.
afterEach(() => {
  cleanup()
  localStorage.clear()
  sessionStorage.clear()
})
