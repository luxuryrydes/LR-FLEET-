import { useEffect, type ReactNode } from "react";
import { ApiError } from "../lib/api";
import { label } from "../lib/format";

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </div>
  );
}

export function Card({ title, children, actions, className = "" }: { title?: string; children: ReactNode; actions?: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <div className="card-head">
          {title && <h2>{title}</h2>}
          {actions && <div className="actions">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null;
  const e = error as ApiError;
  return (
    <div className="alert alert-error" role="alert">
      <strong>{e.message ?? "Something went wrong"}</strong>
      {e.fieldErrors && e.fieldErrors.length > 0 && (
        <ul>
          {e.fieldErrors.map((f) => (
            <li key={f.field}>
              {label(f.field.replace(/([A-Z])/g, "_$1"))}: {f.message}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function Notice({ kind = "info", children }: { kind?: "info" | "warning" | "success" | "error"; children: ReactNode }) {
  return <div className={`alert alert-${kind}`}>{children}</div>;
}

export function Spinner({ text = "Loading…" }: { text?: string }) {
  return (
    <div className="spinner" role="status">
      <span className="spin" aria-hidden="true" /> {text}
    </div>
  );
}

export function Empty({ children = "No records match these filters." }: { children?: ReactNode }) {
  return <div className="empty">{children}</div>;
}

const TONE: Record<string, string> = {
  ACTIVE: "green", OK: "green", VALID: "green", VERIFIED: "green", COMMITTED: "green", SUCCEEDED: "green", ELIGIBLE: "green",
  RESOLVED: "green", COMPLETE: "green", GOOD: "green", INSERTED: "green",
  DUE_SOON: "amber", WARNING: "amber", PENDING: "amber", IDLE: "amber", WORKSHOP: "amber", MEDIUM: "amber", ACKNOWLEDGED: "amber",
  IN_PROGRESS: "amber", PARSE_PENDING: "amber", HEADERS_PENDING: "amber", HEADERS_READY: "blue", PARSED: "blue", PARTIAL: "amber",
  DUPLICATE: "grey", INACTIVE: "grey", SOLD: "grey", SCRAPPED: "grey", LOW: "grey", CANCELLED: "grey", DISMISSED: "grey", EXCLUDED: "grey",
  OVERDUE: "red", REJECTED: "red", FAILED: "red", HIGH: "red", CRITICAL: "red", OPEN: "red", EXPIRED: "red",
  MISSING_SERVICE_RECORD: "purple", MISSING_BASELINE: "purple", UNRELIABLE_BASELINE: "purple", INSUFFICIENT_DATA: "purple",
  MIXED_FUEL: "purple", NO_KM: "purple", NO_FUEL: "purple",
};

export function Badge({ value, text }: { value: string | null | undefined; text?: string }) {
  if (!value) return <span className="muted">—</span>;
  return <span className={`badge badge-${TONE[value] ?? "blue"}`}>{text ?? label(value)}</span>;
}

export function Field({ label: l, error, hint, children, required }: { label: string; error?: string; hint?: string; children: ReactNode; required?: boolean }) {
  return (
    <label className={`field ${error ? "has-error" : ""}`}>
      <span className="field-label">
        {l}
        {required && <span className="req"> *</span>}
      </span>
      {children}
      {hint && !error && <span className="hint">{hint}</span>}
      {error && <span className="field-error">{error}</span>}
    </label>
  );
}

export function Select({ value, onChange, options, placeholder, ...rest }: {
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
  placeholder?: string;
} & Omit<React.SelectHTMLAttributes<HTMLSelectElement>, "onChange" | "value">) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} {...rest}>
      {placeholder !== undefined && <option value="">{placeholder}</option>}
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  );
}

export function codes(list: string[] | undefined) {
  return (list ?? []).map((c) => ({ value: c, label: label(c) }));
}

export function Modal({ title, onClose, children, footer }: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title} onMouseDown={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h2>{title}</h2>
          <button className="btn-icon" onClick={onClose} aria-label="Close">×</button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  );
}

export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: { key: T; label: string; count?: number }[]; value: T; onChange: (k: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <button key={t.key} role="tab" aria-selected={value === t.key} className={value === t.key ? "tab active" : "tab"} onClick={() => onChange(t.key)}>
          {t.label}
          {t.count !== undefined && <span className="tab-count">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}
