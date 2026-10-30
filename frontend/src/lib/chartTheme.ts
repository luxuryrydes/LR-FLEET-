/**
 * Shared chart styling (Recharts). Fuel colours follow the validated categorical order and always mean the same fuel:
 * CNG blue, petrol orange, diesel aqua, electricity yellow. Single-series charts use series 1. Grid and axes recede.
 */
export const FUEL_COLORS: Record<string, string> = { CNG: "#2a78d6", PETROL: "#eb6834", DIESEL: "#1baf7a", ELECTRICITY: "#eda100" };
export const FUEL_LABEL: Record<string, string> = { CNG: "CNG", PETROL: "Petrol", DIESEL: "Diesel", ELECTRICITY: "Electric" };
export const SERIES_1 = "#2a78d6";

export const axis = { fontSize: 12, tick: { fill: "#6b7a90" }, axisLine: false, tickLine: false } as const;
export const grid = { strokeDasharray: "0", stroke: "#eef1f5", vertical: false } as const;
export const bar = { radius: [4, 4, 0, 0] as [number, number, number, number], maxBarSize: 34 };
export const tooltip = {
  contentStyle: { borderRadius: 10, border: "1px solid #e3e8ef", boxShadow: "0 6px 20px rgba(16,32,64,0.12)", fontSize: 13 },
  labelStyle: { fontWeight: 600, color: "#0f1b2d" },
  cursor: { fill: "rgba(36, 92, 163, 0.06)" },
};
export const legend = { iconType: "circle" as const, iconSize: 9, wrapperStyle: { fontSize: 12, color: "#3b4a5e" } };
