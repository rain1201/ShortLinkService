# ShortLinkService

A high-performance URL shortening service built with Spring Boot 4.0 and Java 21, featuring Redis caching, Redis List-based asynchronous view tracking, Proof-of-Work captcha protection, and a dark-themed SPA frontend.

## Features

- **URL Shortening** – Create compact short links for any valid URL with configurable expiration.
- **Fast Redirects** – Cached in Redis for low-latency redirects; falls back to HSQLDB on cache miss.
- **View Tracking** – Asynchronous view counting via Redis List (BRPopLPush) with batched database writes (triggered by size or time threshold).
- **Proof-of-Work Captcha** – SHA-1 based PoW challenge protects the `/shorten` endpoint from spam.
- **Update/Delete with Auth Codes** – HMAC-like update codes allow link owners to modify or delete their links.
- **RESTful API** – Clean JSON API with unified response format.
- **Dark-themed SPA** – Tabbed web UI (Create / Query / Update / Delete) with client-side PoW captcha solving.
- **Distributed Ready** – Redis distributed locks prevent cache stampedes and duplicate creation.

## Architecture

```
┌──────────┐     ┌──────────────┐     ┌─────────────┐
│  Browser │────▶│  Spring Boot │────▶│    Redis     │
│ (SPA UI) │     │   (Tomcat)   │     │ ┌─────────┐ │
└──────────┘     └──────┬───────┘     │ │  Cache  │ │
                        │             │ ├─────────┤ │
                        │             │ │View List│ │
                        │             │ └────┬────┘ │
                        │             └──────┼───────┘
                        │                    │
                 ┌──────┴───────┐            │
                 │  MQService   │◀───────────┘
                 │ (Background) │  BRPopLPush
                 └──────┬───────┘
                        │
                 ┌──────┴───────┐
                 │   HSQLDB     │
                 │  (Database)  │
                 └──────────────┘
```

1. Client requests a short link redirect (`GET /{id}`).
2. Server checks Redis cache for the original URL.
3. On cache hit → immediate redirect (302).
4. On cache miss → query HSQLDB, populate Redis, redirect.
5. Each redirect pushes a serialized `View` event to a Redis List (`shortlink:view:mq`).
6. `MQService` background workers consume the list via `BRPopLPush`, batch views in local cache, and periodically flush to HSQLDB (triggered by size or time threshold).

## Tech Stack

| Component             | Technology                                           |
| --------------------- | ---------------------------------------------------- |
| **Language**          | Java 21                                              |
| **Framework**         | Spring Boot 4.0.6 (Web, Data JPA, JDBC, Redis)      |
| **Build Tool**        | Apache Maven 3.9.14 (via Maven Wrapper)              |
| **Database**          | HSQLDB (file-based, in-memory for tests)             |
| **Cache & Queue**     | Redis (via Spring Data Redis Reactive + Lettuce)     |
| **Serialization**     | Jackson (JSON for View event serialization)          |
| **ORM**               | JPA / Hibernate                                      |
| **API Docs**          | Springdoc OpenAPI 3.0.2                              |
| **ID Generator**      | Snowflake (41-bit timestamp, 10-bit worker, 12-bit sequence) |
| **Captcha**           | Custom Proof-of-Work (SHA-1)                         |
| **Frontend**          | Vanilla HTML + CSS (dark theme) + JavaScript (ES6)   |
| **Testing**           | JUnit 5 + Mockito                                    |
| **License**           | MIT                                                  |

## Prerequisites

- **Java 21** JDK (e.g., Eclipse Temurin, Oracle OpenJDK)
- **Redis** 6+ (running on `localhost:6379`)

## Quick Start

### 1. Clone & Build

```bash
git clone https://github.com/your-org/ShortLinkService.git
cd ShortLinkService
./mvnw clean package
```

### 2. Start Dependencies

```bash
# Start Redis (Docker example)
docker run -d -p 6379:6379 redis:7
```

### 3. Run the Application

```bash
# Production mode
./mvnw spring-boot:run

# Dev mode (auto-creates schema + sample data)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Or run the packaged JAR:

```bash
java -jar target/shortlink-0.0.1-SNAPSHOT.jar
java -jar target/shortlink-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

### 4. Open the UI

Navigate to [http://localhost:8080](http://localhost:8080) in your browser.

## API Reference

All responses use the unified `ApiResponse<T>` format:

```json
{
  "code": 0,
  "message": "success",
  "data": { ... }
}
```

| Method | Path                   | Description                           | Auth            |
| ------ | ---------------------- | ------------------------------------- | --------------- |
| `GET`  | `/index`               | Health check                          | None            |
| `POST` | `/shorten`             | Create a short link                   | PoW captcha     |
| `GET`  | `/{id}`                | Redirect to original URL              | None            |
| `GET`  | `/getInfo/{id}`        | Get short link metadata               | None            |
| `POST` | `/update/{id}`         | Update URL or expiration              | Update code     |
| `POST` | `/delete/{id}`         | Delete a short link                   | Update code     |
| `POST` | `/calcUpdateCode/{id}` | Calculate update code *(dev only)*    | Dev profile     |

### `POST /shorten`

Create a new short link. Protected by PoW captcha.

**Parameters (form-encoded):**

| Parameter     | Type    | Default      | Description                              |
| ------------- | ------- | ------------ | ---------------------------------------- |
| `url`         | string  | (required)   | The original URL to shorten              |
| `expireAfter` | long    | `1000000000` | TTL in seconds (~31.7 years)            |
| `updateCode`  | string  | (optional)   | Secret code for later update/delete      |
| `captcha`     | string  | (required)   | PoW nonce (SHA-1 hash with N leading 0s) |
| `time`        | long    | (required)   | Unix timestamp of captcha generation     |

**Response data:** The Base64-encoded short link ID.

### `GET /{id}`

Redirect to the original URL. View count is incremented asynchronously.

| Parameter | Location | Description                                |
| --------- | -------- | ------------------------------------------ |
| `id`      | Path     | Base64-encoded Snowflake ID of short link  |

### `GET /getInfo/{id}`

Retrieve metadata about a short link.

**Response data (example):**

```
Shortlink{idx=1, originalUrl='https://example.com', viewCount=42, createdAt=..., expireAfter=...}
```

### `POST /update/{id}`

Update the target URL and/or expiration. Requires the correct update code.

**Parameters (form-encoded):**

| Parameter     | Type   | Description                                       |
| ------------- | ------ | ------------------------------------------------- |
| `updateCode`  | string | (required) The update code to authorize the change |
| `url`         | string | (optional) New target URL                         |
| `expireAfter` | long   | (optional) New TTL in seconds                     |

### `POST /delete/{id}`

Delete a short link. Requires the correct update code.

**Parameters (form-encoded):**

| Parameter    | Type   | Description                                       |
| ------------ | ------ | ------------------------------------------------- |
| `updateCode` | string | (required) The update code to authorize deletion   |

## Configuration

Key settings in `src/main/resources/application.yaml`:

| Property                          | Default               | Description                                   |
| --------------------------------- | --------------------- | --------------------------------------------- |
| `server.port`                     | `8080`                | HTTP server port                              |
| `spring.datasource.url`           | `jdbc:hsqldb:file:testdb` | HSQLDB connection URL                    |
| `spring.redis.host`               | `localhost`           | Redis host                                    |
| `spring.rabbitmq.host`            | `localhost`           | RabbitMQ host                                 |
| `shortlink.redirect-code`         | `302`                 | HTTP redirect status code (use `200` in dev)  |
| `shortlink.pow-difficulty`        | `2`                   | PoW captcha: required leading zero bytes      |
| `shortlink.pow-expire`            | `300`                 | PoW captcha TTL (seconds)                     |
| `shortlink.check-code-length`     | `8`                   | Update code truncation length                 |

## Snowflake ID Format

Short link IDs are 64-bit Snowflake IDs encoded in Base64:

```
┌────────────────────┬──────────┬──────────┬────────────┐
│   Timestamp (41)   │ DC (5)   │ WID (5)  │ Seq (12)   │
├────────────────────┼──────────┼──────────┼────────────┤
│  ms since 2021-01-01│ 0..31    │ 0..31    │ 0..4095    │
└────────────────────┴──────────┴──────────┴────────────┘
```

- **Timestamp**: Milliseconds since custom epoch (2021-01-01 00:00:00 UTC).
- **Datacenter ID** and **Worker ID**: Configured statically (datacenter=1, worker=1 by default).
- **Sequence**: Rolls over within the same millisecond.

## Proof-of-Work Captcha

The `/shorten` endpoint requires a PoW captcha to prevent spam:

1. Server sends a challenge (via `/index` or embedded in page): current unix `time`, `difficulty` (number of leading zero bytes).
2. Client iterates nonces, computing `SHA1(time + nonce)`, until the hash has `difficulty` leading zero bytes.
3. Client submits `time` and `captcha` (the winning nonce) with the request.
4. Server validates the hash and checks that `time` is within `pow-expire` seconds of now.

The client-side solver runs in the browser using the Web Crypto API.

## Update Codes

Update codes are HMAC-style tokens that authorize link modifications:

```
SHA1(realCode + idx + url + expireAfter) → truncated to checkCodeLength chars (default 8)
```

- If no `updateCode` is provided during creation, the link cannot be updated or deleted later.
- The `calcUpdateCode` endpoint (dev profile only) helps test update code generation.

## Project Structure

```
src/
├── main/
│   ├── java/com/test/shortlink/
│   │   ├── ShortlinkApplication.java        # Entry point + dev data initializer
│   │   ├── anno/                            # Custom annotations (@PowCaptcha, @ParamLenLimit)
│   │   ├── aspect/                          # AOP aspects for captcha & param validation
│   │   ├── conf/                            # Spring configuration (DB, Redis, MQService, Executor, Jackson)
│   │   ├── controller/                      # REST controller (ShortlinkController)
│   │   ├── dto/                             # API response wrapper (ApiResponse)
│   │   ├── entity/                          # JPA entities (Shortlink, View, RedisKeys)
│   │   ├── repository/                      # JPA repository
│   │   ├── service/                         # Business logic (ShortlinkService, MQService, MQServiceFactory, etc.)
│   │   └── util/                            # Utilities (SnowFlakeId, URL validation, PoW, etc.)
│   └── resources/
│       ├── application.yaml                 # Main configuration
│       └── static/                          # Frontend SPA (HTML, CSS, JS)
│           ├── index.html
│           ├── css/style.css
│           └── js/{api,app,captcha}.js
└── test/
    └── java/com/test/shortlink/
        ├── controller/                      # Controller integration tests (MockMvc)
        ├── service/                         # Service layer unit tests
        ├── aspect/                          # AOP aspect tests
        └── util/                            # Utility tests
```

## Running Tests

```bash
./mvnw test
```

Tests use the `test` profile with an in-memory HSQLDB and Mockito mocks — no external services required.

**Test coverage:**

| Test Class                    | What it covers                                          |
| ----------------------------- | ------------------------------------------------------- |
| `ShortlinkControllerTest`     | All 6 API endpoints, error handling, dev vs prod profile |
| `ShortlinkServiceTest`        | Shorten, redirect, update, delete, getInfo, view tracking |
| `MQServiceTest`               | Redis List consumption, local caching, DB sync with rollback |
| `RedisExpireListenerTest`     | Redis key expiration event handling                      |
| `PowCaptchaAspectTest`        | Captcha validation (valid, expired, invalid, missing)    |
| `ParamLenLimitAspectTest`     | Parameter length validation (bounds, types, arrays)      |
| `UtilTest`                    | URL validation, ID encode/decode, PoW, SnowFlakeId       |
| `SnowFlakeIdTest`             | Uniqueness, monotonicity, concurrency, boundary values   |

## TODO

- [ ] Replace HSQLDB with MySQL for production-grade persistence
- [ ] Integrate Elasticsearch for click-stream analysis and visitor analytics

## License

[MIT](LICENSE) — Copyright 2026 Rye Song.
