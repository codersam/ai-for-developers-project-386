import { test, expect } from "@playwright/test";

test("owner creates event type, guest books a slot, owner sees the booking", async ({ page }) => {
  const eventName = `Intro Call ${Date.now()}`;

  // --- Owner creates the event type ---
  await page.goto("/admin/event-types");
  await page.getByRole("button", { name: "New event type" }).click();

  const adminModal = page.getByRole("dialog", { name: "New event type" });
  await adminModal.getByLabel("Name").fill(eventName);
  await adminModal.getByLabel("Description").fill("A short intro chat for E2E.");
  // Duration NumberInput defaults to 30 — leave it.
  await adminModal.getByRole("button", { name: "Create" }).click();
  // Wait for the new card — not just any text match — because the Mantine
  // success notification echoes the same name and would race with the card.
  await expect(
    page.locator(".mantine-Card-root").filter({ hasText: eventName }),
  ).toBeVisible();

  // --- Guest books a slot ---
  await page.getByRole("banner").getByRole("link", { name: "Book" }).click();
  await expect(page).toHaveURL(/\/$/);
  const card = page.locator(".mantine-Card-root").filter({ hasText: eventName });
  await card.getByRole("link", { name: "Book" }).click();
  await expect(page).toHaveURL(/\/book\/[^/]+$/);

  // Pick the first enabled day in the Mantine DatePicker.
  // Disabled days carry data-disabled; enabled day buttons render the day number.
  const firstAvailableDay = page
    .locator("table button:not([data-disabled])")
    .filter({ hasText: /^\d{1,2}$/ })
    .first();
  await firstAvailableDay.click();

  // Time slots render as buttons labelled "HH:mm:ss" (per the OpenAPI contract).
  await page.getByRole("button", { name: /^\d{2}:\d{2}:\d{2}$/ }).first().click();

  // Booking modal.
  const bookingModal = page.getByRole("dialog", { name: "Confirm your booking" });
  await expect(bookingModal).toBeVisible();
  await bookingModal.getByLabel("Name").fill("E2E Guest");
  await bookingModal.getByLabel("Email").fill("e2e-guest@example.com");
  await bookingModal.getByLabel("What's this meeting about?").fill("Sync");
  await bookingModal.getByLabel(/Notes/).fill("Created by Playwright.");
  await bookingModal.getByRole("button", { name: "Book", exact: true }).click();

  // Confirmation page. Scope to <main> because Mantine notifications
  // (e.g. the "Created" toast) linger ~4s and echo the event name.
  const main = page.getByRole("main");
  await expect(page).toHaveURL(/\/book\/[^/]+\/confirmed\/[^/]+/);
  await expect(main.getByText("You're booked!")).toBeVisible();
  await expect(main.getByText(eventName)).toBeVisible();

  // --- Owner sees the booking ---
  await page.getByRole("banner").getByRole("link", { name: "Upcoming" }).click();
  await expect(page).toHaveURL(/\/admin\/bookings$/);
  await expect(main.getByText("Sync", { exact: true })).toBeVisible();
  await expect(main.getByText("e2e-guest@example.com")).toBeVisible();
  await expect(main.getByText(eventName)).toBeVisible();
});
