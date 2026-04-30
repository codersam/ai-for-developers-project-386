import { useQuery } from "@tanstack/react-query";
import { api } from "./client";

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
