import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router";
import {
  Alert,
  Badge,
  Button,
  Center,
  Group,
  Loader,
  Modal,
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
  useAvailableSlots,
  useCreateScheduledEvent,
  useEventTypes,
} from "../../api/hooks";
import { SlotPicker, type SelectedSlot } from "../../components/SlotPicker";
import { isWithinBookingWindow } from "../../lib/bookingWindow";
import { getClientTimezone } from "../../lib/timezone";

export default function BookEventPage() {
  const { eventTypeId } = useParams();
  const navigate = useNavigate();

  const eventTypesQuery = useEventTypes();
  const eventType = eventTypesQuery.data?.find((et) => et.eventTypeId === eventTypeId);
  const slotsQuery = useAvailableSlots(eventTypeId);

  const [selectedSlot, setSelectedSlot] = useState<SelectedSlot | null>(null);
  const [opened, { open, close }] = useDisclosure(false);

  const create = useCreateScheduledEvent();

  const form = useForm({
    initialValues: { guestName: "", guestEmail: "", subject: "", notes: "" },
    validate: {
      guestName: (v) => (v.trim().length < 2 ? "Required" : null),
      guestEmail: (v) => (/^\S+@\S+\.\S+$/.test(v) ? null : "Invalid email"),
      subject: (v) => (v.trim().length < 2 ? "Required" : null),
    },
  });

  const slotsInWindow = useMemo(
    () => (slotsQuery.data ?? []).filter((d) => isWithinBookingWindow(d.date)),
    [slotsQuery.data],
  );

  if (eventTypesQuery.isLoading || slotsQuery.isLoading) {
    return (
      <Center mih={240}>
        <Loader />
      </Center>
    );
  }

  if (eventTypesQuery.error || slotsQuery.error) {
    return (
      <Alert color="red" title="Couldn't load this event type">
        {(eventTypesQuery.error ?? slotsQuery.error)?.message}
      </Alert>
    );
  }

  if (!eventType) {
    return <Alert color="red" title="Event type not found">No event type matches this id.</Alert>;
  }

  const handleSelect = (slot: SelectedSlot) => {
    setSelectedSlot(slot);
    form.reset();
    open();
  };

  const handleSubmit = form.onSubmit((values) => {
    if (!selectedSlot || !eventTypeId) return;
    create.mutate(
      {
        eventTypeId,
        date: selectedSlot.date,
        time: selectedSlot.time,
        guestTimezone: getClientTimezone(),
        ...values,
      },
      {
        onSuccess: (scheduled) => {
          notifications.show({ color: "green", title: "Booked", message: "See you then!" });
          close();
          navigate(`/book/${eventTypeId}/confirmed`, {
            state: { eventType, ...selectedSlot, scheduled },
          });
        },
        onError: (e) =>
          notifications.show({ color: "red", title: "Couldn't book", message: e.message }),
      },
    );
  });

  return (
    <Stack gap="lg">
      <Stack gap="xs">
        <Group gap="sm">
          <Title order={2}>{eventType.eventTypeName}</Title>
          <Badge variant="light">{eventType.durationMinutes} min</Badge>
        </Group>
        <Text c="dimmed">{eventType.description}</Text>
      </Stack>

      <TextInput
        label="Your timezone"
        value={getClientTimezone()}
        readOnly
        w={280}
      />

      <SlotPicker slotsPerDay={slotsInWindow} onSelect={handleSelect} />

      <Modal opened={opened} onClose={close} title="Confirm your booking" centered>
        {selectedSlot && (
          <Stack gap="sm">
            <Text size="sm" c="dimmed">
              {selectedSlot.date} at {selectedSlot.time} ({selectedSlot.duration} min)
            </Text>
            <form onSubmit={handleSubmit}>
              <Stack gap="sm">
                <TextInput
                  label="Name"
                  placeholder="Your name"
                  {...form.getInputProps("guestName")}
                />
                <TextInput
                  label="Email"
                  placeholder="you@example.com"
                  {...form.getInputProps("guestEmail")}
                />
                <TextInput
                  label="What's this meeting about?"
                  placeholder="Quick chat, project review…"
                  {...form.getInputProps("subject")}
                />
                <Textarea
                  label="Notes (optional)"
                  placeholder="Anything you'd like to share ahead of time"
                  autosize
                  minRows={2}
                  {...form.getInputProps("notes")}
                />
                <Group justify="flex-end" mt="sm">
                  <Button variant="default" onClick={close}>
                    Cancel
                  </Button>
                  <Button type="submit" loading={create.isPending}>
                    Book
                  </Button>
                </Group>
              </Stack>
            </form>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}
