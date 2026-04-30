export function getClientTimezone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}
