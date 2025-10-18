# ErgoPay Payment Portal

This is a ready to use payment portal application to use for websites and applications to process
payments on the Ergo blockchain. It supports payments both in Ergo (the blockchain's native token)
and every other token on Ergo.

## Quick start

The portal now ships with a simple web UI and one-command setup scripts so you can deploy it on any
Linux machine in minutes.

### Run with Gradle (recommended for local development)

```bash
git clone https://github.com/MrStahlfelge/ergopay-payment-portal.git
cd ergopay-payment-portal
./scripts/run.sh
```

The application listens on port `82` by default. Because low ports require elevated privileges on
Linux and macOS, either run the command with `sudo` or override the port with
`SERVER_PORT=<preferred-port> ./scripts/run.sh`.

Open <http://localhost:82> in your browser (or whatever port you configured) to access the
dashboard, create payment requests, and monitor their status. The REST API continues to be available
under `/payment/...`.

### Run with Docker

```bash
git clone https://github.com/MrStahlfelge/ergopay-payment-portal.git
cd ergopay-payment-portal
docker build -t ergopay-portal .
docker run -p 82:82 ergopay-portal
```

You can pass custom JVM options when running the container:

```bash
docker run -e JAVA_OPTS="-Xms512m -Xmx512m" -p 82:82 ergopay-portal
```

### Deploying to a Linux server

1. Install Docker (or Java 11 + Gradle) on your server.
2. Clone this repository and either run `./scripts/run.sh` or build the Docker image as shown above.
3. Reverse proxy the exposed port `82` if you want to serve it under HTTPS.

### Automate Nginx reverse proxying

Use the helper script to generate and enable an Nginx site that proxies traffic to the Spring Boot
app:

```bash
sudo ./scripts/configure_nginx.sh --server-name pay.example.com --port 82
```

The script will:

* Create `/etc/nginx/sites-available/ergopay-portal.conf` with a secure proxy configuration.
* Symlink it into `sites-enabled`.
* Test the configuration with `nginx -t` and reload Nginx if the test passes.

Pass `--https` to emit an HTTPS-ready server block (you can later plug in certificates from Let's
Encrypt or another CA). Use `--force` to overwrite an existing configuration.

### Keep a DuckDNS record in sync

If you rely on [DuckDNS](https://www.duckdns.org/) for dynamic DNS, the following script provisions a
systemd timer that keeps your record updated every five minutes:

```bash
sudo ./scripts/setup_duckdns.sh --subdomain your-subdomain --token YOUR_TOKEN
```

It installs a lightweight update script under `/opt/duckdns`, registers a `duckdns-update.service`
and `duckdns-update.timer`, and starts the timer immediately. Check the timer status with
`systemctl status duckdns-update.timer`.

The UI is responsive and can be shared directly with non-technical users. If you already use Spring
Boot on your server, you can integrate the `PaymentService` class into your own project and directly
use it without going through a REST API.

## Use the hosted version

You can find a deployed version of this service on [TokenJay](https://tokenjay.app/) to integrate 
into your applications without the need to host it yourself.

To cover the server costs, the fee for completed transactions is 0.1% of ERG and tokens (min 0.001 ERG, min 1 token unit in its raw value - for SigUSD, this is 0.01 SigUSD).

[Swagger](https://api.tokenjay.app)

## How the code is organized

If you are not familiar with Spring Boot, but you are most interested in Ergo-related code, directly
jump to the  [Service class](https://github.com/MrStahlfelge/ergopay-payment-portal/blob/master/src/main/kotlin/org/ergoplatform/ergopay/paymentportal/service/PaymentService.kt). 

Spring is organized in the following way:

* Model classes define db entities
* Repository classes define the db access
* Controller classes define REST API endpoints
* Service classes define the actual business logic and are singletons.

## The API endpoints
Defined in [PaymentPortalController](https://github.com/MrStahlfelge/ergopay-payment-portal/blob/master/src/main/kotlin/org/ergoplatform/ergopay/paymentportal/rest/PaymentPortalController.kt)

### POST payment/addrequest

Request body is a json-encoded `CreatePaymentRequest` 

```
class CreatePaymentRequest(
    val nanoErg: Long, // nano erg value to pay to the recipient, or min amount if only token should be paid
    val tokenId: String?, // token ID to pay to the recipient, or null if none
    val tokenRawAmount: Long?, // raw amount of token to pay. note it is the raw amount
    val receiverAddress: String, // address to pay to
    val senderAddress: String?, // address to pay from, if known
    val message: String?, // message to attach to the transaction (optional)
)
```

Example body to request 1 ERG:

    {
    "nanoErg": 1000000000,
    "receiverAddress": "9g8gaARC3N8j9v97wmnFkhDMxHHFh9PEzVUtL51FGSNwTbYEnnk"
    }

Example body to request 1 SigUSD:

    {
    "nanoErg": 1000000,
    "receiverAddress": "9g8gaARC3N8j9v97wmnFkhDMxHHFh9PEzVUtL51FGSNwTbYEnnk",
    "tokenId": "03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04",
    "tokenRawAmount": 100
    }

The request will respond with the request ID and an ErgoPay URL:

    {
    "requestId": "MSWHPMIDDZ",
    "ergoPayUrl": "ergopay://localhost:82/payment/getrequest/MSWHPMIDDZ?sender=#P2PK_ADDRESS#"
    }

Use the request ID for calls to the state endpoint (see below). The `ergoPayUrl` is the link you 
should present your user by QR code and as a button-type link in case the user has an ErgoPay-compatible
wallet application installed on the device he is using your website/app with.

### GET payment/state/{requestId}

Use this to retrieve the state of the transaction. A response will look like this:

    {
    "requestId": "MSWHPMIDDZ",
    "paymentRequestState": "WAITING",
    "txId": "4bd18c0ddf5cfd2be6885c8e111bf75f28c4c5dcc5961f3b83a5141a047928df"
    }

Transaction ID is the Ergo transaction ID that can be reviewed in Explorer. Note that this transaction 
ID is set before the transaction is executed.

PaymentRequestState is one of the following states:

    CREATED, // request sent by dapp, but no ergopay request from wallet app
    WAITING, // ergopay request sent to wallet app and waiting to be submitted and included into a block
    EXECUTED, // payment included into a block
    INVALID, // ergopay request sent to wallet app but impossible to be included into a block


Please note that request IDs will be purged after some time (default configuration 30 minutes), so 
make sure to persist the data on your side.
