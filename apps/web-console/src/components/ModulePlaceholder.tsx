export function ModulePlaceholder(props: {
  title: string
  capability: string
  next: string
}) {
  return (
    <section class="panel">
      <span class="badge">平台业务待实现</span>
      <h2>{props.title}</h2>
      <p>{props.capability}</p>
      <p>{props.next}</p>
    </section>
  )
}
