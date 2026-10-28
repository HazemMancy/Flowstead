# Running the ThermoDock engine

The engine is a Docker container: an ASP.NET Core API in front of a pool of DWSIM 9.0.5 worker processes. The phone only talks HTTPS. Caddy, in `deploy/`, terminates TLS.

## VPS with a domain name

```bash
git clone --recurse-submodules https://github.com/HazemMancy/ThermoDock && cd ThermoDock/deploy
THERMODOCK_DOMAIN=engine.example.com docker compose up -d --build
docker compose exec engine dotnet /app/ThermoDock.Engine.Api.dll keys add my-phone
```

Point the domain's A record at the VPS first, so Caddy can get a Let's Encrypt certificate. In the app, open **Settings → Engine**:
- **Address:** `https://engine.example.com`
- **API key:** the printed key, which is shown only once.

Tap **Test connection**.

## PC on the same Wi-Fi

```bash
THERMODOCK_DOMAIN=192.168.1.20 docker compose up -d --build
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./thermodock-root.crt
```

Copy `thermodock-root.crt` to the phone and install it under **Settings → Security → Encryption & credentials → Install a certificate → CA certificate**. ThermoDock trusts user-installed CAs, and only for HTTPS (decision D16).

## Keys

```bash
docker compose exec engine dotnet /app/ThermoDock.Engine.Api.dll keys list
docker compose exec engine dotnet /app/ThermoDock.Engine.Api.dll keys revoke my-phone
```

Only SHA-256 hashes of keys are stored. Each key is limited to 120 requests per minute.

## Tuning (environment variables on the `engine` service)

| Variable | Default | Meaning |
|---|---|---|
| `Engine__Workers` | half the CPU cores, 1–4 | DWSIM worker processes, i.e. solves that can run at once |
| `Engine__SolveTimeoutSeconds` | 60 | A longer solve is stopped and its worker restarted |

Each worker uses about 300 MB of RAM and takes about 3 s to load the compound database at start-up.
