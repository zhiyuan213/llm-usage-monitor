export function traceObjectLabel(object: {type?: string; object_id?: string; data?: {name?: string; model?: string; observationType?: string}}): string {
  const data = object.data ?? {};
  return (object.type === 'observation' && data.observationType === 'generation'
    ? data.model || data.name
    : data.name || data.model) || object.object_id || '—';
}
