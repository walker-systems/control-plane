import type { InputHTMLAttributes } from 'react'

export function Input({
  className = '',
  ...rest
}: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={
        'h-10 w-full rounded-md border border-slate-300 bg-white px-3 text-sm ' +
        'placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-950 ' +
        'disabled:opacity-50 ' +
        className
      }
      {...rest}
    />
  )
}
