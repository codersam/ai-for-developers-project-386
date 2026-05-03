import { test, expect } from "@playwright/test";

const deepLinks = ["/", "/admin/event-types", "/admin/bookings"];

for (const path of deepLinks) {
  test(`deep link ${path} loads the SPA shell`, async ({ page }) => {
    const res = await page.goto(path);
    expect(res?.status()).toBe(200);
    // The header is rendered by App.tsx for every route. Scope to the
    // banner because `/` may also list EventTypeCards with "Book" buttons.
    await expect(
      page.getByRole("banner").getByRole("link", { name: "Book" }),
    ).toBeVisible();
  });
}

test("hard refresh on an admin route does not 404", async ({ page }) => {
  await page.goto("/admin/event-types");
  await page.reload();
  await expect(page.getByRole("link", { name: "Event types" })).toBeVisible();
});
