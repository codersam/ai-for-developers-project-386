import {
  Alert,
  Badge,
  Button,
  Card,
  Center,
  Group,
  Loader,
  Modal,
  NumberInput,
  Stack,
  Text,
  Textarea,
  TextInput,
  Title,
} from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import {
  useCreateEventType,
  useEventTypes,
  type CreateEventTypeBody,
} from "../../api/hooks";

export default function EventTypesAdminPage() {
  const { data, isLoading, error } = useEventTypes();
  const create = useCreateEventType();
  const [opened, { open, close }] = useDisclosure(false);

  const form = useForm<CreateEventTypeBody>({
    initialValues: { eventTypeName: "", description: "", durationMinutes: 30 },
    validate: {
      eventTypeName: (v) => (v.trim().length < 2 ? "Required" : null),
      description: (v) => (v.trim().length < 1 ? "Required" : null),
      durationMinutes: (v) => (v > 0 && v <= 240 ? null : "1–240"),
    },
  });

  const handleSubmit = form.onSubmit((values) => {
    create.mutate(values, {
      onSuccess: () => {
        notifications.show({
          color: "green",
          title: "Created",
          message: values.eventTypeName,
        });
        form.reset();
        close();
      },
      onError: (e) =>
        notifications.show({
          color: "red",
          title: "Couldn't create",
          message: e.message,
        }),
    });
  });

  if (isLoading) {
    return (
      <Center mih={240}>
        <Loader />
      </Center>
    );
  }

  if (error) {
    return (
      <Alert color="red" title="Couldn't load event types">
        {error.message}
      </Alert>
    );
  }

  return (
    <Stack gap="lg" w="66.67%" mx="auto">
      <Group justify="space-between" align="center">
        <Title order={2}>Event types</Title>
        <Button onClick={open}>New event type</Button>
      </Group>

      {!data || data.length === 0 ? (
        <Text c="dimmed">No event types yet.</Text>
      ) : (
        <Stack gap="md">
          {data.map((et) => (
            <Card key={et.eventTypeId} withBorder padding="lg" radius="md">
              <Stack gap="sm">
                <Group justify="space-between" align="flex-start">
                  <Text fw={600} size="lg">
                    {et.eventTypeName}
                  </Text>
                  <Badge variant="light">{et.durationMinutes} min</Badge>
                </Group>
                <Text size="sm" c="dimmed">
                  {et.description}
                </Text>
              </Stack>
            </Card>
          ))}
        </Stack>
      )}

      <Modal opened={opened} onClose={close} title="New event type" centered>
        <form onSubmit={handleSubmit}>
          <Stack gap="sm">
            <TextInput
              label="Name"
              placeholder="30-minute intro"
              {...form.getInputProps("eventTypeName")}
            />
            <Textarea
              label="Description"
              placeholder="What's this event about?"
              autosize
              minRows={2}
              {...form.getInputProps("description")}
            />
            <NumberInput
              label="Duration (minutes)"
              min={1}
              max={240}
              step={15}
              {...form.getInputProps("durationMinutes")}
            />
            <Group justify="flex-end" mt="sm">
              <Button variant="default" onClick={close}>
                Cancel
              </Button>
              <Button type="submit" loading={create.isPending}>
                Create
              </Button>
            </Group>
          </Stack>
        </form>
      </Modal>
    </Stack>
  );
}
