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

model CreateEventType {
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
  subject: string;
  notes: string;
  guestName: string;
  guestEmail: string;
  guestTimezone: string;
}

model CreateScheduledEvent {
  eventTypeId: string;
  date: plainDate;
  time: plainTime;
  subject: string;
  notes: string;
  guestName: string;
  guestEmail: string;
  guestTimezone: string;
}

model TimeSlotsOfTheDay {
  date: plainDate;
  timeSlots: TimeSlot[];
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
  createEventType(@body body: CreateEventType): EventType | Error;

  @route("scheduled_events")
  @get
  listScheduledEvents(): ScheduledEvent[] | Error;

  @route("scheduled_events/{scheduledEventId}")
  @get
  getScheduledEventById(@path scheduledEventId: string): ScheduledEvent | Error;

  // --------- Guest --------------

  @route("event_types")
  @get
  listEventTypes(): EventType[] | Error;

  @route("event_types/{eventTypeId}/available_slots")
  @get
  listAvailableSlots(
    @path eventTypeId: string,
    @query clientTimeZone: string,
  ): TimeSlotsOfTheDay[] | Error;

  @route("scheduled_events")
  @post
  createScheduledEvent(
    @body body: CreateScheduledEvent,
  ): ScheduledEvent | Error;
}
