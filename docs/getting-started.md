# Getting started

You need an Android phone running **Android 9 or newer** with at least one active SIM, and a computer
or server on the same network as the phone.

```mermaid
flowchart LR
    A[Install APK] --> B[Grant permissions] --> C[Allow background] --> D[Start server] --> E[Create API key] --> F[Send a test SMS]
```

## 1. Install the APK

1. On the phone, open the [Releases page](https://github.com/jpbaking/sms-gateway-android/releases) and download the latest `sms-gateway-*.apk`.
2. Open it and allow installs from your browser or file manager when Android asks.

Google Play Protect is wary of any sideloaded app that uses SMS, so you may see a warning:

- **"Blocked by Play Protect"**: tap **More details**, then **Install anyway**.
- **No install option at all**: open Play Store → your profile → **Play Protect** → ⚙️, turn off
  **Scan apps with Play Protect**, install, then turn it back on. Installing over USB with
  `adb install sms-gateway-….apk` also works.

See [Troubleshooting](troubleshooting.md#the-install-is-blocked) for other messages.

## 2. Grant permissions

Open **SMS Gateway** and tap **Grant permissions**. The app needs:

| Permission | Why |
| --- | --- |
| SMS (send and receive) | To send messages and to see incoming ones |
| Phone | To list your SIM cards and choose which one sends |
| Notifications (Android 13+) | For the "gateway running" notification that keeps the server alive |

If a permission toggle is greyed out with **Restricted setting**, open App info → ⋮ → **Allow
restricted settings**, then grant it.

## 3. Let it run in the background

Tap **Allow running in background**. Without this, battery optimization can pause the server
when the screen is off. Some brands (Xiaomi, Huawei, Oppo, Samsung) have their own extra battery
settings too. Look for "autostart" or "unrestricted battery" for SMS Gateway.

## 4. Start the server

Tap **Start**. A notification appears and the app shows the address to use, for example
`http://192.168.1.50:8080`. Turn on **Start on boot** if the phone should come back up by itself after
a restart.

Check it from your computer:

```sh
curl http://192.168.1.50:8080/health
# {"status":"ok","version":"0.1.0-beta.1"}
```

## 5. Create an API key

Under **API keys**, tap **New key** and give it a name that says who will use it, such as
`billing-app`. Copy the key right away: **it's shown only once**. If you lose it, tap
**Regenerate** to get a new one.

Make one key per app or person. Every message you send records the name of the key that sent it.

## 6. Send your first SMS

```sh
GW=http://192.168.1.50:8080
KEY=sgw_your_key_here

curl -X POST $GW/api/v1/messages \
  -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"to": "+15551234567", "body": "Hello from my SMS gateway"}'
```

The reply contains the message `id` and `"status": "queued"`. Check on it:

```sh
curl -H "X-API-Key: $KEY" $GW/api/v1/messages/MESSAGE_ID
```

The status moves to `sent`, and to `delivered` if your carrier sends delivery reports.

## Next steps

- Get incoming SMS pushed to your server: [Webhooks](webhooks.md)
- Pick which SIM sends: [Dual-SIM and multi-SIM](multi-sim.md)
- See everything the API can do: [API reference](api.md)
