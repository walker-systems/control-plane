import { useEffect, useRef, type ReactNode } from 'react'

// General-purpose modal on the native <dialog> element — same
// mechanism as ConfirmDialog (focus trap, Escape, inert background,
// top-layer rendering), but for arbitrary content (forms, etc.). Use
// ConfirmDialog for the narrow confirm/cancel case; this for anything
// with its own body and controls.

interface Props {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
}

export function Dialog({ open, title, onClose, children }: Props) {
  const ref = useRef<HTMLDialogElement>(null)

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
        // Escape fires 'cancel'; route it through onClose so parent
        // state stays in sync with the dialog's actual open state.
        e.preventDefault()
        onClose()
      }}
      className="rounded-lg border border-slate-200 bg-white p-0 shadow-lg backdrop:bg-slate-900/40"
    >
      <div className="w-[28rem] max-w-full space-y-4 p-5">
        <h2 className="text-base font-semibold text-slate-900">{title}</h2>
        {children}
      </div>
    </dialog>
  )
}
