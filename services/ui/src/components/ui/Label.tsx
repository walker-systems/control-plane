import type { LabelHTMLAttributes } from 'react'

export function Label({
  className = '',
  ...rest
}: LabelHTMLAttributes<HTMLLabelElement>) {
  return (
    <label
      className={'text-sm font-medium text-slate-700 ' + className}
      {...rest}
    />
  )
}
