import { useEffect, useRef } from 'react'
import { Button } from '@/components/ui/Button'

// Small modal built on the native <dialog> element. Focus trap,
// Escape-to-close, and inert background come for free. Rendered
// inline where called (no portal needed) — dialogs are position:
// fixed at the top layer per the spec.
//
// One conscious tradeoff: we don't animate open/close. The demo
// gets a clean binary transition; adding CSS transitions would
// require handling the browser's backdrop rendering explicitly.

interface Props {
  open: boolean
  title: string
  description?: string
  confirmLabel: string
  destructive?: boolean
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  destructive,
  busy,
  onConfirm,
  onCancel,
}: Props) {
  const ref = useRef<HTMLDialogElement>(null)

  // Sync the dialog's imperative open/close API with the React prop.
  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (open && !el.open) {
      el.showModal()
    } else if (!open && el.open) {
      el.close()
    }
  }, [open])

  return (
    <dialog
      ref={ref}
      onCancel={(e) => {
        // ESC key fires 'cancel'; we route it through the same handler
        // as the Cancel button so parent state stays consistent.
        e.preventDefault()
        onCancel()
      }}
      className="rounded-lg border border-slate-200 bg-white p-0 shadow-lg backdrop:bg-slate-900/40"
    >
      <div className="w-96 max-w-full space-y-3 p-5">
        <h2 className="text-base font-semibold text-slate-900">{title}</h2>
        {description && <p className="text-sm text-slate-600">{description}</p>}
        <div className="flex justify-end gap-2 pt-2">
          <Button variant="secondary" size="sm" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button
            size="sm"
            onClick={onConfirm}
            disabled={busy}
            className={destructive ? '!bg-red-600 hover:!bg-red-700' : ''}
          >
            {busy ? 'Working…' : confirmLabel}
          </Button>
        </div>
      </div>
    </dialog>
  )
}
