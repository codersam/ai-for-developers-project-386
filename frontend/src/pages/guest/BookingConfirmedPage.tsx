import { Alert, Button, Center, Loader, Stack, Text, Title } from "@mantine/core";
import { Link, useParams } from "react-router";
import { useEventTypes, useScheduledEvent } from "../../api/hooks";
import { dayjs } from "../../lib/dayjs";
import { getClientTimezone } from "../../lib/timezone";

export default function BookingConfirmedPage() {
  const { scheduledEventId } = useParams();
  const bookingQuery = useScheduledEvent(scheduledEventId);
  const eventTypesQuery = useEventTypes();

  if (bookingQuery.isLoading || eventTypesQuery.isLoading) {
    return (
      <Center mih={240}>
        <Loader />
      </Center>
    );
  }

  if (bookingQuery.error) {
    return (
      <Alert color="red" title="Couldn't load this booking">
        {bookingQuery.error.message}
      </Alert>
    );
  }

  const booking = bookingQuery.data;
  if (!booking) {
    return <Alert color="red" title="Booking not found">No booking matches this id.</Alert>;
  }

  const eventType = eventTypesQuery.data?.find(
    (et) => et.eventTypeId === booking.eventTypeId,
  );
  const tz = getClientTimezone();
  const start = dayjs(booking.utcDateStart).tz(tz);

  return (
    <Stack align="center" gap="md" mt="xl">
      <Title order={2}>You're booked!</Title>
      <Text>
        {eventType?.eventTypeName ?? "Booking"} on {start.format("YYYY-MM-DD")} at{" "}
        {start.format("HH:mm")} ({booking.durationMinutes} min)
      </Text>
      {booking.subject && <Text c="dimmed">{booking.subject}</Text>}
      <Button component={Link} to="/">
        Back to event types
      </Button>
    </Stack>
  );
}
