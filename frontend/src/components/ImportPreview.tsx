import type { Row } from "../lib/api";
import { date, label } from "../lib/format";
import { Badge } from "./ui";

export interface StagingMessage {
  level: "error" | "warning" | "info";
  code: string;
  field: string | null;
  message: string;
}

/** Parsed-row preview with the reason for every rejected, duplicate or warning row. */
export function ImportPreview({ rows, importType }: { rows: Row[]; importType: "GPS_KM" | "FUEL" }) {
  if (rows.length === 0) return <div className="empty">No rows in this view.</div>;
  return (
    <div className="table-scroll">
      <table className="table" aria-label="Import preview">
        <thead>
          <tr>
            <th>Row</th>
            <th>Status</th>
            <th>Vehicle (file → matched)</th>
            <th>Date</th>
            {importType === "GPS_KM" ? <th style={{ textAlign: "right" }}>Km</th> : <>
              <th>Energy</th><th style={{ textAlign: "right" }}>Qty</th><th style={{ textAlign: "right" }}>Amount</th><th>Invoice</th>
            </>}
            <th>Reasons / notes</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const p = (r.parsed ?? {}) as Row;
            const raw = (r.raw ?? {}) as Row;
            const msgs = (r.messages ?? []) as StagingMessage[];
            return (
              <tr key={r.rowNumber}>
                <td className="num">{r.rowNumber}</td>
                <td><Badge value={r.status} /></td>
                <td>
                  <span className="mono">{p.vehicleRaw ?? Object.values(raw)[0] ?? "—"}</span>
                  {r.registrationNumber && <> → <strong className="mono">{r.registrationNumber}</strong></>}
                </td>
                <td>{r.recordDate ? date(r.recordDate) : "—"}</td>
                {importType === "GPS_KM" ? <td className="num" style={{ textAlign: "right" }}>{p.km ?? "—"}</td> : <>
                  <td>{p.energyType ? label(p.energyType) : "—"}</td>
                  <td className="num" style={{ textAlign: "right" }}>{p.quantity ?? "—"}</td>
                  <td className="num" style={{ textAlign: "right" }}>{p.invoiceAmount ?? p.grossAmount ?? (p.unitPrice ? `@ ${p.unitPrice}` : "—")}</td>
                  <td>{p.invoiceNumber ?? "—"}</td>
                </>}
                <td>
                  {msgs.length === 0 ? <span className="muted">—</span> : (
                    <ul className="msg-list">
                      {msgs.map((m, i) => <li key={i} className={`msg-${m.level}`}>{m.message}</li>)}
                    </ul>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
