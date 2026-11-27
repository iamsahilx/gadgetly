export default function RetryMessage({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div role="alert" className="rounded-card border border-hairline/20 bg-panel p-6 text-center text-sm text-ink/70">
      <p>{message}</p>
      <button type="button" onClick={onRetry} className="mt-3 rounded-full bg-coral px-5 py-2 font-semibold text-onaccent">
        Try again
      </button>
    </div>
  )
}
