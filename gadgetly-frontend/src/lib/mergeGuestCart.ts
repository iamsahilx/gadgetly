import { api } from './api'
import { queryClient } from './queryClient'
import { useGuestCartStore } from '../store/cartStore'

// Transfer one item at a time. Remove an item locally only after the API confirms
// it was added, so a partial outage never silently discards the guest cart.
export async function mergeGuestCart(): Promise<{ added: number; failed: number }> {
  const guest = useGuestCartStore.getState()
  let added = 0
  let failed = 0
  for (const item of [...guest.items]) {
    try {
      await api.post('/cart/items', {
        productId: item.productId,
        variantId: item.variantId,
        quantity: item.quantity,
      })
      useGuestCartStore.getState().removeItem(item.productId, item.variantId)
      added++
    } catch {
      failed++
    }
  }
  if (added) await queryClient.invalidateQueries({ queryKey: ['cart'] })
  return { added, failed }
}
