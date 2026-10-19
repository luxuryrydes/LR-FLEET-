/**
 * Registration rules mirrored from the backend (RegistrationNormalizer.java) for instant form feedback only.
 * Tested against spec/registration-cases.json; the server re-normalises on save.
 */
export function normalizeRegistration(raw: string | null | undefined): string {
  return (raw ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
}

const PLATE = /(?<!\d)(?:\d{2}BH\d{4}[A-Z]{1,2}|[A-Z]{2}\d{1,2}[A-Z]{0,3}\d{4}(?!\d))/;

export function extractPlate(raw: string | null | undefined): string | null {
  const n = normalizeRegistration(raw);
  if (!n) return null;
  const m = PLATE.exec(n);
  return m ? m[0] : null;
}
