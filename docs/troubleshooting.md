# Troubleshooting

## The install is blocked

Android and Google Play Protect are cautious about sideloaded apps that use SMS.

| What you see | What to do |
| --- | --- |
| "Blocked by Play Protect" | Tap **More details** → **Install anyway** |
| Blocked, with no install option | Play Store → profile → **Play Protect** → ⚙️ → turn off **Scan apps**, install, turn it back on. Or use `adb install` over USB |
| "Blocked to protect you from fraud" | Install with `adb install sms-gateway-….apk` over USB |
| A permission is greyed out: "Restricted setting" | App info → ⋮ → **Allow restricted settings**, then grant it |
| "App not installed" when updating | The new APK is signed with a different key. Debug builds and release builds can't update each other. Uninstall first, which deletes the app's data |

## I can't reach the server

1. Is the notification "SMS gateway running" showing? If not, open the app and tap **Start server**.
2. Use the exact address shown in the app. The phone's IP changes when it joins another network.
3. Are both devices on the same network? Guest Wi-Fi and "AP isolation" often block devices from
   reaching each other.
4. Try `curl http://<phone-ip>:8080/health` from the computer. If that times out, it's a network
   problem, not an API key problem.
5. "Could not start on port …" in the app means another app is using that port. Pick another one.
6. With [HTTPS](https.md) on, other devices can only use `https://` and the HTTPS port (8443 by
   default), unless you allowed plain HTTP too.

## HTTPS: "certificate verify failed" or "unable to get local issuer certificate"

The client doesn't trust the certificate. For a self-signed one, give the client the certificate
(`curl --cacert gateway.pem …`); see [HTTPS → Self-signed certificate](https.md#self-signed-certificate).
For "hostname mismatch" or "doesn't match", connect using a name or IP the certificate lists (shown
in the app), or generate a new self-signed certificate after the phone's IP changed.

## It stops working when the screen is off

- Tap **Allow running in background** on the app's **Status** tab.
- On Xiaomi, Huawei, Oppo, Vivo and Samsung, also look in the battery settings for SMS Gateway and
  set it to **Unrestricted**, or turn on **Autostart**.
- Don't use **Force stop** on the app (App info → Force stop). Android won't run its background work until you open it
  again.

## Every request returns 401

- Send the key as `X-API-Key: <key>` or `Authorization: Bearer <key>`.
- The key may have been regenerated or revoked. Check the **API keys** list in the app.
- A fresh install has no keys. Create one first.

## Messages stay at `sending` or end as `failed`

- `failed` with "no service" or "radio off": the SIM has no signal, or airplane mode is on.
- `failed` with "generic failure": often no credit, or the carrier refused the message.
- Check that the SIM can send SMS with the phone's normal Messages app.
- Pick a SIM explicitly with `simSlot` to rule out the wrong default SIM.

## Messages stay at `sent` and never become `delivered`

Delivery reports depend on the carrier, and some never send them. `sent` means the network
accepted the message.

## Webhooks aren't arriving

- Your server must be reachable **from the phone**. Open the webhook URL in the phone's browser to check.
- Look at what your server returns. Anything `4xx` except `408` and `429` makes the phone give up
  on that event.
- Failed calls are retried. With the default settings they come within about 5 minutes once your
  server is back. See [Retries](webhooks.md#retries).
- `GET /api/v1/messages?direction=in` lists everything received, whatever happened to the webhooks.

## The database is getting big

In **Settings → Storage**, tap **Edit**, lower **Keep messages for**, tap **Save**, then tap **Clean up now**. The default is 30 days.
