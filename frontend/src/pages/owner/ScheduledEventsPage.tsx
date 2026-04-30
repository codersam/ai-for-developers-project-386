import { useMemo } from "react";
import {
  Alert,
  Badge,
  Center,
  Loader,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from "@mantine/core";
import { useEventTypes, useScheduledEvents } from "../../api/hooks";
import { dayjs } from "../../lib/dayjs";
import { getClientTimezone } from "../../lib/timezone";

export default function ScheduledEventsPage() {
  const { data: events = [], isLoading, error } = useScheduledEvents();
  const { data: types = [] } = useEventTypes();

  const typeById = useMemo(
    () => new Map(types?.map((t) => [t.eventTypeId, t]) ?? []),
    [types],
  );

  const tz = getClientTimezone();

  const groups = useMemo(() => {
    const sorted = [...events].sort((a, b) =>
      a.utcDateStart.localeCompare(b.utcDateStart),
    );
    const map = new Map<string, typeof sorted>();
    for (const e of sorted) {
      const day = dayjs(e.utcDateStart).tz(tz).format("YYYY-MM-DD");
      const list = map.get(day);
      if (list) list.push(e);
      else map.set(day, [e]);
    }
    return Array.from(map.entries());
  }, [events, tz]);

  if (isLoading) {
    return (
      <Center mih={240}>
        <Loader />
      </Center>
    );
  }

  if (error) {
    return (
      <Alert color="red" title="Couldn't load bookings">
        {String(error)}
      </Alert>
    );
  }

  return (
    <Stack gap="lg">
      <Title order={2}>Upcoming bookings</Title>

      <TextInput label="Your timezone" value={tz} readOnly w={280} />

      {groups.length === 0 ? (
        <Text c="dimmed">No upcoming bookings.</Text>
      ) : (
        <Stack gap="lg">
          {groups.map(([day, dayEvents]) => (
            <Stack key={day} gap="sm">
              <Title order={4}>{day}</Title>
              <Table withTableBorder>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Time</Table.Th>
                    <Table.Th>Subject</Table.Th>
                    <Table.Th>Event type</Table.Th>
                    <Table.Th>Guest</Table.Th>
                    <Table.Th>Duration</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {dayEvents.map((e) => {
                    const type = typeById.get(e.eventTypeId);
                    return (
                      <Table.Tr key={e.scheduledEventId}>
                        <Table.Td>
                          {dayjs(e.utcDateStart).tz(tz).format("HH:mm")}
                        </Table.Td>
                        <Table.Td>
                          <Stack gap={2}>
                            <Text fw={500}>{e.subject}</Text>
                            {e.notes && (
                              <Text
                                size="xs"
                                c="dimmed"
                                lineClamp={2}
                                title={e.notes}
                              >
                                {e.notes}
                              </Text>
                            )}
                          </Stack>
                        </Table.Td>
                        <Table.Td>
                          {type ? (
                            type.eventTypeName
                          ) : (
                            <Text c="dimmed">unknown</Text>
                          )}
                        </Table.Td>
                        <Table.Td>
                          <Stack gap={0}>
                            <Text>{e.guestName}</Text>
                            <Text size="xs" c="dimmed">
                              {e.guestEmail}
                            </Text>
                          </Stack>
                        </Table.Td>
                        <Table.Td>
                          <Badge variant="light">{e.durationMinutes} min</Badge>
                        </Table.Td>
                      </Table.Tr>
                    );
                  })}
                </Table.Tbody>
              </Table>
            </Stack>
          ))}
        </Stack>
      )}
    </Stack>
  );
}
