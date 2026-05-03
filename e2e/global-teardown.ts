import { execSync } from "node:child_process";
import path from "node:path";

export default async function globalTeardown() {
  const repoRoot = path.resolve(__dirname, "..");

  execSync(
    "docker compose -f compose.prod.yaml down -v",
    { cwd: repoRoot, stdio: "inherit" },
  );
}
