# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Developer Profile

PhD in Computer Science and Java Developer with 20 years of experience who consistently writes testable, readable code using modern clean code and clean architecture approaches. Prioritizes quality over speed and never compromises when choosing between high-quality and fast implementations.

## Project Overview

Spring Boot **4.0.3** microservices e-commerce application using **Spring Cloud 2025.1.0** and **Java 21**. Services communicate via REST (OpenFeign + RestTemplate), use Netflix Eureka for service discovery, Spring Cloud Config Server for centralized configuration, and Kafka for async messaging. Distributed tracing via Zipkin + Micrometer Brave bridge across all services.

## Architecture

### Service Dependency & Startup Order

1. `config-server` (port 8888) — must start first; all other services fetch config from it
2. `discovery-service` (port 8761) — Eureka Server; must start before business services
3. Business services (start in parallel after discovery-service is healthy):
   - `customer` (port 8090) — MongoDB-backed
   - `product` (port 8050) — PostgreSQL + Redis cache + Flyway migrations
   - `order` (port 8070) — PostgreSQL, orchestrates via REST, produces Kafka events
   - `payment` (port 8060) — PostgreSQL, produces Kafka events
   - `notification` (port 8040) — MongoDB, consumes Kafka events, sends emails
4. `gateway` (port 8222) — starts last; routes external traffic via Eureka load balancing

### Inter-Service Communication

Order Service routes all calls **through the Gateway** (not direct service-to-service):
- `CustomerClient` — OpenFeign → `http://{GATEWAY_HOST}:8222/api/v1/customers`
- `ProductClient` — RestTemplate → `http://{GATEWAY_HOST}:8222/api/v1/products`
- `PaymentClient` — OpenFeign → `http://{GATEWAY_HOST}:8222/api/v1/payments`

URLs configured in `services/config-server/src/main/resources/configurations/order-service.yml` under `application.config.*`. In Docker, `GATEWAY_HOST=gateway`.

### Kafka Messaging

Kafka (port 9092, Zookeeper on 22181) — JSON serialization with explicit type mappings:

| Topic | Producer | Message Type (Producer) | Consumer | Message Type (Consumer) |
|-------|----------|------------------------|----------|------------------------|
| `order-topic` | Order Service | `OrderConfirmation` | Notification Service | `OrderConfirmation` |
| `payment-topic` | Payment Service | `PaymentNotificationRequest` | Notification Service | `PaymentConfirmation` |

Type mapping aliases (`spring.json.type.mapping`) defined per-service in config server YAMLs. Consumer uses `orderConfirmation` and `paymentConfirmation` as mapping keys.

### Configuration Flow

Each service bootstraps via `spring.config.import=configserver:http://localhost:8888` (overridden in Docker via `SPRING_CONFIG_IMPORT` env var to `http://config-server:8888`), then loads its `<service-name>.yml` from:
```
services/config-server/src/main/resources/configurations/
```

Config files use `${ENV_VAR:default}` placeholders (e.g., `${POSTGRES_HOST:localhost}`) so the same config works both locally and in Docker.

Shared config lives in `configurations/application.yml` (Eureka defaults, JPA `open-in-view: false`, tracing sampling = 1.0, file logging).

### Databases

| Service | DB | DDL Strategy | Connection |
|---------|----|-------------|------------|
| Customer | MongoDB | auto (Spring Data) | `root:root@localhost:27017` |
| Product | PostgreSQL | **Flyway** (`validate` mode) | `root:root@localhost:5432/product` |
| Order | PostgreSQL | `ddl-auto: update` | `root:root@localhost:5432/order` |
| Payment | PostgreSQL | `ddl-auto: update` | `root:root@localhost:5432/payment` |
| Notification | MongoDB | auto (Spring Data) | `root:root@localhost:27017/notification` |

PostgreSQL databases auto-created on first start via `docker/postgres/init-databases.sql`.
Flyway migrations: `services/product/src/main/resources/db/migration/` (baseline version 0, auto-baseline enabled).

### Caching (Redis)

Product Service only. Redis 7.4-Alpine on port 6379.
- `RedisCacheManager` with **10-minute TTL**, JSON value serialization
- `@Cacheable("PRODUCT_CACHE", key = "#productId")` on `findById()`
- `@Cacheable("PRODUCT_CACHE", key = "'all'")` on `findAll()`
- Config class: `services/product/src/main/java/com/petproject/ecomnerce/config/RedisConfig.java`

### Security

**Currently disabled.** Keycloak is commented out in `docker-compose.yml`. Gateway has `SecurityConfig.java` with OAuth2/JWT setup fully commented out. All routes are open.

## File Structure & Navigation

```
services/
  config-server/           — Spring Cloud Config Server
  discovery-service/       — Eureka Server
  gateway/                 — Spring Cloud Gateway (WebMVC)
  customer/                — Customer CRUD (MongoDB)
  product/                 — Product catalog + purchase + Redis cache (PostgreSQL)
  order/                   — Order orchestration (PostgreSQL + Kafka + Feign)
  payment/                 — Payment processing (PostgreSQL + Kafka)
  notification/            — Event-driven notifications (MongoDB + Kafka + Mail)
docker/
  postgres/init-databases.sql  — Creates order, payment, product databases
resources/                 — Architecture diagrams
```

### Key File Locations Per Service

| What | Path Pattern |
|------|-------------|
| Main app class | `services/<svc>/src/main/java/com/petproject/ecommerce/<Svc>Application.java` |
| Config YAML | `services/config-server/src/main/resources/configurations/<svc>-service.yml` |
| Controller | `services/<svc>/src/main/java/com/petproject/ecommerce/<domain>/<Domain>Controller.java` |
| Service layer | `services/<svc>/src/main/java/com/petproject/ecommerce/<domain>/<Domain>Service.java` |
| Mapper | `services/<svc>/src/main/java/com/petproject/ecommerce/<domain>/<Domain>Mapper.java` |
| Error handler | `services/<svc>/src/main/java/com/petproject/ecommerce/handler/GlobalExceptionHandler.java` |
| Dockerfile | `services/<svc>/Dockerfile` |
| Tests | `services/<svc>/src/test/java/...` |

### Known Quirk: Product Service Package Name

Product Service uses `com.petproject.ecomnerce` (typo — missing second `m`) while all other services use `com.petproject.ecommerce`. This affects all file paths under `services/product/src/main/java/com/petproject/ecomnerce/`.

## Build & Run

### Full Stack (Docker Compose)
```bash
docker compose up -d --build    # Build and start everything
docker compose up -d            # Start without rebuilding
docker compose down             # Stop all
```

Dockerfiles use multi-stage builds: `eclipse-temurin:21-jdk-alpine` (build) → `eclipse-temurin:21-jre-alpine` (runtime). Maven wrapper (`mvnw`) runs inside the build stage with `dependency:go-offline` for layer caching.

### Infrastructure Only (for local development)
```bash
docker compose up postgresql pgadmin mongodb mongo-express zookeeper kafka mail-dev zipkin redis -d
```

### Build & Run a Single Service
```bash
mvn clean package -f services/<service-name>/pom.xml -DskipTests
java -jar services/<service-name>/target/<service-name>-0.0.1-SNAPSHOT.jar
```

### Run Tests
```bash
mvn test -f services/<service-name>/pom.xml
mvn test -f services/<service-name>/pom.xml -Dtest=ClassName
```

## Code Patterns

**DTOs:** Java Records with Jakarta validation (`@NotNull`, `@Email`, `@Positive`).

**REST endpoints:** `/api/v1/<resource>` pattern.

**Error handling:** `@RestControllerAdvice` in `GlobalExceptionHandler` per service (customer, order, product), returning `ErrorResponse` records. Payment and notification services have no custom error handler.

**Entity/Mapper pattern:** Entities (`@Document` for MongoDB, `@Entity` for JPA) mapped to/from request/response records via `*Mapper` Spring `@Component` classes (manual mapping, no MapStruct).

**JPA Auditing:** `@EnableJpaAuditing` on Order and Payment applications (for `@CreatedDate`/`@LastModifiedDate`).

**Async processing:** `@EnableAsync` on Notification Service for non-blocking email dispatch.

**Caching:** `@EnableCaching` on Product Service with Redis backend.

**Feign clients:** `@EnableFeignClients` on Order Service for `CustomerClient` and `PaymentClient`.

### Service-Specific Summary

| Service | Key Annotations | REST Endpoints | Special Features |
|---------|----------------|----------------|-----------------|
| Customer | `@SpringBootApplication` | `GET/POST /api/v1/customers`, `GET /{id}`, `PUT`, `DELETE`, `GET /exists/{id}` | `MongoRepository<Customer, String>` |
| Product | `@EnableCaching` | `GET/POST /api/v1/products`, `GET /{id}`, `POST /purchase` | Redis cache, Flyway, Hibernate sequences (increment 50) |
| Order | `@EnableFeignClients`, `@EnableJpaAuditing` | `GET/POST /api/v1/orders`, `GET /{id}`, `GET/POST /api/v1/order-lines` | Kafka producer, mixed Feign + RestTemplate |
| Payment | `@EnableJpaAuditing` | `POST /api/v1/payments` | Kafka producer |
| Notification | `@EnableAsync` | none (event-driven only) | Kafka consumer, Thymeleaf email templates |
| Gateway | `@SpringBootApplication` | proxy only | WebMVC gateway, Eureka load-balanced routes |

### Distributed Tracing

All business services + gateway include `spring-boot-starter-zipkin` and `micrometer-tracing-bridge-brave`. Sampling probability = 1.0 (100%). Traces visible at http://localhost:9411.

## Testing

**Only Product Service has meaningful tests** (`ProductServiceTest.java`). All other services have only the default Spring Boot context-load test.

**Test stack:** JUnit 5 + Mockito (no Testcontainers, no embedded databases).

**Test patterns used:**
- `@ExtendWith(MockitoExtension.class)` for unit tests
- `@Nested` classes and `@DisplayName` for organization
- `@Mock` for repository/mapper, `@InjectMocks` for service under test

## What's NOT in the Project (Yet)

- No CI/CD pipeline (no GitHub Actions, Jenkins, etc.)
- No Resilience4j (no circuit breakers, retries, or bulkheads)
- No OAuth2/Keycloak (commented out, intended for future)
- No Testcontainers or integration test infrastructure
- No API documentation (no Swagger/OpenAPI)

## Infrastructure URLs

| Service | URL |
|---------|-----|
| Config Server | http://localhost:8888 |
| Eureka Dashboard | http://localhost:8761 |
| API Gateway | http://localhost:8222 |
| pgAdmin | http://localhost:5050 |
| Mongo Express | http://localhost:8081 (credentials: root/root) |
| Mail Dev UI | http://localhost:1080 |
| Kafka | localhost:9092 |
| Zipkin | http://localhost:9411 |
| Redis | localhost:6379 |

## Resources & References

### Architecture & Diagrams
![Architecture Diagram](resources/photo_2026-03-04_14-57-45.jpg)
