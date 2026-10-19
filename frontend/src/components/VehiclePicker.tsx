import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { api, type Page, type Row } from "../lib/api";
import { normalizeRegistration } from "../lib/registration";

/** Type-ahead vehicle search (server-side; never loads the whole fleet into the browser). */
export function VehiclePicker({ value, onChange, id }: { value: Row | null; onChange: (v: Row | null) => void; id?: string }) {
  const [text, setText] = useState(value?.registrationNumber ?? "");
  const [open, setOpen] = useState(false);
  useEffect(() => setText(value?.registrationNumber ?? ""), [value]);
  const q = normalizeRegistration(text);
  const r = useQuery({
    queryKey: ["vehicle-search", q],
    queryFn: () => api.get<Page<Row>>("/api/vehicles", { q, size: 10 }),
    enabled: open && q.length >= 2,
  });
  return (
    <div style={{ position: "relative" }}>
      <input id={id} value={text} placeholder="Type registration…" autoComplete="off" aria-label="Vehicle"
             onChange={(e) => { setText(e.target.value); setOpen(true); if (value) onChange(null); }}
             onFocus={() => setOpen(true)} onBlur={() => setTimeout(() => setOpen(false), 150)} />
      {open && r.data && r.data.items.length > 0 && (
        <div className="card" style={{ position: "absolute", zIndex: 20, left: 0, right: 0, padding: 4, marginTop: 2, maxHeight: 240, overflowY: "auto" }}>
          {r.data.items.map((v) => (
            <button type="button" key={v.id} className="link-btn" style={{ display: "block", width: "100%", textAlign: "left", padding: "6px 8px" }}
                    onMouseDown={() => { onChange(v); setText(v.registrationNumber); setOpen(false); }}>
              <strong className="mono">{v.registrationNumber}</strong> <span className="muted">{v.make} {v.model} · {v.fuelType}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
