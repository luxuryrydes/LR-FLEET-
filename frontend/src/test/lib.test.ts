import cases from "../../../spec/registration-cases.json";
import { defaultFilters, filtersFromParams, filtersToQuery, filtersToSearch, validateRange } from "../lib/filters";
import { date, label, number, rupees } from "../lib/format";
import { extractPlate, normalizeRegistration } from "../lib/registration";
import { can } from "../lib/auth";
import { qs } from "../lib/api";

describe("registration rules (shared spec with Java and Python)", () => {
  it.each(cases.normalize)("normalize %#", (c) => {
    expect(normalizeRegistration(c.input)).toBe(c.expected);
  });
  it.each(cases.extract)("extract $input", (c) => {
    expect(extractPlate(c.input)).toBe(c.expected);
  });
});

describe("filters", () => {
  it("drops empty values so the API receives only active filters", () => {
    const f = { ...defaultFilters(), from: "2026-10-01", to: "2026-10-19", fuelType: "CNG", vehicle: "  dl1rt1234 " };
    expect(filtersToQuery(f)).toEqual({ from: "2026-10-01", to: "2026-10-19", fuelType: "CNG", vehicle: "dl1rt1234" });
  });

  it("round-trips through the URL so KPI links open the same filtered records", () => {
    const f = { ...defaultFilters(), from: "2026-09-01", to: "2026-09-30", departmentId: "4", status: "ACTIVE" };
    const back = filtersFromParams(new URLSearchParams(filtersToSearch(f)));
    expect(back).toEqual(f);
  });

  it("adds extra parameters such as paging", () => {
    expect(filtersToQuery({ from: "2026-01-01" }, { page: 2, size: 50 })).toEqual({ from: "2026-01-01", page: 2, size: 50 });
    expect(qs({ a: "1", b: "", c: undefined, d: 0 })).toBe("?a=1&d=0");
  });

  it("validates the date range", () => {
    expect(validateRange({ from: "2026-10-02", to: "2026-10-01" })).toMatch(/on or before/);
    expect(validateRange({ from: "", to: "2026-10-01" })).toMatch(/both dates/);
    expect(validateRange({ from: "2026-10-01", to: "2026-10-01" })).toBeNull();
  });
});

describe("Indian formatting", () => {
  it("formats rupees with lakh grouping", () => {
    expect(rupees(1234567.891)).toBe("₹12,34,567.89");
    expect(rupees(null)).toBe("—");
  });
  it("formats dates as DD-MM-YYYY", () => {
    expect(date("2026-10-01")).toBe("01-10-2026");
    expect(date(null)).toBe("—");
  });
  it("formats numbers and labels", () => {
    expect(number(100000, 0)).toBe("1,00,000");
    expect(label("CNG_PETROL")).toBe("Cng Petrol");
  });
});

describe("permissions mirror server roles", () => {
  const u = (...roles: ("ADMIN" | "MANAGER" | "OPERATOR" | "VIEWER")[]) => ({ roles });
  it("viewer can only read", () => {
    expect(can(u("VIEWER"), "read")).toBe(true);
    expect(can(u("VIEWER"), "write")).toBe(false);
    expect(can(u("VIEWER"), "manage")).toBe(false);
  });
  it("operator writes but cannot manage", () => {
    expect(can(u("OPERATOR"), "write")).toBe(true);
    expect(can(u("OPERATOR"), "manage")).toBe(false);
  });
  it("manager manages but is not admin", () => {
    expect(can(u("MANAGER"), "manage")).toBe(true);
    expect(can(u("MANAGER"), "admin")).toBe(false);
    expect(can(u("ADMIN"), "admin")).toBe(true);
    expect(can(null, "read")).toBe(false);
  });
});
