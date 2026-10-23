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
export function ImportPreview({ rows, importType }: { rows: Row[]; importType: "GPS_KM" | "FUEL" | "VEHICLE_MASTER" | "DOCUMENTS" | "SERVICE" }) {
  if (rows.length === 0) return <div className="empty">No rows in this view.</div>;
  if (importType === "VEHICLE_MASTER") return <MasterPreview rows={rows} />;
  if (importType === "DOCUMENTS") return <DocumentsPreview rows={rows} />;
  if (importType === "SERVICE") return <ServicePreview rows={rows} />;
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

const DOC_LABEL: Record<string, string> = {
  INSURANCE: "Insurance", INSURANCE_OD: "Own damage", PUC: "Pollution", FITNESS: "Fitness", ROAD_TAX: "Road tax",
  PERMIT: "Permit 5 yr", PERMIT_1Y: "Permit 1 yr", REGISTRATION: "Registered",
};

function DocumentsPreview({ rows }: { rows: Row[] }) {
  return (
    <div className="table-scroll">
      <table className="table" aria-label="Import preview">
        <thead>
          <tr><th>Tab / row</th><th>Status</th><th>Vehicle</th><th>Commercial / private</th><th>Dates to save</th><th>Notes</th></tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const p = (r.parsed ?? {}) as Row;
            const raw = (r.raw ?? {}) as Row;
            const msgs = ((r.messages ?? []) as StagingMessage[]).filter((m) => m.code !== "DOCS_PREVIEW");
            return (
              <tr key={`${raw.Sheet}-${r.rowNumber}`}>
                <td>{raw.Sheet} <span className="muted">· {r.rowNumber}</span></td>
                <td><Badge value={r.status} /></td>
                <td className="mono">{p.registration ?? r.registrationNumber ?? "—"}</td>
                <td>{p.registrationClass ? label(p.registrationClass) : "—"}</td>
                <td>
                  {((p.documents ?? []) as Row[]).map((d) => (
                    <span key={d.docType} className="chip">{DOC_LABEL[d.docType] ?? d.docType}: {d.notApplicable ? d.note : date(d.expiryDate ?? d.issueDate)}</span>
                  ))}
                </td>
                <td>{msgs.length === 0 ? <span className="muted">—</span> : (
                  <ul className="msg-list">{msgs.map((m, i) => <li key={i} className={`msg-${m.level}`}>{m.message}</li>)}</ul>
                )}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function ServicePreview({ rows }: { rows: Row[] }) {
  const km = (v: unknown) => (v === null || v === undefined ? "—" : Number(v).toLocaleString("en-IN"));
  return (
    <div className="table-scroll">
      <table className="table" aria-label="Import preview">
        <thead>
          <tr><th>Row</th><th>Status</th><th>Vehicle</th><th>Service date</th><th style={{ textAlign: "right" }}>Odometer</th>
            <th style={{ textAlign: "right" }}>Next service km</th><th>Notes</th></tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const p = (r.parsed ?? {}) as Row;
            const raw = (r.raw ?? {}) as Row;
            const msgs = ((r.messages ?? []) as StagingMessage[]).filter((m) => m.code !== "SERVICE_PREVIEW");
            return (
              <tr key={r.rowNumber}>
                <td className="num">{r.rowNumber}</td>
                <td><Badge value={r.status} /></td>
                <td className="mono">{p.registration ?? r.registrationNumber ?? Object.values(raw)[1] ?? "—"}</td>
                <td>{p.serviceDate ? date(p.serviceDate) : r.parsed ? <span className="muted">not recorded</span> : "—"}</td>
                <td className="num" style={{ textAlign: "right" }}>{km(p.odometerKm)}</td>
                <td className="num" style={{ textAlign: "right" }}>{km(p.nextServiceOdometer)}</td>
                <td>{msgs.length === 0 ? <span className="muted">—</span> : (
                  <ul className="msg-list">{msgs.map((m, i) => <li key={i} className={`msg-${m.level}`}>{m.message}</li>)}</ul>
                )}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function MasterPreview({ rows }: { rows: Row[] }) {
  return (
    <div className="table-scroll">
      <table className="table" aria-label="Import preview">
        <thead>
          <tr>
            <th>Row</th><th>Status</th><th>Registration</th><th>Vehicle</th><th>Fuel</th><th>Driver / vendor</th><th>Department</th>
            <th>Will be</th><th>GPS km</th><th>Notes</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const p = (r.parsed ?? {}) as Row;
            const pv = (p.preview ?? {}) as Row;
            const msgs = ((r.messages ?? []) as StagingMessage[]).filter((m) => m.code !== "MASTER_PREVIEW");
            return (
              <tr key={r.rowNumber}>
                <td className="num">{r.rowNumber}</td>
                <td><Badge value={r.status} /></td>
                <td className="mono">{p.registration ?? "—"} {pv.existing ? <span className="muted small">(update)</span> : p.registration ? <span className="muted small">(new)</span> : null}</td>
                <td>{pv.make ? `${pv.make} ${pv.modelName}` : p.model ?? "—"}{p.modelYear ? ` (${p.modelYear})` : ""}</td>
                <td>{pv.fuel ? label(pv.fuel) : "—"}</td>
                <td>{p.driverName ?? "—"} <span className="muted small">{p.operatorLabel ?? ""}</span></td>
                <td>{p.department ?? "—"}</td>
                <td>{pv.status ? <><Badge value={pv.status} /> <span className="muted small">{label(pv.operatorType)}</span></> : "—"}</td>
                <td>{pv.status ? (pv.gpsKmTracked ? <Badge value="OK" text="imported" /> : <Badge value="DUPLICATE" text="not imported" />) : "—"}</td>
                <td>{msgs.length === 0 ? <span className="muted">—</span> : (
                  <ul className="msg-list">{msgs.map((m, i) => <li key={i} className={`msg-${m.level}`}>{m.message}</li>)}</ul>
                )}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
