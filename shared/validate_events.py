"""Strict dependency-free boundary checks for the common JSONL contract."""
import argparse
import json
import math
from datetime import datetime, timedelta
from pathlib import Path
from uuid import UUID

SCHEMA = json.loads(Path(__file__).with_name("event-v1.schema.json").read_text())


def timestamp(value):
    if not isinstance(value, str):
        raise ValueError("timestamp must be a string")
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None or result.utcoffset() != timedelta(0):
        raise ValueError("timestamp must explicitly use UTC")
    return result


def validate(event):
    if not isinstance(event, dict):
        raise ValueError("event must be an object")
    for key in SCHEMA["required"]:
        if key not in event:
            raise ValueError(f"missing {key}")
    if type(event["schemaVersion"]) is not int or event["schemaVersion"] != 1:
        raise ValueError("unsupported schemaVersion")
    for key in ("eventId", "sessionId"):
        value = event[key]
        if value is None and key == "sessionId":
            continue
        if not isinstance(value, str) or str(UUID(value)) != value.lower():
            raise ValueError(f"{key} must be a canonical UUID")
    for key in ("source", "category", "type", "evidence", "coverage"):
        if key in event and event[key] not in SCHEMA["properties"][key]["enum"]:
            raise ValueError(f"invalid {key}")
    timestamp(event["occurredAt"])
    for key in ("startedAt", "endedAt"):
        if event.get(key) is not None:
            timestamp(event[key])
    if event.get("startedAt") and event.get("endedAt"):
        if timestamp(event["endedAt"]) < timestamp(event["startedAt"]):
            raise ValueError("interval ends before it starts")
    if not isinstance(event["metrics"], dict):
        raise ValueError("metrics must be an object")
    allowed = SCHEMA["properties"]["metrics"]["properties"]
    for key, value in event["metrics"].items():
        if key not in allowed:
            raise ValueError(f"unknown metric {key}")
        if value is not None and (type(value) not in (int, float) or not math.isfinite(value) or value < 0):
            raise ValueError(f"{key} must be a finite nonnegative number or null")
    for key in ("outcome", "taskRef", "deviceId", "reason"):
        if event.get(key) is not None and not isinstance(event[key], str):
            raise ValueError(f"{key} must be a string or null")
    return event


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()
    count = 0
    for path in args.files:
        for number, line in enumerate(path.read_text().splitlines(), 1):
            if not line.strip():
                continue
            try:
                validate(json.loads(line))
            except (ValueError, TypeError, KeyError) as error:
                parser.exit(1, f"{path.name}:{number}: {error}\n")
            count += 1
    print(f"Validated {count} events; no personal data copied or changed.")


if __name__ == "__main__":
    main()
