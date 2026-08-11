# Digital Banking System — Microservices

A distributed banking platform built with Spring Boot, demonstrating microservices patterns including event-driven communication (Kafka), the SAGA pattern for distributed transactions, real-time fraud detection, and API gateway routing with rate limiting.

---
**Live demo:**
- Frontend: **https://nts1500bank.netlify.app/**
- Backend: **https://api-gateway-service-erz3.onrender.com/**

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2.0 |
| API Gateway | Spring Cloud Gateway (WebFlux) |
| Messaging | Apache Kafka |
| Relational DB | MySQL 8.0 |
| Cache / Fraud patterns | Redis |
| Inter-service calls | OpenFeign |
| Payments | Razorpay |
| Build tool | Maven |

---

## Services Overview

| Service | Port | Responsibility |
|---|---|---|
| api-gateway-service | 8090 | Single entry point, request routing, rate limiting |
| account-service | 8091 | Account management, balance operations |
| transaction-service | 8092 | Money transfers, SAGA orchestration, transaction history |
| payment-service | 8093 | Razorpay integration, payment webhooks |
| fraud-detection-service | 8094 | Real-time fraud detection via Redis-based pattern checks |
| notification-service | 8095 | Transaction, OTP, and fraud alerts |

---

## Architecture Flow

```
                        User
                         │
                         ▼
              API Gateway (:8090)
        rate limiting · request routing
                         │
        ┌────────────────┼────────────────┐
        ▼                ▼                ▼
  Account Service   Transaction Service  Payment Service
     (:8091)             (:8092)            (:8093)
        ▲                  │                   │
        │                  ▼                   ▼
        │            Apache Kafka  ◄────────────┘
        │                  │
        │        ┌─────────┴─────────┐
        │         ▼                   ▼
        │  Fraud Detection      Notification Service
        │  Service (:8094)          (:8095)
        │  (Redis patterns)     (email/SMS alerts)
        │         │
        └─────────┘
     block / refund via Feign
     (SAGA compensation)
```

---

## API Endpoints

### account-service (`:8091`)

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/accounts` | Create a new account |
| GET | `/api/v1/accounts/{accountNumber}` | Get account details |
| GET | `/api/v1/accounts/{accountNumber}/balance` | Get current balance |
| PUT | `/api/v1/accounts/{accountNumber}/block` | Block an account |
| PUT | `/api/v1/accounts/{accountNumber}/deduct?amount=` | Deduct balance (used internally by transaction-service) |
| PUT | `/api/v1/accounts/{accountNumber}/credit?amount=` | Credit balance (used internally by transaction-service) |

### transaction-service (`:8092`)

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/transactions/transfer` | Initiate a money transfer |
| GET | `/api/v1/transactions/{transactionId}` | Get transaction by ID |
| GET | `/api/v1/transactions/account/{accountNumber}` | Get transaction history for an account |
| POST | `/api/v1/transactions/{transactionId}/verify?otp=` | Verify OTP for a flagged transaction |

### payment-service (`:8093`)

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/payments` | Create a Razorpay payment order |
| POST | `/api/v1/payments/webhook` | Razorpay webhook receiver |

---

## Prerequisites

- **Java 17** (JDK)
- **Maven 3.8+**
- **Docker & Docker Compose** (for MySQL, Redis, Kafka, Zookeeper)
- **Postman** or similar, for testing endpoints
- A **Razorpay test account** (for payment-service) — [dashboard.razorpay.com](https://dashboard.razorpay.com)

---

## Environment Variables

Before running the services, set the following environment variables rather than hardcoding credentials in `application.yaml`. Create a `.env` file (git-ignored) or set these in your IDE's run configuration:

| Variable | Used by | Example |
|---|---|---|
| `DB_USERNAME` | account, transaction, payment | `root` |
| `DB_PASSWORD` | account, transaction, payment | *(your MySQL password)* |
| `RAZORPAY_KEY_ID` | payment-service | `rzp_test_xxxxxxxxxxxx` |
| `RAZORPAY_KEY_SECRET` | payment-service | *(your Razorpay secret)* |


---

## How To Run

### Step 1: Start Infrastructure

```bash
docker-compose up -d
```

This starts MySQL (`:3306`), Redis (`:6379`), Zookeeper (`:2181`), and Kafka (`:9092`).

### Step 2: Create the databases

Each service manages its own database. Connect to MySQL and create them upfront (recommended over relying solely on `createDatabaseIfNotExists`):

```sql
CREATE DATABASE IF NOT EXISTS account_db;
CREATE DATABASE IF NOT EXISTS transaction_db;
CREATE DATABASE IF NOT EXISTS payment_db;
```

### Step 3: Start all services

Each service runs independently — start them in separate terminals. `account-service` should generally be started first since other services call it via Feign.

```bash
# Terminal 1
cd account-service && mvn spring-boot:run

# Terminal 2
cd transaction-service && mvn spring-boot:run

# Terminal 3
cd payment-service && mvn spring-boot:run

# Terminal 4
cd fraud-detection-service && mvn spring-boot:run

# Terminal 5
cd notification-service && mvn spring-boot:run

# Terminal 6
cd api-gateway-service && mvn spring-boot:run
```

### Step 4: Verify everything is healthy

Each service exposes an actuator health check:

```bash
curl http://localhost:8091/actuator/health   # account-service
curl http://localhost:8092/actuator/health   # transaction-service
curl http://localhost:8093/actuator/health   # payment-service
curl http://localhost:8094/actuator/health   # fraud-detection-service
curl http://localhost:8095/actuator/health   # notification-service
curl http://localhost:8090/actuator/health   # api-gateway-service
```

---

## Quick Test: Create an Account and Transfer Money

```bash
# 1. Create an account
curl -X POST http://localhost:8091/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "accountHolderName": "Jane Doe",
    "email": "jane@example.com",
    "phone": "9876543210",
    "accountType": "SAVINGS",
    "initialDeposit": 10000
  }'

# 2. Transfer money between two existing accounts
curl -X POST http://localhost:8092/api/v1/transactions/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderAccountNumber": "ACC0001",
    "receiverAccountNumber": "ACC0002",
    "amount": 500,
    "description": "Test transfer"
  }'
```

`accountType` accepts: `SAVINGS`, `CURRENT`, `FIXED_DEPOSIT`.

---

## Project Structure

```
banking-system/
├── api-gateway-service/
├── account-service/
├── transaction-service/
├── payment-service/
├── fraud-detection-service/
├── notification-service/
└── docker-compose.yml
```

Each service follows a standard Spring Boot layout:

```
service-name/
├── src/main/java/.../
│   ├── client/          # Feign clients
│   ├── config/          # Kafka, Redis config
│   ├── controller/      # REST endpoints
│   ├── dto/             # Request/response payloads
│   ├── entity/          # JPA entities & enums
│   ├── event/           # Kafka event payload classes
│   ├── repository/      # Spring Data repositories
│   └── service/         # Business logic & Kafka listeners
└── src/main/resources/application.yaml
```

---

## Security Notes

- Do not commit real database passwords or Razorpay keys — use environment variables (`${DB_PASSWORD}`, `${RAZORPAY_KEY_SECRET}`, etc.) in `application.yaml`.
- Add a `.gitignore` entry for `.env` files before pushing to a public repository.
- Rotate any credentials that may have been exposed during development or debugging sessions.

