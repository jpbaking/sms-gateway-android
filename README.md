# SMS Gateway for Android

Turns an Android phone (Android 9 or newer) into an SMS gateway. Other systems send and read SMS through
an HTTP API served by the phone itself, and get webhooks for incoming messages and delivery status.
Dual-SIM and multi-SIM phones are supported: you can choose the SIM per message, and every message
records which SIM it used.

Built with Kotlin, Jetpack Compose, Ktor (CIO server), Room and WorkManager. Distributed as an APK
through [GitHub Releases](../../releases), not the Play Store.

## Setup on the phone

1. Install the APK from Releases (allow installs from unknown sources).
2. Open the app and tap **Grant permissions** (SMS, phone state, notifications).
3. Tap **Allow running in background** so battery optimization doesn't pause the server.
4. Tap **Start**. The app shows the URL to use, e.g. `http://192.168.1.50:8080`.
5. Under **API keys**, tap **New key** and give it a name (e.g. `billing-app`). Copy the key: it's
   shown only once.
6. Optional: turn on **Start on boot**.

The phone and the system calling it need to be on the same network (or reach the phone through a VPN
such as Tailscale). Cloud relay mode is planned for later.

## API

Every `/api/v1` call needs an API key, as `X-API-Key: <key>` or `Authorization: Bearer <key>`.

Create one named key per app or user in the app's **API keys** card. The phone keeps only a hash of
each key, so the key is shown once when it's created or regenerated. After that the list shows its
name, its first characters (`sgw_1a2b3c4d…`) and when it was last used. **Regenerate** replaces
a key's value under the same name. **Revoke** deletes it. Both take effect immediately.

Every outgoing message records the key that sent it, as `apiKeyId` and `apiKeyName` in the API
and in webhook payloads. A webhook receiver can use that to recognise status events for its own
sends. Incoming SMS go to every webhook.

| Method | Path | What it does |
| --- | --- | --- |
| `GET` | `/health` | Liveness check, no key needed |
| `GET` | `/api/v1/sims` | Active SIMs with `slot`, `subscriptionId`, carrier, default flag |
| `POST` | `/api/v1/messages` | Send an SMS |
| `GET` | `/api/v1/messages?direction=in\|out&limit=50&offset=0` | List messages, newest first |
| `GET` | `/api/v1/messages/{id}` | One message and its status |
| `GET` | `/api/v1/webhooks` | List webhooks |
| `POST` | `/api/v1/webhooks` | Add a webhook |
| `DELETE` | `/api/v1/webhooks/{id}` | Remove a webhook |

### Send

```sh
curl -X POST http://PHONE:8080/api/v1/messages \
  -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"to": "+15551234567", "body": "Hello", "simSlot": 1}'
```

`simSlot` is zero-based (`0` = SIM 1, `1` = SIM 2). You can pass `subscriptionId` from `/sims`
instead. With neither, the phone's default SMS SIM is used. Long messages are split automatically.

The response is `202 Accepted` with the message. Its `status` then moves through
`queued` → `sending` → `sent` → `delivered`, or ends at `failed` with an `error`.
(`delivered` needs the carrier to send delivery reports, which not all do.)

### Webhooks

```sh
curl -X POST http://PHONE:8080/api/v1/webhooks \
  -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{
    "url": "https://example.com/sms",
    "events": ["sms:received", "sms:delivered"],
    "headers": [
      {"key": "Authorization", "value": "Bearer XXXXX", "isSecret": true},
      {"key": "X-Tenant", "value": "acme"}
    ]
  }'
```

`headers` is optional. Each header is sent with every delivery. `isSecret` defaults to `false`.
Secret header values are stored encrypted and shown as `********` when you list webhooks.
`Content-Type`, `Content-Length`, `Host`, `Connection`, `Transfer-Encoding` and `X-Gateway-*`
are set by the gateway and can't be overridden. You can override `User-Agent`.

Events: `sms:received`, `sms:sent`, `sms:delivered`, `sms:failed` (default: all). The create
response includes the signing `secret` (generated unless you pass one). Save it: later calls to
`GET /api/v1/webhooks` don't return it. Each delivery is a `POST` with this body:

```json
{
  "id": "event uuid",
  "event": "sms:received",
  "timestamp": 1762726800000,
  "message": { "id": "...", "direction": "in", "phoneNumber": "+15551234567", "body": "Hi",
               "simSlot": 0, "subscriptionId": 1, "status": "received", "...": "..." }
}
```

Headers: `X-Gateway-Event`, `X-Gateway-Timestamp` (Unix seconds) and
`X-Gateway-Signature: sha256=<hex>`, the HMAC-SHA256 of `"<timestamp>.<raw body>"` keyed with the
webhook's secret.

**Retries:** if your server is down (network error, timeout, 5xx, 408 or 429), the phone retries
after 10s, 20s, 40s, ... and then every 5 minutes, forever by default. So delivery resumes within
about 5 minutes of your server coming back. Both the interval and an optional time limit are set
on the app's **Webhook retries** card. While the phone itself is offline, retries wait for the
connection without counting. Other 4xx responses are not retried, and deleting a webhook stops its
pending retries. Every message is also stored on the phone, so `GET /api/v1/messages` can catch up
on anything a webhook missed.

### How secrets are stored

API keys are stored only as SHA-256 hashes. Webhook signing secrets and secret header values are
encrypted with an AES-256-GCM key held in the Android Keystore. The key can't be exported from the phone, so a copy of the app's data
files doesn't reveal them. App backups are turned off as well.

## Releases

Pushing a tag builds a signed APK and publishes it on the Releases page:

- `v0.1.0-dev.1`, `v0.1.0-alpha.1`, `v0.1.0-beta.1`, ... are published as **pre-releases**
- `v1.0.0` (no suffix) is published as a full release

```sh
git tag v0.1.0-dev.1 && git push origin v0.1.0-dev.1
```

### One-time signing setup

Android only installs an update over an existing install when both are signed with the same key,
so every release must use the same keystore. Create it once and keep a backup somewhere safe:

```sh
keytool -genkeypair -v -keystore release.jks -alias smsgateway \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then add these repository secrets (Settings → Secrets and variables → Actions), or use `gh`:

```sh
base64 -w0 release.jks | gh secret set SIGNING_KEYSTORE_BASE64   # macOS: base64 -i release.jks
gh secret set SIGNING_KEYSTORE_PASSWORD
gh secret set SIGNING_KEY_ALIAS      # smsgateway
gh secret set SIGNING_KEY_PASSWORD   # same as the keystore password unless you chose another
```

## Building locally

Needs JDK 17 and the Android SDK.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
```

Debug builds install as a separate app (`io.github.jpbaking.smsgateway.debug`), so they can sit
next to a release build.
