/** Indian display formats. Values come from the API as numbers/ISO dates; these functions only format them. */

const inr = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 2, maximumFractionDigits: 2 });
const num = (digits: number) =>
  new Intl.NumberFormat("en-IN", { minimumFractionDigits: 0, maximumFractionDigits: digits });

export function rupees(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === "") return "—";
  const n = typeof v === "string" ? Number(v) : v;
  return Number.isFinite(n) ? inr.format(n) : "—";
}

export function number(v: number | string | null | undefined, digits = 2): string {
  if (v === null || v === undefined || v === "") return "—";
  const n = typeof v === "string" ? Number(v) : v;
  return Number.isFinite(n) ? num(digits).format(n) : "—";
}

/** "2026-10-01" or ISO timestamp -> "01-10-2026". */
export function date(v: string | null | undefined): string {
  if (!v) return "—";
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(v);
  if (!m) return v;
  if (v.length > 10 && v.includes("T")) {
    const d = new Date(v);
    if (!Number.isNaN(d.getTime())) {
      const p = (x: number) => String(x).padStart(2, "0");
      return `${p(d.getDate())}-${p(d.getMonth() + 1)}-${d.getFullYear()}`;
    }
  }
  return `${m[3]}-${m[2]}-${m[1]}`;
}

export function dateTime(v: string | null | undefined): string {
  if (!v) return "—";
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return v;
  const p = (x: number) => String(x).padStart(2, "0");
  return `${p(d.getDate())}-${p(d.getMonth() + 1)}-${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** "2026-10" -> "Oct 2026" */
export function month(v: string): string {
  const m = /^(\d{4})-(\d{2})/.exec(v);
  if (!m) return v;
  const names = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  return `${names[Number(m[2]) - 1]} ${m[1]}`;
}

export const EFFICIENCY_UNIT: Record<string, string> = { PETROL: "km/L", DIESEL: "km/L", CNG: "km/kg", ELECTRICITY: "km/kWh" };
export const QUANTITY_UNIT: Record<string, string> = { PETROL: "L", DIESEL: "L", CNG: "kg", ELECTRICITY: "kWh" };

const ACRONYMS = new Set(["CNG", "SUV", "MUV", "LCV", "EV", "GPS", "PUC", "LR", "KM", "UPI", "DOT", "MHA", "ID"]);

export function label(code: string | null | undefined): string {
  if (!code) return "—";
  return code
    .split("_")
    .map((w) => (ACRONYMS.has(w.toUpperCase()) ? w.toUpperCase() : w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()))
    .join(" ");
}

export function todayIso(): string {
  const d = new Date();
  const p = (x: number) => String(x).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function daysAgoIso(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  const p = (x: number) => String(x).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
