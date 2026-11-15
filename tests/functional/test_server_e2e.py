"""End-to-end functional tests against a real Guard server and PostgreSQL."""

from __future__ import annotations

import base64
import json
import os
import secrets
import time
import uuid
from dataclasses import dataclass
from datetime import date

import pytest
import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


BASE_URL = os.environ.get("GUARD_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
WAIT_SECONDS = int(os.environ.get("GUARD_WAIT_SECONDS", "120"))
PASSWORD = "correct horse battery staple"

HTTP = requests.Session()
HTTP.trust_env = False


def request(method: str, path: str, *, token: str | None = None,
            expected: int = 200, **kwargs):
    headers = kwargs.pop("headers", {})
    if token:
        headers["Authorization"] = f"Bearer {token}"
    response = HTTP.request(method, f"{BASE_URL}{path}", headers=headers,
                            timeout=20, **kwargs)
    body = response.text
    assert response.status_code == expected, (
        f"{method} {path}: expected {expected}, got {response.status_code}: {body}"
    )
    if response.headers.get("content-type", "").startswith("application/json") and body:
        return response.json()
    return response


@dataclass
class Parent:
    email: str
    token: str
    parent_id: str

    def auth(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}"}


@dataclass
class Device:
    device_id: str
    token: str
    parent: Parent
    private_key: ec.EllipticCurvePrivateKey
    row_id: str = ""


def wait_for_server() -> None:
    deadline = time.monotonic() + WAIT_SECONDS
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            response = HTTP.get(f"{BASE_URL}/healthz", timeout=5)
            if response.status_code == 200 and response.json().get("database") is True:
                return
        except Exception as exc:  # pragma: no cover - diagnostic path
            last_error = exc
        time.sleep(1)
    raise AssertionError(f"Guard server did not become healthy at {BASE_URL}: {last_error}")


@pytest.fixture(scope="session", autouse=True)
def server_ready() -> None:
    wait_for_server()


@pytest.fixture()
def parent() -> Parent:
    email = f"e2e-{uuid.uuid4().hex}@example.test"
    body = request("POST", "/api/v1/auth/register", expected=201,
                   json={"email": email, "password": PASSWORD})
    created = Parent(email=email, token=body["token"], parent_id=body["parent"]["id"])
    yield created
    try:
        HTTP.post(f"{BASE_URL}/api/v1/me/delete", headers=created.auth(), timeout=10,
                  json={"email": email, "password": PASSWORD, "confirmation": "DELETE"})
    except Exception:
        pass


def register_device(parent: Parent, name: str = "E2E child tablet") -> Device:
    private_key = ec.generate_private_key(ec.SECP256R1())
    public_der = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    device_id = f"e2e-device-{uuid.uuid4().hex}"
    registered = request(
        "POST",
        "/api/v1/device/register",
        expected=201,
        json={
            "device_id": device_id,
            "public_key": base64.b64encode(public_der).decode(),
            "name": name,
            "platform": "android",
        },
    )
    device = Device(device_id=device_id, token=registered["device_token"],
                    parent=parent, private_key=private_key)
    claimed = request("POST", "/api/v1/pairing/claim", token=parent.token,
                      json={"code": registered["pairing_code"]})
    device.row_id = claimed["id"]
    confirmed = request("POST", "/api/v1/device/confirm-pairing", token=device.token)
    assert confirmed == {"paired": True, "confirmed": True}
    return device


def policy(reward_minutes: int = 10) -> dict:
    return {
        "schema": 1,
        "revision": 0,
        "timezone": "Asia/Shanghai",
        "enabled": True,
        "launcher_enabled": False,
        "quiz_gate_enabled": True,
        "reward_minutes": reward_minutes,
        "max_attempts_per_day": 8,
        "cooldown_seconds": 60,
        "budget_warning_minutes": 15,
        "continuous_use_reminder_minutes": 30,
        "quiz_question_count": 1,
        "quiz_subjects": [],
        "quiz_grades": [],
        "quiz_difficulties": [],
        "quiz_languages": [],
        "quiz_tags": [],
        "quiz_triggers": [],
        "help_contacts": [{"label": "Mum", "phone": "+86 138-0000-0000"}],
        "daily_budgets": {"com.example.game": 60},
        "blocked_packages": ["com.example.blocked"],
        "always_allowed_packages": [],
        "schedules": {},
    }


def publish_policy(device: Device, body: dict | None = None) -> dict:
    return request("PUT", f"/api/v1/devices/{device.row_id}/policy",
                   token=device.parent.token, json={"policy": body or policy()})


def sign_device_proof(device: Device, timestamp: int, nonce: str) -> str:
    message = f"guard-device-token-v1\n{device.device_id}\n{timestamp}\n{nonce}".encode()
    signature = device.private_key.sign(message, ec.ECDSA(hashes.SHA256()))
    return base64.b64encode(signature).decode()


def test_health_authentication_and_sessions(parent: Parent) -> None:
    for path in ("/healthz", "/api/v1/health"):
        health = request("GET", path)
        assert health["status"] == "ok"
        assert health["database"] is True

    me = request("GET", "/api/v1/me", token=parent.token)
    assert me["email"] == parent.email

    second = request("POST", "/api/v1/auth/login",
                     json={"email": parent.email, "password": PASSWORD})
    assert second["parent"]["id"] == parent.parent_id
    sessions = request("GET", "/api/v1/me/sessions", token=parent.token)
    assert len(sessions) >= 2
    other = next(row for row in sessions if not row["current"])
    request("POST", f"/api/v1/me/sessions/{other['id']}/revoke", token=parent.token)
    request("GET", "/api/v1/me", token=second["token"], expected=401)
    assert request("GET", "/api/v1/me", token=parent.token)["email"] == parent.email

    request("POST", "/api/v1/auth/login", expected=401,
            json={"email": parent.email, "password": "wrong password"})


def test_pairing_policy_rollback_and_token_renewal(parent: Parent) -> None:
    device = register_device(parent)
    assert request("GET", "/api/v1/devices", token=parent.token)[0]["id"] == device.row_id

    first = publish_policy(device)
    assert first["revision"] == 1
    key = request("GET", "/api/v1/config/policy-key")
    assert key["key_id"] == first["key_id"]
    public_key = serialization.load_der_public_key(base64.b64decode(key["public_key"]))
    public_key.verify(base64.b64decode(first["signature"]), first["payload"].encode(),
                      ec.ECDSA(hashes.SHA256()))
    stored = json.loads(first["payload"])
    assert stored["revision"] == 1
    assert stored["daily_budgets"] == {"com.example.game": 60}

    fetched = request("GET", "/api/v1/device/policy", token=device.token)
    assert fetched["revision"] == 1
    assert fetched["signature"] == first["signature"]
    request("POST", "/api/v1/device/policy-ack", token=device.token,
            expected=204, json={"revision": 1, "verified": True, "errors": []})

    second = publish_policy(device, policy(20))
    assert second["revision"] == 2
    rollback = request("POST", f"/api/v1/devices/{device.row_id}/policy/1/rollback",
                       token=parent.token)
    assert rollback["revision"] == 3
    assert json.loads(rollback["payload"])["reward_minutes"] == 10
    history = request("GET", f"/api/v1/devices/{device.row_id}/policy/history",
                      token=parent.token)
    assert [row["revision"] for row in history] == [3, 2, 1]

    timestamp = int(time.time() * 1000)
    nonce = secrets.token_urlsafe(24)
    renewed = request("POST", "/api/v1/device/token", json={
        "device_id": device.device_id,
        "timestamp": timestamp,
        "nonce": nonce,
        "signature": sign_device_proof(device, timestamp, nonce),
    })
    assert renewed["device_token"]
    request("GET", "/api/v1/device/me", token=renewed["device_token"])
    request("POST", "/api/v1/device/token", expected=401, json={
        "device_id": device.device_id,
        "timestamp": timestamp,
        "nonce": nonce,
        "signature": sign_device_proof(device, timestamp, nonce),
    })


def test_usage_notice_help_manual_quiz_and_audit(parent: Parent) -> None:
    device = register_device(parent)
    publish_policy(device)

    request("POST", "/api/v1/device/usage", token=device.token, expected=204, json={
        "day": date.today().isoformat(),
        "app_minutes": {"com.example.game": 42},
        "health": {"battery": 88},
    })
    usage = request("GET", f"/api/v1/devices/{device.row_id}/usage", token=parent.token)
    assert usage[0]["app_minutes"] == {"com.example.game": 42}
    assert usage[0]["health"] == {"battery": 88}

    notice = request("POST", f"/api/v1/devices/{device.row_id}/notices",
                     token=parent.token, expected=201,
                     json={"kind": "callback", "message": "Please call me"})
    request("POST", f"/api/v1/device/notices/{notice['id']}/ack", token=device.token)

    help_request = request("POST", "/api/v1/device/help-requests", token=device.token,
                           expected=201, json={
                               "client_request_id": f"help-{uuid.uuid4().hex}",
                               "target_package": "com.example.game",
                               "reason": "need_parent",
                               "message": "I need help",
                           })
    request("POST", f"/api/v1/devices/{device.row_id}/help-requests/"
                    f"{help_request['id']}/respond", token=parent.token,
            json={"reply": "I am here"})
    acknowledged = request("POST", f"/api/v1/device/help-requests/{help_request['id']}/ack",
                           token=device.token)
    assert acknowledged["status"] == "acknowledged"

    trigger = request("POST", f"/api/v1/devices/{device.row_id}/quiz-triggers",
                      token=parent.token, expected=201,
                      json={"package_name": "com.example.game", "expires_in_minutes": 10})
    accepted = request("POST", f"/api/v1/device/quiz-triggers/{trigger['id']}/ack",
                       token=device.token, json={"status": "accepted"})
    assert accepted["status"] == "accepted"

    audit = request("GET", f"/api/v1/devices/{device.row_id}/audit", token=parent.token)
    events = {row["event"] for row in audit}
    assert {"policy.published", "notice.acknowledged", "help.responded",
            "quiz.trigger.manual", "quiz.trigger.accepted"} <= events


def test_screenshot_consent_and_call_rejection(parent: Parent) -> None:
    device = register_device(parent)
    publish_policy(device)

    screenshot = request("POST", f"/api/v1/devices/{device.row_id}/call-requests",
                         token=parent.token, expected=201,
                         json={"kind": "screenshot"})
    response = request("POST", f"/api/v1/device/call-requests/{screenshot['id']}/respond",
                       token=device.token, json={"accept": True})
    assert response["status"] == "accepted"
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 32
    uploaded = request("POST", "/api/v1/device/screenshots", token=device.token,
                       expected=201, json={
                           "call_id": screenshot["id"],
                           "image_base64": base64.b64encode(jpeg).decode(),
                           "width": 320,
                           "height": 240,
                       })
    listed = request("GET", f"/api/v1/devices/{device.row_id}/screenshots",
                     token=parent.token)
    assert listed[0]["id"] == uploaded["id"]
    image = request("GET", f"/api/v1/screenshots/{uploaded['id']}", token=parent.token)
    assert image.content.startswith(b"\xff\xd8\xff")
    assert image.headers["cache-control"] == "private, no-store"

    rejected = request("POST", f"/api/v1/devices/{device.row_id}/call-requests",
                       token=parent.token, expected=201, json={"kind": "audio"})
    response = request("POST", f"/api/v1/device/call-requests/{rejected['id']}/respond",
                       token=device.token, json={"accept": False})
    assert response["status"] == "rejected"


def test_account_deletion_and_tenant_isolation() -> None:
    first = request("POST", "/api/v1/auth/register", expected=201, json={
        "email": f"e2e-{uuid.uuid4().hex}@example.test", "password": PASSWORD})
    owner = Parent(email=first["parent"]["email"], token=first["token"],
                   parent_id=first["parent"]["id"])
    device = register_device(owner)
    publish_policy(device)

    other = request("POST", "/api/v1/auth/register", expected=201, json={
        "email": f"e2e-{uuid.uuid4().hex}@example.test", "password": PASSWORD})
    outsider = Parent(email=other["parent"]["email"], token=other["token"],
                      parent_id=other["parent"]["id"])
    assert request("GET", "/api/v1/devices", token=outsider.token) == []
    request("GET", f"/api/v1/devices/{device.row_id}", token=outsider.token, expected=404)
    request("GET", f"/api/v1/devices/{device.row_id}/policy", token=outsider.token,
            expected=404)

    request("POST", "/api/v1/me/delete", token=owner.token, expected=204, json={
        "email": owner.email, "password": PASSWORD, "confirmation": "DELETE"})
    request("GET", "/api/v1/me", token=owner.token, expected=401)
    request("POST", "/api/v1/auth/login", expected=401,
            json={"email": owner.email, "password": PASSWORD})
    request("GET", "/api/v1/device/me", token=device.token, expected=401)

    request("POST", "/api/v1/me/delete", token=outsider.token, expected=204, json={
        "email": outsider.email, "password": PASSWORD, "confirmation": "DELETE"})
