# Public access with Cloudflare Tunnel

The app has [cloudflared](https://github.com/cloudflare/cloudflared) built in. When the tunnel is on,
the phone connects out to Cloudflare, and the API becomes reachable at a public HTTPS URL. You don't
need to open ports, set up port forwarding, or have a public IP, so it also works on mobile data.

> Anyone who knows the URL can reach the API. Requests still need an API key, so keep your keys
> secret, and revoke any key you think has leaked. See [Security](security.md).

## What you need

A free Cloudflare account and a domain on Cloudflare. The tunnel gets your own hostname, such as
`https://sms.example.com`, which never changes.

> **Quick tunnels are gone.** Earlier versions could also start a tunnel with no account, at a
> random `trycloudflare.com` URL. That made it too easy to put the gateway on the internet without
> meaning to, so the option has been removed. If you were using one, the app turned the tunnel off
> when it updated and says so on the **Status** tab. Set up your own tunnel below to get public
> access back.

## Set up your own tunnel

1. In the [Cloudflare dashboard](https://one.dash.cloudflare.com/), open **Zero Trust > Networks > Tunnels**
   and create a tunnel of type **Cloudflared**.
2. On the install step, copy the token. It's the long string after `--token` in any of the install
   commands. You don't need to install anything.
3. Add a **public hostname**, for example `sms.example.com`, with service type **HTTP** and URL
   `localhost:8080` (use the port shown in the app).
4. In the app, open **Settings → Public access** and tap **Edit**. Turn on **Enable tunnel**, paste
   the token, optionally type the hostname so the app can show it, and tap **Save**. The app asks
   you to confirm before it makes the API public.
5. The tunnel starts whenever the server is running, and shows **Connected** once it's up. The
   public URL is also listed on the **Status** tab.

```sh
curl https://sms.example.com/api/v1/sims -H "X-API-Key: sgw_your_key"
```

The token is stored encrypted on the phone, like the other secrets. To use a different tunnel,
paste a new token. **Remove token** deletes it.

## Tunnel only

If the tunnel is the only way you reach the gateway, set **Settings → Server → Who can connect** to
**Tunnel only**. The server then listens on the phone itself and nothing else, so no device on
Wi-Fi or mobile data can connect to it directly. See [Who can connect](security.md#who-can-connect).

**Tunnel only** can only be picked while the tunnel is turned on and has a token, because without
the tunnel nothing could reach the gateway at all. For the same reason, turning the tunnel off or
removing its token while in **Tunnel only** asks first, then switches **Who can connect** to
**Local network** and restarts the server. Devices on the same network can then connect again
(they still need an API key), so cancel if that isn't what you want.

Give the key you use over the tunnel only the [scopes](api.md#scopes) it needs. A public URL is
the place where a leaked key costs the most.

## Reconnecting

The tunnel is meant to stay up unattended:

- **When the network comes back or changes**, for example Wi-Fi returning or the phone switching
  between Wi-Fi and mobile data, the app restarts cloudflared right away instead of waiting for
  cloudflared's own retry timer. This usually takes a few seconds.
- **If cloudflared stops or crashes**, the app starts it again after 2s, then 4s, 8s, and so on,
  up to one minute between tries.
- **With no network at all**, it waits and reconnects as soon as Wi-Fi or mobile data is back.

When the screen has been off for a while, Android's battery saving can cut the app off from the
network. Tap **Allow running in background** on the **Status** tab, and see
[Troubleshooting](troubleshooting.md) for phone brands with extra battery savers.

## Good to know

- **Phones:** cloudflared is included for ARM phones (`arm64-v8a` and `armeabi-v7a`), which covers
  nearly every Android phone. On other processors the section says it isn't available.
- **APK size:** cloudflared adds about 20 MB to the APK download, and about 30 MB once installed.
- **Battery and data:** the tunnel keeps a few connections to Cloudflare open, which uses a little
  more battery and some background data.
- **Log:** tap **Show** on the **Log** card under **Settings → Public access** to see what cloudflared is doing. It's useful
  when a token is rejected or the tunnel won't connect.
