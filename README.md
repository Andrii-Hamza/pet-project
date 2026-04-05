# E-Commerce Microservices

A microservices-based e-commerce platform built with **Spring Boot 4.0.3**, **Spring Cloud 2025.1.0**, and **Java 21**.

## Architecture

![Architecture Diagram](resources/photo_2026-03-04_14-57-45.jpg)

The system follows a microservices architecture with:

- **API Gateway** — Single entry point routing all traffic to internal services via Eureka load balancing
- **Config Server** — Centralized configuration for all services
- **Eureka Server** — Service discovery and registration
- **Kafka** — Async messaging for order confirmations and payment notifications
- **Redis** — Caching layer for product catalog
- **Zipkin** — Distributed tracing across all services (100% sampling)

### Services

| Service | Port | Database | Description |
|---------|------|----------|-------------|
| Config Server | 8888 | — | Centralized configuration |
| Discovery Service | 8761 | — | Eureka service registry |
| Gateway | 8222 | — | API gateway (Spring Cloud Gateway WebMVC) |
| Customer | 8090 | MongoDB | Customer management |
| Product | 8050 | PostgreSQL + Redis | Product catalog, inventory & caching |
| Order | 8070 | PostgreSQL | Order processing & orchestration |
| Payment | 8060 | PostgreSQL | Payment handling |
| Notification | 8040 | MongoDB | Email notifications (event-driven, no REST) |

### Communication

- **Synchronous:** REST via OpenFeign (Order -> Customer, Order -> Payment) and RestTemplate (Order -> Product), all routed through the **Gateway**
- **Asynchronous:** Kafka topics with JSON serialization and explicit type mappings
  - `order-topic` — Order Service produces `OrderConfirmation` -> Notification Service consumes
  - `payment-topic` — Payment Service produces `PaymentNotificationRequest` -> Notification Service consumes

### Order Creation Flow

When a client sends `POST /api/v1/orders`, the Order Service orchestrates the following steps:

```
Client
  |
  v
API Gateway (:8222)
  |
  v
Order Service (:8070)
  |-- 1. Validate customer exists       --> Customer Service (via Feign through Gateway)
  |-- 2. Purchase products (deduct qty) --> Product Service  (via RestTemplate through Gateway)
  |-- 3. Persist order + order lines    --> PostgreSQL
  |-- 4. Request payment                --> Payment Service  (via Feign through Gateway)
  |-- 5. Send order confirmation        --> Kafka (order-topic)
  |
  v
Notification Service (:8040)            <-- consumes from order-topic & payment-topic
  |-- Persists notification to MongoDB
  |-- Sends HTML email (Thymeleaf)      --> MailDev SMTP (:1025)
```

## Tech Stack

| Category | Technology |
|----------|-----------|
| Language & Framework | Java 21, Spring Boot 4.0.3, Spring Cloud 2025.1.0 |
| Service Discovery | Netflix Eureka |
| API Gateway | Spring Cloud Gateway (WebMVC) |
| Configuration | Spring Cloud Config Server |
| Messaging | Apache Kafka (Zookeeper) |
| REST Clients | OpenFeign, RestTemplate |
| Relational DB | PostgreSQL (Order, Payment, Product) |
| Document DB | MongoDB (Customer, Notification) |
| Caching | Redis (Product Service, 10-min TTL) |
| DB Migrations | Flyway (Product Service) |
| Email Templates | Thymeleaf |
| Distributed Tracing | Zipkin + Micrometer Brave |
| Containerization | Docker (multi-stage builds, Eclipse Temurin 21 Alpine) |
| Orchestration | Docker Compose |

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker & Docker Compose

### Option A: Full Stack with Docker (Recommended)

```bash
# Build and start everything
docker compose up -d --build

# Check all services are running
docker compose ps

# View logs for a specific service
docker compose logs -f <service-name>

# Stop everything
docker compose down
```

Docker Compose handles the startup order automatically via healthchecks:
1. **config-server** starts first (healthcheck on `/actuator/health`)
2. **discovery-service** waits for config-server to be healthy
3. **Business services** wait for discovery-service to be healthy
4. **gateway** starts last (after all business services are up)

PostgreSQL databases (`order`, `payment`, `product`) are auto-created on first start via `docker/postgres/init-databases.sql`. Product database is pre-populated with 25 products across 5 categories via Flyway migrations.

### Option B: Local Development

Start only infrastructure, then run services manually:

```bash
# 1. Start infrastructure
docker compose up postgresql pgadmin mongodb mongo-express zookeeper kafka mail-dev zipkin redis -d

# 2. Build and run services (in order)
mvn clean package -f services/config-server/pom.xml -DskipTests
java -jar services/config-server/target/config-server-0.0.1-SNAPSHOT.jar

mvn clean package -f services/discovery-service/pom.xml -DskipTests
java -jar services/discovery-service/target/discovery-service-0.0.1-SNAPSHOT.jar

# Then start remaining services in any order:
# gateway, customer, product, order, payment, notification
mvn clean package -f services/<service-name>/pom.xml -DskipTests
java -jar services/<service-name>/target/<service-name>-0.0.1-SNAPSHOT.jar
```

### Verify

- Eureka Dashboard: http://localhost:8761 — all services should be registered
- Config Server: http://localhost:8888/actuator/health
- API Gateway: http://localhost:8222/api/v1/products — should return pre-seeded products

## API Endpoints

All endpoints are accessible through the **API Gateway** at `http://localhost:8222`.

### Customer Service

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/customers` | Create a customer |
| PUT | `/api/v1/customers` | Update a customer |
| GET | `/api/v1/customers` | Get all customers |
| GET | `/api/v1/customers/{id}` | Get customer by ID |
| GET | `/api/v1/customers/exists/{id}` | Check if customer exists |
| DELETE | `/api/v1/customers/{id}` | Delete a customer |

### Product Service

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/products` | Create a product |
| POST | `/api/v1/products/purchase` | Purchase products (deducts inventory) |
| GET | `/api/v1/products` | Get all products (cached) |
| GET | `/api/v1/products/{id}` | Get product by ID (cached) |

### Order Service

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/orders` | Create an order |
| GET | `/api/v1/orders` | Get all orders |
| GET | `/api/v1/orders/{order-id}` | Get order by ID |
| GET | `/api/v1/order-lines/order/{order-id}` | Get order lines by order ID |

### Payment Service

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/payments` | Create a payment |

### Notification Service

No REST endpoints — event-driven only. Consumes Kafka messages and sends HTML emails.

## Example: End-to-End Order

**Step 1 — Create a customer:**
```bash
curl -X POST http://localhost:8222/api/v1/customers \
  -H "Content-Type: application/json" \
  -d '{
    "firstname": "John",
    "lastname": "Doe",
    "email": "john.doe@example.com",
    "address": {
      "street": "Main Street",
      "houseNumber": "42",
      "zipCode": "10001"
    }
  }'
```

**Step 2 — Browse products** (pre-seeded with 25 products):
```bash
curl http://localhost:8222/api/v1/products
```

**Step 3 — Place an order** (uses customer ID from step 1 and product IDs from step 2):
```bash
curl -X POST http://localhost:8222/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "reference": "ORD-001",
    "amount": 199.98,
    "paymentMethod": "VISA",
    "customerId": "<customer-id-from-step-1>",
    "products": [
      { "productId": 1, "quantity": 2 }
    ]
  }'
```

This triggers the full flow: customer validation -> product purchase (inventory deducted) -> order persisted -> payment processed -> Kafka events -> email notifications.

**Step 4 — Check the email** at http://localhost:1080 (MailDev UI) — you should see both an order confirmation and a payment confirmation email.

**Payment methods:** `PAYPAL`, `CREDIT_CARD`, `VISA`, `MASTER_CARD`, `BITCOIN`

## Infrastructure UIs

| Service | URL | Credentials |
|---------|-----|-------------|
| Eureka Dashboard | http://localhost:8761 | — |
| API Gateway | http://localhost:8222 | — |
| pgAdmin | http://localhost:5050 | pgadmin4@pgadmin.org / admin |
| Mongo Express | http://localhost:8081 | root / root |
| MailDev | http://localhost:1080 | — |
| Zipkin | http://localhost:9411 | — |

## Configuration

All service configurations are centralized in the **Config Server** and stored as YAML files:

```
services/config-server/src/main/resources/configurations/
  application.yml          # Shared config (Eureka, tracing, JPA defaults)
  customer-service.yml     # MongoDB connection
  product-service.yml      # PostgreSQL, Flyway, Redis cache
  order-service.yml        # PostgreSQL, Kafka producer, inter-service URLs
  payment-service.yml      # PostgreSQL, Kafka producer
  notification-service.yml # MongoDB, Kafka consumer, SMTP mail
  gateway-service.yml      # Gateway routes (load-balanced via Eureka)
```

Config files use `${ENV_VAR:default}` placeholders so the same configuration works both locally and in Docker without changes. For example:
- `${POSTGRES_HOST:localhost}` — resolves to `postgresql` in Docker, `localhost` when running locally
- `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` — resolves to `ms_kafka:29092` in Docker

## Project Structure

```
services/
  config-server/           # Centralized configuration server
  discovery-service/       # Eureka service registry
  gateway/                 # API Gateway (Spring Cloud Gateway WebMVC)
  customer/                # Customer microservice (MongoDB)
  product/                 # Product microservice (PostgreSQL + Flyway + Redis cache)
  order/                   # Order microservice (PostgreSQL + Kafka + Feign + RestTemplate)
  payment/                 # Payment microservice (PostgreSQL + Kafka)
  notification/            # Notification microservice (MongoDB + Kafka + Thymeleaf email)
docker/
  postgres/
    init-databases.sql     # Auto-creates order, payment, product databases
resources/
  photo_2026-03-04_14-57-45.jpg  # Architecture diagram
```
