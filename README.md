# Transaction Processor

## Overview

This service accepts account transactions that may arrive more than once or out of order. It records each transaction idempotently and reserves per-account sequence slots so later processing can preserve order. This first step builds the project foundation and durable ingestion path; transaction execution is not implemented yet.

## Tech stack

- Java 17
- Spring Boot 4.1.1, Spring MVC, Spring Data JPA, Hibernate, and Jakarta Validation
- H2 in-memory database
- Maven wrapper
- JUnit 5, Mockito

Application classes use the `com.assessment.transaction_processor` base package, organized into `config`, `dto`, `entity`, `enums`, `repository`, `service`, `source`, and `validator` packages.

## How to run and test

Run the application with `./mvnw spring-boot:run` (on Windows, use `mvnw.cmd spring-boot:run`). Run the test suite with `./mvnw clean test` (on Windows, use `mvnw.cmd clean test`).

## Assumptions

1. Sequence numbers are scoped to each account.
2. A duplicate is any later request with the same `transactionId`, regardless of payload.
3. The cursor continues after a failed transaction.
4. A pending transaction that exceeds the pending timeout becomes `FAILED`.
5. The retry delay is configurable.
6. The balance can never be negative.
7. Only `CREDIT` and `DEBIT` transaction types are supported.
8. A single currency per account is assumed.

## Status lifecycle

| Status | Meaning | Associated reason |
|---|---|---|
| `PENDING` | Accepted but not yet executed | `AWAITING_SEQUENCE` or `AWAITING_RETRY` |
| `PROCESSING` | Execution is in progress | None |
| `PROCESSED` | Execution completed successfully | None |
| `FAILED` | Terminal failure | A `FailureReason` |
| `DUPLICATE` | A repeated transaction identifier | Logged separately; the original is unchanged |

## Data model summary

| Entity | Purpose |
|---|---|
| `Account` | Current balance, per-account next expected sequence, and optimistic version |
| `TransactionRecord` | The original request, status, lifecycle fields, and its unique account sequence slot |
| `DuplicateRequestLog` | Each repeated transaction identifier and whether its payload matched |
| `RejectedRecord` | Malformed, stale, or conflicting requests that cannot own a sequence slot |

## Progress log

- Step 1: Added the Maven/Spring Boot 4 foundation, configuration and clock, entities and repositories, input validation and transaction sources, idempotent ingestion, and focused tests under `com.assessment.transaction_processor`. Processing, retries, scheduling, reporting, and REST endpoints remain out of scope.
