interface Props {
  message: string
}

export function ErrorBanner({ message }: Props) {
  return (
    <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
      {message}
    </div>
  )
}

export function EmptyState({ message }: Props) {
  return (
    <div className="rounded-lg border border-dashed border-gray-200 bg-white px-6 py-12 text-center">
      <p className="text-sm text-gray-400">{message}</p>
    </div>
  )
}
