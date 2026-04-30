import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { getClientTimezone } from "../lib/timezone";
import type { components } from "./schema";

type ApiError = components["schemas"]["Error"];

function toError(error: ApiError): Error {
  return new Error(`[${error.code}] ${error.message}`);
}

export function useEventTypes() {
  return useQuery({
    queryKey: ["event_types"],
    queryFn: async () => {
      const { data, error } = await api.GET("/calendar/event_types");
      if (error) throw toError(error);
      return data;
    },
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
      if (error) throw toError(error);
      return data ?? [];
    },
  });
}

export type CreateScheduledEventBody = components["schemas"]["CreateScheduledEvent"];

export function useCreateScheduledEvent() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateScheduledEventBody) => {
      const { data, error } = await api.POST("/calendar/scheduled_events", { body });
      if (error) throw toError(error);
      return data!;
    },
    onSuccess: (_d, vars) => {
      qc.invalidateQueries({ queryKey: ["scheduled_events"] });
      qc.invalidateQueries({ queryKey: ["available_slots", vars.eventTypeId] });
    },
  });
}

export type CreateEventTypeBody = components["schemas"]["CreateEventType"];

export function useCreateEventType() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateEventTypeBody) => {
      const { data, error } = await api.POST("/calendar/event_types", { body });
      if (error) throw toError(error);
      return data!;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["event_types"] });
    },
  });
}

export function useScheduledEvents() {
  const clientTimeZone = getClientTimezone();
  return useQuery({
    queryKey: ["scheduled_events", clientTimeZone],
    queryFn: async () => {
      const { data, error } = await api.GET("/calendar/scheduled_events", {
        params: { query: { clientTimeZone } },
      });
      if (error) throw toError(error);
      return data ?? [];
    },
  });
}
