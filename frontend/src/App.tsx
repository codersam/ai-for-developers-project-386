import { AppShell, Group, Title, Anchor } from "@mantine/core";
import { NavLink } from "react-router";
import AppRoutes from "./routes";

export default function App() {
  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Title order={4}>Calendar</Title>
          <Group gap="md">
            <Anchor component={NavLink} to="/" end>
              Book
            </Anchor>
            <Anchor component={NavLink} to="/admin/event-types">
              Event types
            </Anchor>
            <Anchor component={NavLink} to="/admin/bookings">
              Upcoming
            </Anchor>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Main>
        <AppRoutes />
      </AppShell.Main>
    </AppShell>
  );
}
