import { Routes, Route } from "react-router";
import EventTypesPage from "../pages/guest/EventTypesPage";

export default function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<EventTypesPage />} />
    </Routes>
  );
}
