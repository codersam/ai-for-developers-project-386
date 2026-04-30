import { Routes, Route } from "react-router";
import EventTypesPage from "../pages/guest/EventTypesPage";
import BookEventPage from "../pages/guest/BookEventPage";
import BookingConfirmedPage from "../pages/guest/BookingConfirmedPage";
import EventTypesAdminPage from "../pages/owner/EventTypesAdminPage";
import ScheduledEventsPage from "../pages/owner/ScheduledEventsPage";

export default function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<EventTypesPage />} />
      <Route path="/book/:eventTypeId" element={<BookEventPage />} />
      <Route path="/book/:eventTypeId/confirmed" element={<BookingConfirmedPage />} />
      <Route path="/admin/event-types" element={<EventTypesAdminPage />} />
      <Route path="/admin/bookings" element={<ScheduledEventsPage />} />
    </Routes>
  );
}
