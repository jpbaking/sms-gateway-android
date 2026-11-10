# Public access with Cloudflare Tunnel

The app has [cloudflared](https://github.com/cloudflare/cloudflared) built in. When the tunnel is on,
the phone connects out to Cloudflare, and the API becomes reachable at a public HTTPS URL. You don't
need to open ports, set up port forwarding, or have a public IP, so it also works on mobile data.

> Anyone who knows the URL can reach the API. Requests still need an API key, so keep your keys
> secret, and revoke any key you think has leaked. See [Security](security.md).

## Two modes

| Mode | Needs | URL |
| --- | --- | --- |
| **My Cloudflare tunnel** | A free Cloudflare account and a domain on Cloudflare | Your own hostname, such as `https://sms.example.com`. It never changes. |
| **Quick tunnel** | Nothing | A random `https://….trycloudflare.com` URL. It changes every time the tunnel starts. For testing only: Cloudflare gives no uptime guarantee. |

## Set up your own tunnel

1. In the [Cloudflare dashboard](https://one.dash.cloudflare.com/), open **Zero Trust > Networks > Tunnels**
   and create a tunnel of type **Cloudflared**.
2. On the install step, copy the token. It's the long string after `--token` in any of the install
   commands. You don't need to install anything.
3. Add a **public hostname**, for example `sms.example.com`, with service type **HTTP** and URL
   `localhost:8080` (use the port shown in the app).
4. In the app, under **Public access (Cloudflare Tunnel)**, choose **My Cloudflare tunnel**, paste the
   token, optionally type the hostname so the app can show it, and tap **Save**.
5. Turn on **Enable tunnel**. The tunnel starts whenever the server is running, and shows
   **Connected** once it's up.

```sh
curl https://sms.example.com/api/v1/sims -H "X-API-Key: sgw_your_key"
```

The token is stored encrypted on the phone, like the other secrets. To use a different tunnel,
paste a new token. **Remove token** deletes it.

## Reconnecting

The tunnel is meant to stay up unattended:

- **When the network comes back or changes**, for example Wi-Fi returning or the phone switching
  between Wi-Fi and mobile data, the app restarts cloudflared right away instead of waiting for
  cloudflared's own retry timer. With your own tunnel this usually takes a few seconds. A quick
  tunnel is first given 15 seconds to reconnect by itself, because a restart gives it a new URL.
- **If cloudflared stops or crashes**, the app starts it again after 2s, then 4s, 8s, and so on,
  up to one minute between tries.
- **With no network at all**, it waits and reconnects as soon as Wi-Fi or mobile data is back.

When the screen has been off for a while, Android's battery saving can cut the app off from the
network. Tap **Allow running in background** in the Server section, and see
[Troubleshooting](troubleshooting.md) for phone brands with extra battery savers.

## Good to know

- **Phones:** cloudflared is included for ARM phones (`arm64-v8a` and `armeabi-v7a`), which covers
  nearly every Android phone. On other processors the section says it isn't available.
- **APK size:** cloudflared adds about 20 MB to the APK download, and about 30 MB once installed.
- **Battery and data:** the tunnel keeps a few connections to Cloudflare open, which uses a little
  more battery and some background data.
- **Log:** tap **Show log** in the tunnel section to see what cloudflared is doing. It's useful
  when a token is rejected or the tunnel won't connect.
