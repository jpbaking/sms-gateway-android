# Companion app (Gateway SMS)

**Gateway SMS** is a small messaging app for *another* phone. It shows the gateway's texts as
conversations, like a normal SMS app, and sends and replies through the gateway. It lives in
[`companion/`](../companion) and installs next to the gateway app as its own app.

It only ever shows the gateway's messages. It doesn't read, send or mix in the SMS of the phone it
runs on.

## What it does

- **Conversations** with each number, newest first, with unread counts. Names and photos come from
  the phone's contacts when the number is saved there (it asks for the contacts permission; without
  it, numbers are shown).
- **Threads** with sent and received bubbles, day separators, and each sent message's status
  (`Sending…`, `Sent`, `Delivered`, or `Not sent`, tap to retry). Long-press a message to copy it.
- **Reply and start new chats**: pick a contact, a recent conversation, or type a number.
- **SIM picked for you**: a reply goes out on the SIM the newest *received* message in that
  conversation came in on, so the other side sees the reply from the number they texted. Without a
  received message it uses the SIM last sent from, then the gateway's default SMS SIM. Tap the SIM
  chip above the text box to choose another one for that conversation.
- **Live updates while it's open** through the [event stream](events.md). There is no background
  service: nothing is connected while the app is closed, and when it's opened again the stream
  replays what was missed (`Last-Event-ID`), or the app reloads. If the gateway's event stream is
  off, the app refreshes every 15 seconds while it's open instead.

## Set up

1. On the gateway phone, create an API key for this phone, e.g. `my-phone`
   (**Settings → API keys → New key**). Tick **Send messages**, **Read all messages** and, for
   live updates, **Follow the event stream**. Leave the other permissions off: the app doesn't use
   them.
2. For live updates, turn on **Settings → Event stream** on the gateway.
3. Install the companion APK on the other phone (Android 9 or newer). CI builds it on every pull
   request as the `gateway-sms-companion-debug-apk` artifact.
4. Enter the gateway URL and the API key, and tap **Save**. The app checks both before saving.

| Gateway URL | When |
| --- | --- |
| `http://192.168.1.50:8080` | Both phones on the same Wi-Fi, plain HTTP |
| `https://192.168.1.50:8443` | Same Wi-Fi, gateway [serving HTTPS](https.md) |
| `https://sms.example.com` | Through the [Cloudflare Tunnel](tunnel.md), from anywhere |

Typing just `192.168.1.50:8080` or `sms.example.com` works too: an IP address gets `http://`,
a host name gets `https://`.

With an `http://` address the app shows a warning: the API key and your messages cross the network
unencrypted, so anyone on the same Wi-Fi could read them. Use it on a network you trust, or turn on
[HTTPS](https.md) on the gateway.

The gateway's default **Local network** mode only accepts connections from the same network. To use
the app from elsewhere, connect through the [Cloudflare Tunnel](tunnel.md) or a VPN. See
[Who can connect](security.md#who-can-connect).

## Self-signed certificates

A gateway serving HTTPS with its own self-signed certificate isn't trusted by Android. When you tap
**Save**, the app shows that certificate's SHA-256 fingerprint. Compare it with the one in the
gateway app under **Settings → Server → Certificate**, and tap **Trust** only if they match. You can
also paste the fingerprint into **Trusted fingerprint** yourself.

The app then trusts exactly that certificate (it's pinned), whatever IP address or host name it's
reached by, plus anything Android trusts anyway, such as the tunnel's certificate. If the gateway
gets a new certificate, Save shows the new fingerprint to compare again.

## Good to know

- The API key is stored encrypted with the Android Keystore, and is left out of backups and of the
  copy Android makes when you move to a new phone. Enter it again there.
- The app never follows a redirect. If the address you entered answers with one, the app says so
  and sends the API key nowhere else. Enter the gateway's own URL.
- Without the **Follow the event stream** permission the key can't open the stream, and the app
  refreshes every 15 seconds while it's open instead.
- The app loads the newest 500 messages, and **Load older messages** fetches more.
- Messages the app sends are recorded on the gateway under its API key's name.
