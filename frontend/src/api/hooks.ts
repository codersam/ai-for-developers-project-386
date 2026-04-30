import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { getClientTimezone } from "../lib/timezone";
import type { components } from "./schema";

export function useEventTypes() {
  return useQuery({
    queryKey: ["event_types"],
    queryFn: async () => {
      const { data, error } = await api.GET("/calendar/event_types");
      if (error) throw error;
      return data;
    },
  });
}

export function useEventType(eventTypeId: string | undefined) {
  return useQuery({
    queryKey: ["event_types"],
    queryFn: async () => {
      const { data, error } = await api.GET("/calendar/event_types");
      if (error) throw error;
      return data;
    },
    enabled: !!eventTypeId,
    select: (list) => list?.find((et) => et.eventTypeId === eventTypeId),
  });
}

export function useAvailableSlots(eventTypeId: string | undefined) {
  const clientTimeZone = getClientTimezone();
  return useQuery({
    enabled: !!eventTypeId,
    queryKey: ["available_slots", eventTypeId, clientTimeZone],
    queryFn: async () => {
      const { data, error } = await api.GET(
        "/calendar/event_types/{eventTypeId}/available_slots",
        { params: { path: { eventTypeId: eventTypeId! }, query: { clientTimeZone } } },
      );
      if (error) throw error;
      return data?.slotsPerDay ?? [];
    },
  });
}

export type CreateScheduledEventBody = components["schemas"]["CreateScheduledEvent"];

export function useCreateScheduledEvent() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateScheduledEventBody) => {
      const { data, error } = await api.POST("/calendar/scheduled_events", { body });
      if (error) throw error;
      return data!;
    },
    onSuccess: (_d, vars) => {
      qc.invalidateQueries({ queryKey: ["scheduled_events"] });
      qc.invalidateQueries({ queryKey: ["available_slots", vars.eventTypeId] });
    },
  });
}
