import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const frontendDir = dirname(dirname(fileURLToPath(import.meta.url)));
const repositoryDir = dirname(frontendDir);
const lockfilePath = join(frontendDir, "package-lock.json");
const outputPath = join(repositoryDir, "THIRD_PARTY_NOTICES.md");
const checkOnly = process.argv.includes("--check");

const mitLicense = (copyright) => `MIT License

Copyright (c) ${copyright}

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.`;

const fallbackNotices = {
  "@excalidraw/excalidraw": {
    license: "MIT",
    text: mitLicense("2020 Excalidraw"),
  },
  "react-remove-scroll-bar": {
    license: "MIT",
    text: mitLicense("2017 Anton Korzunov"),
  },
  fuzzy: {
    license: "MIT",
    text: mitLicense("2015 Matt York"),
  },
  fsevents: {
    license: "MIT",
    text: mitLicense("2010-2020 Philipp Dunkel, Ben Noordhuis, Elan Shankar, Paul Miller"),
  },
  fastdom: {
    license: "MIT",
    text: mitLicense("2016 Wilson Page <wilsonpage@me.com>"),
  },
  khroma: {
    license: "MIT",
    text: mitLicense("2019-present Fabio Spampinato, Andrew Maney"),
  },
  strictdom: {
    license: "MIT",
    text: mitLicense("2013 Wilson Page <wilsonpage@me.com>"),
  },
};

const radixNotice = {
  license: "MIT",
  text: mitLicense("2022 WorkOS"),
};

function normalizeText(text) {
  return text.replace(/\r\n/g, "\n").trim();
}

function packageNameFromLockPath(packagePath) {
  return packagePath.replaceAll("\\", "/").split("node_modules/").at(-1);
}

function normalizeRepository(repository) {
  const value = typeof repository === "string" ? repository : repository?.url;
  if (!value) return null;
  return value
    .replace(/^git\+/, "")
    .replace(/^git:\/\//, "https://")
    .replace(/\.git$/, "");
}

function fallbackFor(name) {
  if (name.startsWith("@radix-ui/")) return radixNotice;
  return fallbackNotices[name] ?? null;
}

function readPackageNotice(packageDirectory, name) {
  if (existsSync(packageDirectory)) {
    const noticeFiles = readdirSync(packageDirectory)
      .filter((file) => /^(licen[cs]e|copying|notice)(?:[._-].*)?$/i.test(file))
      .sort((left, right) => left.localeCompare(right));

    if (noticeFiles.length > 0) {
      return noticeFiles
        .map((file) => {
          const text = normalizeText(readFileSync(join(packageDirectory, file), "utf8"));
          return noticeFiles.length === 1 ? text : `${file}\n\n${text}`;
        })
        .join("\n\n---\n\n");
    }
  }

  const fallback = fallbackFor(name);
  if (!fallback) {
    throw new Error(`No license or notice text found for production dependency ${name}`);
  }
  return fallback.text;
}

const lockfile = JSON.parse(readFileSync(lockfilePath, "utf8"));
const packages = new Map();

for (const [packagePath, lockEntry] of Object.entries(lockfile.packages)) {
  if (!packagePath || lockEntry.dev === true) continue;

  const packageDirectory = join(frontendDir, packagePath);
  const installedManifestPath = join(packageDirectory, "package.json");
  const manifest = existsSync(installedManifestPath)
    ? JSON.parse(readFileSync(installedManifestPath, "utf8"))
    : {};
  const name = manifest.name ?? packageNameFromLockPath(packagePath);
  const version = manifest.version ?? lockEntry.version;
  const id = `${name}@${version}`;
  const fallback = fallbackFor(name);
  const license = manifest.license ?? lockEntry.license ?? fallback?.license;

  if (!license) {
    throw new Error(`No declared license found for production dependency ${id}`);
  }

  const source =
    normalizeRepository(manifest.repository) ??
    manifest.homepage ??
    `https://www.npmjs.com/package/${name}/v/${version}`;
  const notice = readPackageNotice(packageDirectory, name);

  if (!packages.has(id)) {
    packages.set(id, { id, license, source, notice });
  }
}

const sortedPackages = [...packages.values()].sort((left, right) =>
  left.id.localeCompare(right.id),
);
const noticeIds = new Map();

for (const dependency of sortedPackages) {
  if (!noticeIds.has(dependency.notice)) {
    noticeIds.set(dependency.notice, `N${noticeIds.size + 1}`);
  }
  dependency.noticeId = noticeIds.get(dependency.notice);
}

const lines = [
  "# Third-Party Notices",
  "",
  "This file is generated from the production dependency graph locked in",
  "`frontend/package-lock.json`. It records the license and bundled notice text",
  "for each production dependency included by the Excalidraw frontend.",
  "",
  "Regenerate it with `npm run licenses` from the `frontend` directory.",
  "",
  "## Package Inventory",
  "",
  "| Package | License | Notice | Source |",
  "| --- | --- | --- | --- |",
  ...sortedPackages.map(
    ({ id, license, noticeId, source }) =>
      `| \`${id}\` | ${license} | [${noticeId}](#${noticeId.toLowerCase()}) | ${source} |`,
  ),
  "",
  "## License And Notice Texts",
  "",
];

for (const [notice, noticeId] of noticeIds) {
  lines.push(
    `### ${noticeId}`,
    "",
    ...notice.split("\n").map((line) => {
      const normalizedLine = line.trimEnd();
      return normalizedLine ? `    ${normalizedLine}` : "";
    }),
    "",
  );
}

lines.push(
  "## Trademark And Affiliation",
  "",
  "This plugin is unofficial and is not endorsed by or affiliated with Excalidraw.",
);

const generated = `${lines.join("\n")}\n`;

if (checkOnly) {
  const current = existsSync(outputPath) ? normalizeText(readFileSync(outputPath, "utf8")) : "";
  if (current !== normalizeText(generated)) {
    console.error("THIRD_PARTY_NOTICES.md is out of date. Run `npm run licenses` in frontend.");
    process.exit(1);
  }
  console.log(`Verified notices for ${sortedPackages.length} production dependencies.`);
} else {
  writeFileSync(outputPath, generated, "utf8");
  console.log(`Wrote notices for ${sortedPackages.length} production dependencies to ${outputPath}.`);
}
