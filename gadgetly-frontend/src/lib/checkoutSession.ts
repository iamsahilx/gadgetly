const key = (userId: number) => `gadgetly-pending-order-${userId}`

export function readPendingOrderId(userId: number | null): number | null {
  if (!userId || typeof localStorage === 'undefined') return null
  const saved = localStorage.getItem(key(userId))
  const legacy = saved ?? (typeof sessionStorage === 'undefined' ? null : sessionStorage.getItem(key(userId)))
  const id = Number(legacy)
  if (!saved && Number.isSafeInteger(id) && id > 0) localStorage.setItem(key(userId), String(id))
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

export function savePendingOrderId(userId: number | null, orderId: number): void {
  if (userId && typeof localStorage !== 'undefined') localStorage.setItem(key(userId), String(orderId))
}

export function clearPendingOrderId(userId: number | null): void {
  if (!userId) return
  if (typeof localStorage !== 'undefined') localStorage.removeItem(key(userId))
  if (typeof sessionStorage !== 'undefined') sessionStorage.removeItem(key(userId))
}
