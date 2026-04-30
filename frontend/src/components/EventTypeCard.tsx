import { Card, Text, Badge, Group, Button, Stack } from "@mantine/core";
import { Link } from "react-router";
import type { components } from "../api/schema";

type EventType = components["schemas"]["EventType"];

export function EventTypeCard({ eventType }: { eventType: EventType }) {
  return (
    <Card withBorder padding="lg" radius="md">
      <Stack gap="sm" h="100%">
        <Group justify="space-between" align="flex-start">
          <Text fw={600} size="lg">
            {eventType.eventTypeName}
          </Text>
          <Badge variant="light">{eventType.durationMinutes} min</Badge>
        </Group>
        <Text size="sm" c="dimmed" style={{ flexGrow: 1 }}>
          {eventType.description}
        </Text>
        <Button component={Link} to={`/book/${eventType.eventTypeId}`} variant="light">
          Book
        </Button>
      </Stack>
    </Card>
  );
}
