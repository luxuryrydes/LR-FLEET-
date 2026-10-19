import { useQuery } from "@tanstack/react-query";
import { api, type Row } from "./api";

export interface Lookups {
  vehicleCategories: Row[];
  serviceCategories: Row[];
  departments: Row[];
  vendors: Row[];
  drivers: Row[];
  fuelTypes: string[];
  energyTypes: string[];
  ownershipTypes: string[];
  vehicleStatuses: string[];
  documentTypes: string[];
}

export function useLookups() {
  return useQuery({ queryKey: ["lookups"], queryFn: () => api.get<Lookups>("/api/lookups"), staleTime: 60_000 });
}
