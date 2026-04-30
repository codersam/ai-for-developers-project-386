import { Button, Stack, Text, Title } from "@mantine/core";
import { Link, useLocation } from "react-router";
import type { components } from "../../api/schema";

type Nav = {
  eventType?: components["schemas"]["EventType"];
  date?: string;
  time?: string;
};

export default function BookingConfirmedPage() {
  const { state } = useLocation() as { state: Nav | null };

  return (
    <Stack align="center" gap="md" mt="xl">
      <Title order={2}>You're booked!</Title>
      {state?.eventType ? (
        <Text>
          {state.eventType.eventTypeName} on {state.date} at {state.time}{" "}
          ({state.eventType.durationMinutes} min)
        </Text>
      ) : (
        <Text c="dimmed">Your booking was created.</Text>
      )}
      <Button component={Link} to="/">
        Back to event types
      </Button>
    </Stack>
  );
}
