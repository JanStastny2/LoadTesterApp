# LoadTesterApp

A custom load testing orchestrator that targets [GrainWeightApp](../GrainWeightApp) (or any HTTP endpoint). Manages the full test lifecycle, executes load scenarios reactively, and collects hardware metrics from Spring Actuator.

## Architecture

Built with Spring Boot + WebFlux (reactive). Key components:

- **TestRunnerService** — executes load tests using Reactor (`Flux`/`Mono`)
- **ApiRequestService** — sends HTTP requests via `WebClient`
- **HwSampleService** — polls GrainWeightApp's Actuator every 250ms during tests
- **TestCommandService** — manages test state transitions
- **TestRunQueryService** — paginated and filtered queries

## Test Lifecycle

```
CREATED → APPROVED → RUNNING → FINISHED
                   ↘           ↘ FAILED
         REJECTED
         WAITING → CANCELLED
         RUNNING → CANCELLED (via cancellation signal)
```

State transitions require:
- `CREATED → APPROVED`: `POST /api/tests/{id}/approve` (ADMIN only)
- `APPROVED → RUNNING`: `POST /api/tests/{id}/run` (ADMIN only)
- `WAITING/RUNNING → CANCELLED`: `POST /api/tests/{id}/cancel`

## Test Scenarios

### STEADY
Sends exactly `totalRequests` with fixed `concurrency` using WebFlux `flatMap` concurrency parameter.

Required fields: `totalRequests`, `concurrency`

### RAMP_UP
Starts at 10 concurrent requests, increases by 30 every 250ms. Stops when:
- 60 seconds elapsed
- Failure rate exceeds 5% (after 10+ failures)
- User cancels

## REST API

### Test Runs

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/tests` | List tests (paginated, filterable) |
| `POST` | `/api/tests` | Create a test run |
| `GET` | `/api/tests/{id}` | Get test detail |
| `PUT` | `/api/tests/{id}` | Update test (only if CREATED) |
| `DELETE` | `/api/tests/{id}` | Delete test |
| `POST` | `/api/tests/{id}/approve` | Approve test (ADMIN) |
| `POST` | `/api/tests/{id}/reject` | Reject test (ADMIN) |
| `POST` | `/api/tests/{id}/run` | Start test execution (ADMIN) |
| `POST` | `/api/tests/{id}/cancel` | Cancel test |
| `GET` | `/api/tests/{id}/hw-samples` | Get hardware samples for test |
| `GET` | `/api/tests/compare?ids=1,2,3` | Compare multiple test results |

### Create Request Body

```json
{
  "testScenario": "STEADY",
  "totalRequests": 100,
  "concurrency": 10,
  "processingMode": "POOL",
  "poolSizeOrCap": 4,
  "delayMs": 500,
  "request": {
    "url": "http://localhost:8081/api/work/records",
    "method": "GET"
  }
}
```

`processingMode` values: `SERIAL`, `POOL`, `VIRTUAL`  
`testScenario` values: `STEADY`, `RAMP_UP`

### Query Parameters for GET /api/tests

`status`, `mode`, `poolSizeOrCap`, `testScenario`, `totalRequests`, `username`, `createdById`, `from`, `to`, `page`, `size`, `sort`

## Configuration

`src/main/resources/application.properties`:

```properties
server.port=8082

# Database — override via environment variables
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/loadtesterdb}
spring.datasource.username=${DB_USERNAME:postgres}
spring.datasource.password=${DB_PASSWORD:admin}
```

## Database Setup

Requires PostgreSQL 14+. Create the database before first run:

```sql
CREATE DATABASE loadtesterdb;
```

Schema is managed by **Flyway** (migrations in `src/main/resources/db/`). Applied automatically on startup.

## Build and Run

Requirements: Java 21, Maven 3.9+, PostgreSQL running on localhost:5432

```bash
mvn clean install
java -jar target/LoadTesterApp-0.0.1-SNAPSHOT.jar
```

Or with environment variable override:
```bash
DB_PASSWORD=mypassword java -jar target/LoadTesterApp-0.0.1-SNAPSHOT.jar
```

Application starts on **port 8082**.

## Running Tests

```bash
mvn test
```

Test coverage includes:
- Unit tests for `TestRunnerService` (state transitions, scenario validation)
- `@WebMvcTest` integration tests for `TestRunController` (all lifecycle endpoints, auth/authz)

## Relationship with GrainWeightApp

LoadTesterApp sends requests to GrainWeightApp's `/api/work/**` endpoints, appending query parameters:

```
http://localhost:8081/api/work/records?mode=POOL&size=4&delayMs=500
```

It also polls GrainWeightApp's Actuator:
- `/actuator/metrics/system.cpu.usage`
- `/actuator/metrics/jvm.memory.used?tag=area:heap`

GrainWeightApp must be running on **port 8081** before starting a test.
