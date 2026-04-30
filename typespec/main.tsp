import "@typespec/http";

using Http;
@service(#{ title: "Calendar Service" })
namespace CalendarService;

enum DayOfWeek {
  Monday,
  Tuesday,
  Wednesday,
  Thursday,
  Friday,
  Saturday,
  Sunday,
}

model TimeSlot {
  timeStart: plainTime;
  duration: int32;
}

model Calendar {
  ownerName: string;
  ownerEmail: string;
  startOfTheDayHour: plainTime;
  endOfTheDayHour: plainTime;
  breaks: TimeSlot[];
  workingDays: DayOfWeek[];
}

model EventType {
  @visibility(Lifecycle.Read)
  eventTypeId: string;

  eventTypeName: string;
  description: string;
  durationMinutes: int32;
}

model ScheduledEvent {
  @visibility(Lifecycle.Read)
  scheduledEventId: string;

  eventTypeId: string;
  utcDateStart: utcDateTime;
  durationMinutes: int32;
  guestName: string;
  guestEmail: string;
  guestTimezone: string;
}

model CreateScheduledEvent {
  eventTypeId: string;
  date: plainDate;
  time: plainTime;
  guestName: string;
  guestEmail: string;
  guestTimezone: string;
}

model TimeSlotsOfTheDay {
  date: plainDate;
  timeSlots: TimeSlot[];
}

model AvailableTimeSlots {
  slotsPerDay: TimeSlotsOfTheDay[];
}

@error
model Error {
  code: int32;
  message: string;
}

@route("/calendar")
@tag("Calendar")
interface CalendarService {
  // --------- Owner --------------

  @route("event_types")
  @post
  createEventType(@body body: EventType): EventType | Error;

  @route("scheduled_events")
  @get
  listScheduledEvents(@query clientTimeZone: string): ScheduledEvent[] | Error;

  // --------- Guest --------------

  @route("event_types")
  @get
  listEventTypes(): EventType[] | Error;

  @route("event_types/{eventTypeId}/available_slots")
  @get
  listAvailableSlots(
    @path eventTypeId: string,
    @query clientTimeZone: string,
  ): AvailableTimeSlots | Error;

  @route("scheduled_events")
  @post
  createScheduledEvent(
    @body body: CreateScheduledEvent,
  ): ScheduledEvent | Error;
}
