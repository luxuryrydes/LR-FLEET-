import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { ReactNode } from "react";
import { FilterBar } from "../components/FilterBar";
import { ImportPreview } from "../components/ImportPreview";
import { defaultFilters } from "../lib/filters";
import { VehicleForm, validateVehicle, type VehicleDraft } from "../pages/VehicleForm";
import { MappingStep } from "../pages/ImportWizard";
import { DisposeModal } from "../pages/VehicleDetail";
import * as authModule from "../lib/auth";
import { Can } from "../lib/auth";

const lookups = {
  vehicleCategories: [{ code: "SEDAN", name: "Sedan" }], serviceCategories: [], departments: [{ id: 4, name: "Airport" }],
  vendors: [], drivers: [], fuelTypes: ["PETROL", "CNG"], energyTypes: [], ownershipTypes: ["OWNED"], vehicleStatuses: ["ACTIVE"], documentTypes: [],
};

function wrap(ui: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  qc.setQueryData(["lookups"], lookups);
  return render(<QueryClientProvider client={qc}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>);
}

describe("FilterBar", () => {
  it("applies the chosen filters", async () => {
    const onApply = vi.fn();
    wrap(<FilterBar value={{ ...defaultFilters(), from: "2026-10-01", to: "2026-10-19" }} onApply={onApply} />);
    await userEvent.selectOptions(screen.getByLabelText("Fuel type"), "CNG");
    await userEvent.selectOptions(screen.getByLabelText("Department"), "4");
    await userEvent.type(screen.getByLabelText("Vehicle number"), "DL1RT");
    await userEvent.click(screen.getByRole("button", { name: "Apply" }));
    expect(onApply).toHaveBeenCalledWith(expect.objectContaining({ fuelType: "CNG", departmentId: "4", vehicle: "DL1RT", from: "2026-10-01" }));
  });

  it("refuses an inverted date range with a visible message", async () => {
    const onApply = vi.fn();
    wrap(<FilterBar value={{ ...defaultFilters(), from: "2026-10-19", to: "2026-10-01" }} onApply={onApply} />);
    await userEvent.click(screen.getByRole("button", { name: "Apply" }));
    expect(onApply).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("'From' date must be on or before 'To' date");
  });
});

describe("ImportPreview", () => {
  const rows = [
    { rowNumber: 2, status: "VALID", registrationNumber: "DL1RT1234", recordDate: "2026-10-01", parsed: { vehicleRaw: "DL1RT1234 (CNG)", km: "120.5" }, raw: {}, messages: [] },
    { rowNumber: 3, status: "DUPLICATE", registrationNumber: "DL1RT1234", recordDate: "2026-10-01", parsed: { vehicleRaw: "DL1RT1234 (CNG)", km: "120.5" }, raw: {},
      messages: [{ level: "info", code: "DUPLICATE_DAILY_TOTAL", field: null, message: "Repeated copy of the daily total in row 2; it will not be summed" }] },
    { rowNumber: 4, status: "REJECTED", registrationNumber: null, recordDate: null, parsed: null, raw: { "Device Name": "XX99" },
      messages: [{ level: "error", code: "UNMATCHED_VEHICLE", field: "vehicle", message: "Vehicle 'XX99' is not in the vehicle register" }] },
  ];

  it("shows every row's status and the rejection reason", () => {
    render(<ImportPreview rows={rows} importType="GPS_KM" />);
    const table = screen.getByRole("table", { name: "Import preview" });
    const body = within(table).getAllByRole("row").slice(1);
    expect(body).toHaveLength(3);
    expect(within(body[0]).getByText("Valid")).toBeInTheDocument();
    expect(within(body[0]).getByText("120.5")).toBeInTheDocument();
    expect(within(body[1]).getByText(/will not be summed/)).toBeInTheDocument();
    expect(within(body[2]).getByText("Rejected")).toBeInTheDocument();
    expect(within(body[2]).getByText(/not in the vehicle register/)).toHaveClass("msg-error");
  });

  it("renders an empty state", () => {
    render(<ImportPreview rows={[]} importType="FUEL" />);
    expect(screen.getByText("No rows in this view.")).toBeInTheDocument();
  });
});

describe("Import mapping step", () => {
  const batch = {
    id: 9, importType: "GPS_KM",
    sheetInfo: {
      targetFields: { GPS_KM: ["vehicle", "date", "km", "tripStart"] },
      sheets: [{ name: "Distance", headerRow: 4, headers: ["Device Name", "Start Time", "Distance (Kms)"],
        sample: [{ "Device Name": "DL1RT1234 (CNG)", "Start Time": "01-10-2026 08:00:00", "Distance (Kms)": 120.5 }],
        suggestedMapping: { GPS_KM: { vehicle: "Device Name", date: "Start Time" } } }],
    },
  };
  it("pre-fills suggested columns and blocks until required fields are mapped", async () => {
    wrap(<MappingStep b={batch} onDone={() => {}} />);
    expect(screen.getByLabelText("Column for Vehicle number / GPS device name")).toHaveValue("Device Name");
    const validate = screen.getByRole("button", { name: "Validate rows" });
    expect(validate).toBeDisabled();
    expect(screen.getAllByText("Required")).toHaveLength(1);
    await userEvent.selectOptions(screen.getByLabelText("Column for Distance (km)"), "Distance (Kms)");
    expect(validate).toBeEnabled();
    expect(screen.getByText("DL1RT1234 (CNG)")).toBeInTheDocument();
  });
});

describe("Vehicle form validation messages", () => {
  const empty: VehicleDraft = {
    registrationNumber: "", duplicateRegistrationReason: "", make: "", model: "", modelYear: "", fuelType: "", categoryCode: "", ownershipType: "OWNED",
    vendorId: "", currentDriverId: "", currentDepartmentId: "", customerName: "", status: "ACTIVE", expectedMileage: "", serviceIntervalKm: "",
    serviceIntervalMonths: "", tankCapacity: "", batteryCapacityKwh: "", purchaseDate: "", purchaseCost: "", chassisNumber: "", engineNumber: "",
    gpsDeviceName: "", remarks: "", openingOdometerKm: "", openingOdometerDate: "", changeReason: "",
    operatorType: "OWN_DRIVER", gpsKmTracked: true, registrationClass: "",
  };

  it("validates required and numeric fields", () => {
    const e = validateVehicle({ ...empty, registrationNumber: "a-1", expectedMileage: "-3", modelYear: "1890", serviceIntervalKm: "10.5" });
    expect(e.registrationNumber).toMatch(/at least 4/);
    expect(e.make).toBe("Make is required");
    expect(e.fuelType).toBe("Choose a fuel type");
    expect(e.expectedMileage).toMatch(/greater than zero/);
    expect(e.modelYear).toMatch(/1980/);
    expect(e.serviceIntervalKm).toMatch(/Whole kilometres/);
    expect(validateVehicle({ ...empty, registrationNumber: "DL 1C AB 1234", make: "Maruti", model: "Dzire", fuelType: "CNG", categoryCode: "SEDAN" })).toEqual({});
  });

  it("shows messages on submit and does not call the API when invalid", async () => {
    const onSubmit = vi.fn();
    wrap(<VehicleForm initial={empty} editing={false} onSubmit={onSubmit} />);
    await userEvent.click(screen.getByRole("button", { name: "Create vehicle" }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText("Registration number is required")).toBeInTheDocument();
    expect(screen.getByText("Make is required")).toBeInTheDocument();
  });

  it("previews the normalised registration and submits a clean payload", async () => {
    const onSubmit = vi.fn();
    wrap(<VehicleForm initial={{ ...empty, make: "Maruti", model: "Dzire", fuelType: "CNG", categoryCode: "SEDAN" }} editing={false} onSubmit={onSubmit} />);
    await userEvent.type(screen.getByPlaceholderText("DL 1C AB 1234"), "dl 1c-ab 1234");
    expect(screen.getByText("Will be saved as DL1CAB1234")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Create vehicle" }));
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ registrationNumber: "dl 1c-ab 1234", fuelType: "CNG", expectedMileage: null }));
  });
});

describe("Permission-gated controls", () => {
  const as = (roles: authModule.Role[], ui: ReactNode) => (
    <authModule.AuthContext.Provider value={{ user: { id: 1, email: "u@x", fullName: "U", roles }, loading: false, login: vi.fn(), logout: vi.fn() }}>
      {ui}
    </authModule.AuthContext.Provider>
  );

  it("hides write controls from a viewer and shows them to an operator", () => {
    const { rerender } = render(as(["VIEWER"], <Can perm="write"><button>Add vehicle</button></Can>));
    expect(screen.queryByRole("button", { name: "Add vehicle" })).not.toBeInTheDocument();
    rerender(as(["OPERATOR"], <Can perm="write"><button>Add vehicle</button></Can>));
    expect(screen.getByRole("button", { name: "Add vehicle" })).toBeInTheDocument();
    rerender(as(["OPERATOR"], <Can perm="manage"><button>Replace records</button></Can>));
    expect(screen.queryByRole("button", { name: "Replace records" })).not.toBeInTheDocument();
    rerender(as(["MANAGER"], <Can perm="manage"><button>Replace records</button></Can>));
    expect(screen.getByRole("button", { name: "Replace records" })).toBeInTheDocument();
  });
});

describe("Vehicle master preview and disposal", () => {
  it("shows the derived status and GPS tracking for each master row", () => {
    const rows = [
      { rowNumber: 2, status: "VALID", parsed: { registration: "DL1AA0001", model: "Ciaz", driverName: "TEST", operatorLabel: "DRIVER",
        preview: { status: "ACTIVE", operatorType: "OWN_DRIVER", gpsKmTracked: true, make: "Maruti Suzuki", modelName: "Ciaz", fuel: "CNG_PETROL", existing: false } }, messages: [] },
      { rowNumber: 3, status: "VALID", parsed: { registration: "DL1AA0003", model: "Ciaz", driverName: null, operatorLabel: "WORKSHOP CH",
        preview: { status: "WORKSHOP", operatorType: "NONE", gpsKmTracked: false, make: "Maruti Suzuki", modelName: "Ciaz", fuel: "CNG_PETROL", existing: true } }, messages: [] },
    ];
    render(<ImportPreview rows={rows} importType="VEHICLE_MASTER" />);
    const body = within(screen.getByRole("table", { name: "Import preview" })).getAllByRole("row").slice(1);
    expect(within(body[0]).getByText("Active")).toBeInTheDocument();
    expect(within(body[0]).getByText("imported")).toBeInTheDocument();
    expect(within(body[0]).getByText("(new)")).toBeInTheDocument();
    expect(within(body[1]).getByText("Workshop")).toBeInTheDocument();
    expect(within(body[1]).getByText("not imported")).toBeInTheDocument();
    expect(within(body[1]).getByText("(update)")).toBeInTheDocument();
  });

  it("requires a dealer / buyer before a sale can be saved", async () => {
    wrap(<DisposeModal id="1" onClose={() => {}} onDone={() => {}} />);
    const save = screen.getByRole("button", { name: "Save" });
    expect(save).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Sold to (dealer / buyer)"), "Maruti True Value, Gurugram");
    expect(save).toBeEnabled();
    await userEvent.selectOptions(screen.getByLabelText("Disposal type"), "SCRAPPED");
    expect(screen.getByLabelText("Scrap dealer")).toBeInTheDocument();
  });
});

describe("Monthly mileage calculator", () => {
  it("shows month fuel expense ÷ month km per vehicle and filters mixed-fuel vehicles", async () => {
    const { api } = await import("../lib/api");
    const { MonthlyMileagePage } = await import("../pages/MonthlyMileage");
    const month = {
      month: "2026-10", totals: {
        vehicles: 2, vehiclesIncluded: 2, statusCounts: { OK: 1, MIXED_FUEL: 1 }, totalKm: 7200, totalFuelExpense: 28800.4,
        includedKm: 7200, includedFuelExpense: 28800.4, costPerKm: 4.0, mileageByEnergy: { CNG: { km: 4200, quantity: 180, mileage: 23.333, unit: "km/kg" } },
      },
      rows: [
        { vehicleId: 1, registrationNumber: "DL1MM0001", vehicle: "Maruti Dzire", fuelType: "CNG", operatorType: "OWN_DRIVER", km: 4200, cngKg: 180,
          fuelExpense: 15800.4, costPerKm: 3.76, mileage: 23.333, mileageUnit: "km/kg", expectedMileage: 24, variancePct: -2.78, belowExpected: false, status: "OK" },
        { vehicleId: 2, registrationNumber: "DL1MM0002", vehicle: "Maruti Ciaz", fuelType: "CNG_PETROL", operatorType: "OWN_DRIVER", km: 3000, cngKg: 120,
          petrolL: 10, fuelExpense: 13000, costPerKm: 4.33, mileage: null, expectedMileage: null, variancePct: null, belowExpected: false, status: "MIXED_FUEL" },
      ],
    };
    const spy = vi.spyOn(api, "get").mockImplementation(async (path: string) => (path.endsWith("/trend") ? [] : month) as never);
    wrap(<MonthlyMileagePage />);
    expect(await screen.findByText("₹4.00")).toBeInTheDocument();                  // fleet ₹/km
    expect(screen.getAllByText("23.33 km/kg")).toHaveLength(2);                  // fleet CNG card + vehicle row
    const table = screen.getByRole("table", { name: "Monthly mileage by vehicle" });
    expect(within(table).getByText("₹3.76")).toBeInTheDocument();
    expect(within(table).getByText("180 kg CNG + 10 L petrol".replace("180", "120"))).toBeInTheDocument();
    await userEvent.click(screen.getByRole("tab", { name: /Mixed fuel/ }));
    expect(within(table).queryByText("DL1MM0001")).not.toBeInTheDocument();
    expect(within(table).getByText("DL1MM0002")).toBeInTheDocument();
    spy.mockRestore();
  }, 30000);
});
