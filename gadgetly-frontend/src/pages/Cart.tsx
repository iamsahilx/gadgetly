import { Link } from 'react-router-dom'
import { useState } from 'react'
import { useCart } from '../lib/useCart'
import { formatINR } from '../lib/format'
import { Trash2, Tag } from 'lucide-react'
import { useAuthStore } from '../store/authStore'
import { useGuestCartStore } from '../store/cartStore'
import { mergeGuestCart } from '../lib/mergeGuestCart'

export default function Cart() {
  const { cart, isLoading, isError, refetch, updateQuantity, removeItem, applyCoupon } = useCart()
  const { accessToken } = useAuthStore()
  const guestItems = useGuestCartStore((s) => s.items)
  const removeGuestItem = useGuestCartStore((s) => s.removeItem)
  const [coupon, setCoupon] = useState('')
  const [couponMsg, setCouponMsg] = useState('')
  const [merging, setMerging] = useState(false)

  async function retryMerge() {
    setMerging(true)
    try {
      await mergeGuestCart()
      await refetch()
    } finally {
      setMerging(false)
    }
  }

  async function handleApplyCoupon() {
    try {
      await applyCoupon(coupon)
      setCouponMsg('Coupon applied!')
    } catch (e: any) {
      setCouponMsg(e?.response?.data?.message || e.message)
    }
  }

  if (isLoading) return <div className="mx-auto max-w-3xl px-4 py-24 text-center text-ink/50">Loading cart...</div>
  if (isError) return <div className="mx-auto max-w-3xl px-4 py-24 text-center">Could not load your cart. <button className="text-coral" onClick={() => refetch()}>Try again</button></div>

  if ((!cart || cart.items.length === 0) && (!accessToken || guestItems.length === 0)) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-24 text-center">
        <h1 className="font-display text-2xl font-bold mb-3">Your cart is empty</h1>
        <p className="text-ink/50 mb-6">Looks like you haven't added anything yet.</p>
        <Link to="/products" className="rounded-full bg-coral text-onaccent px-6 py-3 font-semibold">Start Shopping</Link>
      </div>
    )
  }

  const currentCart = cart ?? { items: [], subtotal: 0, discount: 0, total: 0 }

  return (
    <div className="mx-auto max-w-5xl px-4 sm:px-6 lg:px-8 py-10">
      <h1 className="font-display text-2xl font-bold mb-6">Your Cart ({currentCart.items.length})</h1>
      {accessToken && guestItems.length > 0 && (
        <div className="rounded-card border border-amber-500/40 bg-amber-500/10 p-4 mb-6 text-sm">
          <p className="font-semibold">Some items from before login have not been added yet.</p>
          <p className="text-ink/60 mt-1">They are still saved in this browser. Retry adding them or remove them below before checkout.</p>
          <ul className="mt-3 space-y-2">
            {guestItems.map((item) => (
              <li key={`${item.productId}-${item.variantId ?? 'base'}`} className="flex items-center justify-between gap-3">
                <span>{item.name} × {item.quantity}</span>
                <button type="button" className="text-red-400 shrink-0" onClick={() => removeGuestItem(item.productId, item.variantId)}>Remove</button>
              </li>
            ))}
          </ul>
          <button type="button" disabled={merging} onClick={retryMerge} className="mt-3 rounded-full bg-coral text-onaccent px-4 py-2 font-semibold disabled:opacity-50">
            {merging ? 'Adding items...' : 'Retry adding items'}
          </button>
        </div>
      )}
      <div className="grid md:grid-cols-[1fr_320px] gap-8">
        <div className="space-y-4">
          {currentCart.items.map((item) => (
            <div key={item.cartItemId} className="glass rounded-card p-4 border border-hairline/10 flex gap-4">
              <img src={item.imageUrl || 'https://placehold.co/200x200/14171C/F2A65A'} className="h-20 w-20 rounded-lg object-cover" />
              <div className="flex-1">
                <p className="font-medium text-sm">{item.productName}</p>
                {item.variantLabel && <p className="text-xs text-ink/40">{item.variantLabel}</p>}
                <p className="text-sm text-coral font-semibold mt-1">{formatINR(item.unitPrice)}</p>
                <div className="flex items-center gap-3 mt-2">
                  <div className="flex items-center border border-hairline/20 rounded-full text-sm">
                    <button onClick={() => updateQuantity(item.cartItemId, item.quantity - 1, item.productId, item.variantId)} className="px-2 py-1">-</button>
                    <span className="px-2">{item.quantity}</span>
                    <button onClick={() => updateQuantity(item.cartItemId, item.quantity + 1, item.productId, item.variantId)} className="px-2 py-1">+</button>
                  </div>
                  <button onClick={() => removeItem(item.cartItemId, item.productId, item.variantId)} className="text-ink/40 hover:text-red-400">
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              </div>
              <p className="font-semibold">{formatINR(item.lineTotal)}</p>
            </div>
          ))}
        </div>

        <div className="glass rounded-card p-5 border border-hairline/10 h-fit space-y-4">
          <h2 className="font-semibold">Order Summary</h2>
          {accessToken && (
            <div>
              <label className="text-xs text-ink/50 flex items-center gap-1 mb-1"><Tag className="h-3 w-3" /> Coupon code</label>
              <div className="flex gap-2">
                <input value={coupon} onChange={(e) => setCoupon(e.target.value)} placeholder="e.g. WELCOME500"
                  className="flex-1 bg-panel border border-hairline/15 rounded-lg px-3 py-2 text-sm" />
                <button onClick={handleApplyCoupon} className="rounded-lg bg-panel border border-hairline/15 px-3 text-sm">Apply</button>
              </div>
              {couponMsg && <p className="text-xs text-plum mt-1">{couponMsg}</p>}
            </div>
          )}
          <div className="text-sm space-y-2 border-t border-hairline/10 pt-4">
            <div className="flex justify-between"><span className="text-ink/50">Subtotal</span><span>{formatINR(currentCart.subtotal)}</span></div>
            {currentCart.discount > 0 && (
              <div className="flex justify-between text-plum"><span>Discount</span><span>-{formatINR(currentCart.discount)}</span></div>
            )}
            <div className="flex justify-between font-semibold text-base border-t border-hairline/10 pt-2">
              <span>Total</span><span>{formatINR(currentCart.total)}</span>
            </div>
          </div>
          {accessToken ? (
            guestItems.length > 0 ? (
              <p className="text-center text-sm text-ink/50">Resolve the saved items above to continue.</p>
            ) : (
              <Link to="/checkout" className="block text-center rounded-full bg-coral text-onaccent font-semibold py-3 hover:shadow-glow transition-shadow">
                Proceed to Checkout
              </Link>
            )
          ) : (
            <Link to="/login" className="block text-center rounded-full bg-coral text-onaccent font-semibold py-3">
              Log in to Checkout
            </Link>
          )}
        </div>
      </div>
    </div>
  )
}
