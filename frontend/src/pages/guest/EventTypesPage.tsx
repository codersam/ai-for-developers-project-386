import { Alert, Center, Loader, SimpleGrid, Stack, Title, Text } from "@mantine/core";
import { useEventTypes } from "../../api/hooks";
import { EventTypeCard } from "../../components/EventTypeCard";

export default function EventTypesPage() {
  const { data, isLoading, error } = useEventTypes();

  if (isLoading) {
    return (
      <Center mih={240}>
        <Loader />
      </Center>
    );
  }

  if (error) {
    return <Alert color="red" title="Couldn't load event types">{String(error)}</Alert>;
  }

  if (!data || data.length === 0) {
    return <Text c="dimmed">No event types yet.</Text>;
  }

  return (
    <Stack gap="lg" w="66.67%" mx="auto">
      <Title order={2}>Pick an event type</Title>
      <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }} spacing="md">
        {data.map((et) => (
          <EventTypeCard key={et.eventTypeId} eventType={et} />
        ))}
      </SimpleGrid>
    </Stack>
  );
}
