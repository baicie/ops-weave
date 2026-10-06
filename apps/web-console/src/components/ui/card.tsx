import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

export function Card({ className, ...props }: ComponentProps<'section'>) {
  return <section data-slot="card" className={cn('admin-card', className)} {...props} />
}
export function CardHeader({ className, ...props }: ComponentProps<'header'>) {
  return <header data-slot="card-header" className={cn('admin-card-header', className)} {...props} />
}
export function CardContent({ className, ...props }: ComponentProps<'div'>) {
  return <div data-slot="card-content" className={cn('admin-card-content', className)} {...props} />
}
