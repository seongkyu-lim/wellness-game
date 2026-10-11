import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// vitest globals를 켜지 않았으므로 Testing Library의 자동 cleanup이 동작하지 않는다. 직접 등록한다.
afterEach(() => {
  cleanup()
  // node 환경 테스트(build.test.ts)에는 Web Storage가 없다.
  if (typeof localStorage !== 'undefined') {
    localStorage.clear()
    sessionStorage.clear()
  }
})
