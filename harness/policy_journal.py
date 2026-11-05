"""Opt-in local policy journal. No policy is applied by this module.

Call propose() before the platform's durable commit; call confirm() only after
that commit succeeds, with the same IDs and a new operation ID. A missing
confirmation means unknown, not inactive or active. Keep operation IDs and
observed_at stable on retries. The caller owns durable operation IDs and its
HMAC key; a lost key prevents correlating future target pseudonyms.

JSONL is append-only for this API, not tamper-proof against filesystem owners.
A partial final record fails closed and needs explicit external recovery; this
module never truncates it. HMAC identifiers are pseudonyms, not anonymous data.
"""
import datetime as dt
import fcntl
import hashlib
import hmac
import json
import os
import re
import uuid
from pathlib import Path

SOURCES = {"mac", "android", "local_review"}
STATUSES = {"unknown", "enforced", "partially_enforced", "unavailable", "not_enforced"}


def _uuid(value):
    return str(uuid.UUID(str(value)))


def _instant(value):
    # datetime supports microseconds; reject higher precision instead of silently losing it.
    if re.search(r"[.,]\d{7,}", value):
        raise ValueError("instant precision exceeds microseconds")
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("instant must include an offset")
    return parsed.astimezone(dt.timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _integer(value, minimum):
    if type(value) is not int or value < minimum:
        raise ValueError("invalid policy duration")
    return value


def _snapshot(policy, key):
    # Deliberately do not serialize unknown fields, names, or user-entered text.
    if not isinstance(key, bytes) or len(key) < 32:
        raise ValueError("a caller-managed key of at least 32 bytes is required")
    targets = policy["targetRefs"]
    if not isinstance(targets, list) or not all(isinstance(x, str) and x for x in targets):
        raise ValueError("targetRefs must be a list of nonempty strings")
    start, end = _instant(policy["startsAt"]), _instant(policy["endsAt"])
    if start >= end:
        raise ValueError("invalid window")
    local_date = dt.date.fromisoformat(policy["localDate"]).isoformat()
    if policy.get("policyVersion") != 2:
        raise ValueError("unsupported policy version")
    return {"policyVersion": 2, "windowId": _uuid(policy["windowId"]),
            "localDate": local_date, "startsAt": start, "endsAt": end,
            "targetDigests": sorted(set(hmac.new(key, x.encode(), hashlib.sha256).hexdigest() for x in targets)),
            "reliefBudgetSeconds": _integer(policy["reliefBudgetSeconds"], 0),
            "reliefMaxGrantSeconds": _integer(policy["reliefMaxGrantSeconds"], 1),
            "exitCoolingOffSeconds": _integer(policy["exitCoolingOffSeconds"], 1)}


class PolicyJournal:
    def __init__(self, path, *, opted_in, target_key):
        if opted_in is not True:
            raise ValueError("explicit local opt-in required")
        if not isinstance(target_key, bytes) or len(target_key) < 32:
            raise ValueError("a caller-managed key of at least 32 bytes is required")
        self.path = Path(path)
        self.key = target_key

    def propose(self, *, operation_id, version_id, observed_at, source, policy):
        return self._append({"kind": "proposal", "operationId": _uuid(operation_id),
                             "versionId": _uuid(version_id), "observedAt": _instant(observed_at),
                             "source": source, "observedStatus": "unknown",
                             "policy": _snapshot(policy, self.key)})

    def confirm(self, *, operation_id, version_id, observed_at, source, observed_status="unknown"):
        return self._append({"kind": "commit", "operationId": _uuid(operation_id),
                             "versionId": _uuid(version_id), "observedAt": _instant(observed_at),
                             "source": source, "observedStatus": observed_status})

    def _append(self, record):
        if record["source"] not in SOURCES or record["observedStatus"] not in STATUSES:
            raise ValueError("invalid source or observed status")
        # Parent directory must already exist; no implicit source/config discovery.
        fd = os.open(self.path, os.O_RDWR | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "r+b") as stream:
            fcntl.flock(stream, fcntl.LOCK_EX)
            stream.seek(0)
            raw = stream.read()
            if raw and not raw.endswith(b"\n"):
                raise ValueError("incomplete journal tail; manual recovery required")
            try:
                records = [json.loads(line) for line in raw.splitlines()]
            except (ValueError, UnicodeDecodeError) as exc:
                raise ValueError("invalid journal; manual recovery required") from exc
            for old in records:
                if old["operationId"] == record["operationId"]:
                    if old != record:
                        raise ValueError("operation ID reused with different data")
                    return old
            same_version = [r for r in records if r["versionId"] == record["versionId"]]
            if record["kind"] == "proposal" and same_version:
                raise ValueError("version ID already exists")
            if record["kind"] == "commit":
                if not any(r["kind"] == "proposal" for r in same_version):
                    raise ValueError("commit requires a proposal")
                if any(r["kind"] == "commit" for r in same_version):
                    raise ValueError("version already committed")
            encoded = (json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n").encode()
            stream.seek(0, os.SEEK_END)
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
            return record
