import { execSync } from "node:child_process";
import path from "node:path";

export default async function globalSetup() {
  const repoRoot = path.resolve(__dirname, "..");

  execSync(
    "docker compose -f compose.prod.yaml up -d --build --wait",
    { cwd: repoRoot, stdio: "inherit" },
  );

  const baseURL = process.env.E2E_BASE_URL ?? "http://localhost:8080";
  const healthUrl = `${baseURL}/api/actuator/health`;

  const res = await fetch(healthUrl);
  if (!res.ok) {
    throw new Error(`Health check failed: ${res.status} ${res.statusText} at ${healthUrl}`);
  }
  const body = (await res.json()) as { status?: string };
  if (body.status !== "UP") {
    throw new Error(`Backend not UP at ${healthUrl}: ${JSON.stringify(body)}`);
  }
}
