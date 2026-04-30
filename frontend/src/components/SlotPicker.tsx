import { useMemo, useState } from "react";
import { Button, Group, ScrollArea, Stack, Text } from "@mantine/core";
import { DatePicker } from "@mantine/dates";
import type { components } from "../api/schema";
import { getBookingWindow } from "../lib/bookingWindow";
import { dayjs } from "../lib/dayjs";

export type SelectedSlot = { date: string; time: string; duration: number };

type Props = {
  slotsPerDay: components["schemas"]["TimeSlotsOfTheDay"][];
  onSelect: (slot: SelectedSlot) => void;
};

export function SlotPicker({ slotsPerDay, onSelect }: Props) {
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const { min, max } = useMemo(() => getBookingWindow(), []);
  const slotMap = useMemo(
    () => new Map(slotsPerDay.map((d) => [d.date, d.timeSlots])),
    [slotsPerDay],
  );

  const dayTimes = selectedDate ? slotMap.get(selectedDate) ?? [] : [];

  return (
    <Group align="flex-start" gap="xl" wrap="nowrap">
      <DatePicker
        value={selectedDate}
        onChange={setSelectedDate}
        minDate={min}
        maxDate={max}
        excludeDate={(d) => {
          const iso = dayjs(d).format("YYYY-MM-DD");
          const ts = slotMap.get(iso);
          return !ts || ts.length === 0;
        }}
      />
      <ScrollArea h={320} w={280}>
        {!selectedDate ? (
          <Text c="dimmed">Pick a day to see times</Text>
        ) : dayTimes.length === 0 ? (
          <Text c="dimmed">No times available for this day</Text>
        ) : (
          <Stack gap="xs">
            {dayTimes.map((t) => (
              <Button
                key={t.timeStart}
                variant="light"
                onClick={() =>
                  onSelect({ date: selectedDate, time: t.timeStart, duration: t.duration })
                }
              >
                {t.timeStart}
              </Button>
            ))}
          </Stack>
        )}
      </ScrollArea>
    </Group>
  );
}
