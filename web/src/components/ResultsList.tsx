import type { ActivityResult } from '../api/types'

interface Props {
  results: ActivityResult[]
}

export function ResultsList({ results }: Props) {
  if (results.length === 0) {
    return null
  }

  return (
    <section className="card" aria-label="획득 내역">
      <h2 className="section-title">✨ 획득 내역</h2>
      {results.map((result, index) => (
        <div className="result-row" key={`${result.source}-${index}`}>
          <span className="message">
            {result.duplicate ? '✅' : '➕'} {result.message}
          </span>
          {result.duplicate ? (
            <span className="pill muted">반영됨</span>
          ) : (
            <span className="pill lime">+{result.gainedXp} XP</span>
          )}
        </div>
      ))}
    </section>
  )
}
