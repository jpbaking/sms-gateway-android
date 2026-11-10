# HTTPS

The gateway can serve its API over HTTPS, so API keys and message text are encrypted on the network.
In the app, open **Settings → Server**. Under **Certificate**, tap **Set up** and pick one of three ways
to get a certificate. Then tap **Edit** on the **Server** card, turn on **Serve HTTPS**, pick a port
(default `8443`), and tap **Save**:

| Option | Use it when |
| --- | --- |
| **Self-signed** | You control the clients and can tell them to trust (or pin) this one certificate. |
| **Paste certificate and key** | You have PEM files, for example from Let's Encrypt or your own CA. |
| **PKCS#12 file** | You have a `.p12` / `.pfx` bundle and its password. |

If the server is running when you save a change (or replace the certificate), the app asks before it
restarts the server to apply it.

## Plain HTTP when HTTPS is on

By default, turning HTTPS on makes plain HTTP answer **only on the phone itself** (`127.0.0.1`), so
other devices must use `https://`. The [Cloudflare Tunnel](tunnel.md) keeps working because it
connects to the phone locally. Turn on **Also allow plain HTTP from other devices** if some
clients can't do HTTPS yet.

| Setting | Other devices | The tunnel |
| --- | --- | --- |
| HTTPS off | `http://PHONE_IP:8080` | works |
| HTTPS on (default) | `https://PHONE_IP:8443` only | works |
| HTTPS on, plain HTTP allowed | both | works |

With **Who can connect** set to **Tunnel only**, HTTPS isn't served at all: no other device can
connect, and Cloudflare already encrypts the tunnel. **Also allow plain HTTP from other devices**
is locked off there too; to let other devices in, set **Who can connect** to at least
**Local network**. In **Local network** mode the HTTPS port
follows the same rules as plain HTTP, and refuses other callers before the TLS handshake. See
[Who can connect](security.md#who-can-connect).

## Self-signed certificate

The app makes an ECDSA P-256 key and a certificate valid for 825 days, for the host names and IP
addresses you list (it suggests `localhost` and the phone's current IP addresses). Clients must
connect using one of those names, so if the phone's IP changes, generate a new certificate, or give
the phone a fixed IP address in your router.

Because no public CA signed it, clients reject it until you tell them to trust it. Copy the
certificate from your own trusted place, not over the network you're trying to secure. The app shows
the **SHA-256 fingerprint** so you can check you have the right one.

```bash
# Fetch the certificate once, then compare its fingerprint with the one in the app.
openssl s_client -connect 192.168.1.50:8443 </dev/null 2>/dev/null | openssl x509 > gateway.pem
openssl x509 -in gateway.pem -noout -fingerprint -sha256

# Then trust only that certificate.
curl --cacert gateway.pem https://192.168.1.50:8443/health
```

Avoid `curl -k` / "skip verification" outside quick tests: it accepts anyone's certificate, which
removes most of the point of HTTPS.

## Your own certificate (PEM)

Paste the certificate, followed by any intermediate certificates, into the first box, and the
private key into the second. Accepted key formats: `BEGIN PRIVATE KEY` (PKCS#8),
`BEGIN RSA PRIVATE KEY` and `BEGIN EC PRIVATE KEY`, with RSA or ECDSA keys. Password-protected keys
aren't accepted; remove the password first or use a PKCS#12 file:

```bash
openssl pkey -in encrypted-key.pem -out key.pem
```

For Let's Encrypt, paste `fullchain.pem` and `privkey.pem`. The certificate must be for a name your
clients use to reach the phone, for example a DNS name on your LAN or VPN that points at it.
Certificates don't renew by themselves here: paste the new one before the old one expires (the app
shows the expiry date).

## PKCS#12 file

Choose the `.p12` / `.pfx` file and enter its password. The first private key in the file and its
certificate chain are used. If Android can't open a file made by OpenSSL 3, re-export it with the
older algorithms:

```bash
openssl pkcs12 -export -legacy -inkey key.pem -in fullchain.pem -out gateway.p12
```

## How it's stored

The certificate is stored as-is (it's public). The private key is encrypted with a key held in the
Android Keystore, like the API keys and webhook secrets (see [Security](security.md)). The PKCS#12
password is only used to open the file and isn't stored.
