"""Raise (never lower) checked-in JaCoCo floors from freshly generated reports."""
import argparse
from decimal import Decimal, ROUND_FLOOR
from pathlib import Path
import re
import xml.etree.ElementTree as ET

MODULES = ["shared-auth", "services/auth-service", "services/appointment-service", "services/prediction-service", "services/report-service"]


def measured_floors(report):
    root = ET.parse(report).getroot()
    result = {}
    for kind in ("LINE", "BRANCH"):
        counter = next((item for item in root.findall("counter") if item.get("type") == kind), None)
        if counter is None:
            raise ValueError(f"Missing {kind} coverage in {report}")
        covered, missed = int(counter.get("covered")), int(counter.get("missed"))
        if covered + missed == 0:
            raise ValueError(f"Empty {kind} coverage in {report}")
        result[kind.lower()] = (Decimal(covered) / Decimal(covered + missed)).quantize(Decimal("0.01"), rounding=ROUND_FLOOR)
    return result


def raise_floors(pom, measured):
    for kind, observed in measured.items():
        prop = f"coverage.{kind}.minimum"
        pattern = rf"(<{re.escape(prop)}>)([^<]+)(</{re.escape(prop)}>)"
        match = re.search(pattern, pom)
        if match is None:
            raise ValueError(f"Missing {prop} in pom")
        minimum = max(Decimal(match[2]), observed)
        pom = re.sub(pattern, lambda item: item[1] + str(minimum) + item[3], pom, count=1)
    return pom


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Apply increases after reviewing a clean verify build")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    changes = []
    for module in MODULES:
        directory = root / module
        measured = measured_floors(directory / "target/site/jacoco/jacoco.xml")
        pom = directory / "pom.xml"
        updated = raise_floors(pom.read_text(), measured)
        print(f"{module}: measured floors {measured}; {'raise available' if updated != pom.read_text() else 'no increase'}")
        changes.append((pom, updated))
    # Validate all reports before mutating any pom. Reports must come from a successful clean verify.
    if args.write:
        for pom, updated in changes:
            pom.write_text(updated)


if __name__ == "__main__":
    main()
