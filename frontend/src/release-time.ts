/** datetime-local controls carry no zone; release inputs are explicitly UTC. */
export function releaseUtcInput(now = new Date()): string {
  return now.toISOString().slice(0, 16);
}
export function releaseUtcInstant(value: string): string {
  return new Date(value + ':00Z').toISOString();
}
