# ErgoPay Payment Portal

A Spring Boot service that turns any dApp backend into an ErgoPay endpoint. It lets you:

- Request ERG/token payments and watch their lifecycle.
- Prompt wallets to share the current P2PK address.
- Build + host NFT mint transactions (full EIP-4 metadata).
- Register prebuilt or unsigned smart‑contract transactions and deliver them through ErgoPay.

Use the public deployment at **https://ergopay.duckdns.org** or self-host the service for full control.

---

## Running the service

### Requirements

- JDK 11+ (tested on Temurin 21)
- Access to an Ergo node (configured through `ERGO_NODE_API_URL` and `ERGO_NODE_API_KEY`).
- (Optional) An Explorer API URL if you want to override the defaults in `ExplorerApiService`.

### Local development

```bash
./gradlew bootRun           # Mac/Linux
# or
gradlew bootRun             # Windows
```

By default an embedded PostgreSQL + in-memory cache is used. Data is purged on restart, so adjust
`EmbeddedPostgresConfiguration`/`application.properties` for production deployments.

### Deploying

Any JVM platform (Heroku, Dokku, bare metal) works. Package with `./gradlew bootJar` and run the JAR
with your preferred process manager. Consider pointing the node/explorer URLs to private instances
and wiring an external database if you need persistence beyond the default TTL windows.

---

## Code layout

- `model/` – JPA entities (`PaymentRequest`, ...).
- `repository/` – Spring Data repositories.
- `rest/` – REST controllers + DTOs.
- `service/` – Business logic (payment creation, NFT minting, reduced Tx handling, node/explorer calls).
- `config/` – Embedded Postgres bootstrap & other wiring.

If you only care about the blockchain bits, start with:
- `PaymentService.kt` (classic payments)
- `NftMintService.kt`
- `ReducedTxService.kt`

---

## API overview

| Flow | Endpoint | Description |
| ---- | -------- | ----------- |
| Address prompt | `GET /payment/auth` | Returns an `ErgoPayResponse` with `address="#P2PK_ADDRESS#"`. Wallet replaces the placeholder and (optionally) calls your `replyTo`. |
| Payment request | `POST /payment/addrequest` | Creates a payment intent and responds with an ErgoPay URL pointing at `/payment/getrequest/{id}`. |
| Payment state | `GET /payment/state/{requestId}` | Polls the lifecycle (`CREATED/WAITING/EXECUTED/INVALID`). |
| NFT mint | `POST /api/nft-mint` | Prepares a mint transaction, stores the reduced tx for 10 minutes, returns the ErgoPay link `/tx/{txId}`. |
| NFT tx fetch | `GET /tx/{txId}` | Delivers the reduced tx + callback URL to wallets. |
| Signed NFT callback | `POST /callback/{txId}` | Optional hook for wallets to POST the broadcast tx id. |
| Reduced transaction | `POST /api/v1/reducedTx` | Register a reduced transaction (prebuilt or unsigned). Returns `/payment/sign/{requestId}` ErgoPay link. |
| Reduced tx fetch | `GET /payment/sign/{requestId}` | Wallets retrieve the reduced tx + message/replyTo. |

All responses follow Spring conventions: non-2xx replies include `{ "message": "..." }` in the body.

---

## Flow details

### 1. Prompt a wallet for its address

```
GET https://ergopay.duckdns.org/payment/auth?message=Please%20sign%20in&replyTo=https://your-app.example/callback
```

Show the returned link as `ergopay://.../payment/auth?...`. The wallet replaces `#P2PK_ADDRESS#` and
sends it to `replyTo` (if specified) or just displays it to the user.

### 2. Request a payment

`POST /payment/addrequest`

```json
{
  "nanoErg": 1000000000,
  "receiverAddress": "9g8gaARC3N8j9v97wmnFkhDMxHHFh9PEzVUtL51FGSNwTbYEnnk",
  "senderAddress": "9f3WhP3ULg7ngTwyxT4gcVxwYeGvKTBjUAugwcduxXWBaxEzp47",
  "tokenId": null,
  "tokenRawAmount": null,
  "message": "Invoice #42"
}
```

Response:
```json
{
  "requestId": "MSWHPMIDDZ",
  "ergoPayUrl": "ergopay://ergopay.duckdns.org/payment/getrequest/MSWHPMIDDZ?sender=#P2PK_ADDRESS#"
}
```

- Present `ergoPayUrl` as a QR/button.
- Wallet loads `/payment/getrequest/{id}` and receives the `ErgoPayResponse` with the reduced tx.
- Poll `GET /payment/state/{id}` for status updates until you see `EXECUTED` (includes `txId`). Requests are purged after ~30 minutes.

### 3. Mint an NFT via ErgoPay

`POST /api/nft-mint`

```json
{
  "address": "9f3WhP3ULg...",
  "imageUrl": "https://ipfs.io/ipfs/Qm...",
  "imageHash": "b3f0...",              // optional SHA-256 hex (preferred)
  "name": "Meme Collection #123",
  "description": "A unique meme NFT",
  "collectionName": "Meme Collection",
  "attributes": [{ "trait_type": "Style", "value": "Bullish" }]
}
```

Response contains `{ success, txId, tokenId, ergoPayUrl }`. The link points to `/tx/{txId}` where the wallet fetches the reduced transaction (`replyTo` is `/callback/{txId}`). Reduced txs live for 10 minutes.

### 4. Register a reduced transaction (smart-contract/token purchases)

`POST /api/v1/reducedTx`

Two options:

1. **Prebuilt reduced transaction** – send the Base64 string directly:
   ```json
   {
     "address": "9f3WhP3ULg...",
     "reducedTx": "6CYS...=",
     "message": "Purchase 1 token",
     "messageSeverity": "INFORMATION",
     "replyTo": "https://our.app/callback"
   }
   ```

2. **Unsigned transaction draft** – let the portal rebuild/reduce it:
   ```json
   {
     "address": "9f3WhP3ULg...",
     "message": "Purchase 1 token for 0.1 ERG",
     "messageSeverity": "INFORMATION",
     "unsignedTx": {
       "creationHeight": 123456,
       "fee": 1100000,
       "changeAddress": "9f3WhP3ULg...",
       "inputs": [ { "boxId": "1a2b..." } ],
       "dataInputs": [ { "boxId": "9c8d..." } ],
       "outputs": [
         {
           "value": "100000000",
           "address": "seller-address",
           "assets": []
         },
         {
           "value": "1000000",
           "address": "buyer-address",
           "assets": [{ "tokenId": "34eb45...", "amount": "1" }]
         },
         {
           "value": "1000000",
           "ergoTree": "100504...",
           "assets": [{ "tokenId": "34eb45...", "amount": "99" }],
           "additionalRegisters": { "R4": "0e20..." }
         }
       ]
     }
   }
   ```

The service fetches every referenced box from its Ergo node, rebuilds the transaction, reduces it, and stores the Base64 representation for 30 minutes. Response:
```json
{
  "url": "ergopay://ergopay.duckdns.org/payment/sign/ABCD123456?sender=#P2PK_ADDRESS#",
  "requestId": "ABCD123456"
}
```

Wallets then GET `/payment/sign/{requestId}` to retrieve the `ErgoPayResponse` containing the reduced transaction and optional callback.

### 5. Wallet callbacks

- `/callback/{txId}` (NFT flow) or your custom `replyTo` endpoints receive wallet POSTs after a signature/broadcast. The payload typically looks like `{ "signedTxId": "<txId>" }`, but confirm with the wallet you target.

---

## Error handling & tips

- All validation failures return `HTTP 400` with a descriptive `message`. Surface that to your users/logs.
- Reduced transactions rely on the node being synced. If inputs/data inputs can’t be fetched, you will get `"Unable to load input boxes"` describing the missing IDs.
- NFT image hashes are recommended: supply `imageHash` to avoid the portal downloading IPFS content.
- Requests/timeouts: payment requests live ~30 minutes, NFT txs live 10 minutes, reduced transactions live 30 minutes.
- Always replace `https://` with `ergopay://` when showing links to users.

---

## Hosted version

A community deployment is available at **https://ergopay.duckdns.org**. Point your dApp to those URLs if you don’t want to host the service. Keep in mind that it uses the default TTL/in-memory cache: persist `requestId`/`txId` on your side if you need to query status later.

---

## Contributing

1. Fork/clone the repo.
2. Create a branch for your change.
3. Run `./gradlew test` (stop any running `bootRun` instance first so the embedded Postgres lock can be acquired).
4. Submit a PR.

Issues/ideas? Open a ticket or reach out on the Ergo developer channels.
