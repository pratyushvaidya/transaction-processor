# Running the Transaction Processor and Using Its API

## Execution instructions

### Prerequisites

- Java 17 or later
- No separately installed Maven is required; the project includes Maven Wrapper scripts.

### Run the tests

From the repository root:

```shell
./mvnw clean test
```

On Windows PowerShell:

```powershell
.\mvnw.cmd clean test
```

### Start the REST application

Start the default profile with:

```shell
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

The server listens on `http://localhost:8080`. The default profile uses an in-memory H2 database, so records are removed when the application stops. H2 console access is disabled.

### Run the end-to-end demo

Start the demo profile with:

```shell
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

On Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

The demo profile loads `src/main/resources/sample/transactions.json`, ingests the batch, retries the configured transient failure, expires the deliberately missing sequence, and prints the report. It also writes JSON to `output/processing-report.json`. The corresponding captured text report is [`sample-output.txt`](sample-output.txt).

The demo configuration uses a 2-second retry delay, a 10-second pending timeout, and enables one simulated failure for `TXN-2005`. The main application configuration still controls scheduling; this runner also explicitly polls the resilience jobs while waiting for the batch to settle.

## Sample input

`src/main/resources/sample/transactions.json` contains a JSON array of raw request objects. Amounts and sequence numbers are strings because ingestion accepts raw strings and validates/converts them. Sequence numbers are per account.

The first three entries demonstrate out-of-order delivery followed by a duplicate:

```json
[
  {
    "transactionId": "TXN-2002",
    "requestId": "REQ-2",
    "accountId": "ACC-201",
    "transactionType": "DEBIT",
    "amount": "40",
    "currency": "USD",
    "sequenceNumber": "2"
  },
  {
    "transactionId": "TXN-2001",
    "requestId": "REQ-1",
    "accountId": "ACC-201",
    "transactionType": "CREDIT",
    "amount": "100",
    "currency": "USD",
    "sequenceNumber": "1"
  },
  {
    "transactionId": "TXN-2002",
    "requestId": "REQ-2-DUP",
    "accountId": "ACC-201",
    "transactionType": "DEBIT",
    "amount": "40",
    "currency": "USD",
    "sequenceNumber": "2"
  }
]
```

The full sample also includes a negative amount, a transaction configured to fail once and retry, an insufficient-funds debit, a missing sequence that times out, and a request missing `transactionId`. The full source file is the authoritative sample for the demo.

### Demo result summary

After retries and timeout processing, the expected account balances are:

| Account | Balance | Next expected sequence | Result |
|---|---:|---:|---|
| `ACC-201` | 85.00 | 4 | Credit 100, debit 40, credit 25 |
| `ACC-202` | 10.00 | 3 | Invalid sequence 1 fails; credit at sequence 2 succeeds |
| `ACC-203` | 500.00 | 2 | Credit succeeds on its second execution |
| `ACC-204` | 0.00 | 2 | Debit fails for insufficient funds |
| `ACC-205` | 0.00 | 1 | Sequence 2 times out; sequence 1 remains a gap |

Expected totals are 5 `PROCESSED`, 4 `FAILED` (three transaction rows plus one rejected malformed request in the report's failed total), and 1 duplicate request. `TXN-2005` has `attemptCount: 2`. The integrity check should report `ledgerBalancesMatch: true`.

The demo report is captured in [`sample-output.txt`](sample-output.txt); the runnable demo also writes machine-readable JSON to `output/processing-report.json`.

## API request and response models

All endpoints use JSON and the `/api` prefix.

### `POST /api/transactions`

Accepts a JSON array of raw transaction request objects and returns an array of per-record `IngestionOutcome` strings in the same order. Batch ingestion isolates each record, then drains each affected account after ingestion writes commit. Therefore, a returned `PENDING` outcome describes its ingestion decision; the corresponding transaction may already have become `PROCESSED` or `FAILED` by the time the response is returned.

Raw request shape:

| Field | JSON type | Validation/use |
|---|---|---|
| `transactionId` | String or `null` | Idempotency key; required for a transaction to own a sequence |
| `requestId` | String or `null` | Request correlation identifier; required by transaction validation |
| `accountId` | String or `null` | Account key; required and must match `[A-Za-z0-9-]{1,32}` |
| `transactionType` | String or `null` | `CREDIT` or `DEBIT` |
| `amount` | String or `null` | Positive decimal with at most two fractional digits |
| `currency` | String or `null` | Three uppercase letters |
| `sequenceNumber` | String or `null` | Positive 64-bit integer, scoped to the account |

Example:

```http
POST /api/transactions
Content-Type: application/json
```

```json
[
  {
    "transactionId": "API-TXN-1",
    "requestId": "API-REQ-1",
    "accountId": "API-ACCOUNT",
    "transactionType": "CREDIT",
    "amount": "25.00",
    "currency": "USD",
    "sequenceNumber": "1"
  }
]
```

Successful HTTP response (`200 OK`):

```json
["PENDING"]
```

Possible outcomes are `PENDING`, `FAILED`, `DUPLICATE`, `REJECTED_MALFORMED`, `REJECTED_STALE_SEQUENCE`, and `REJECTED_SEQUENCE_CONFLICT`. Invalid transaction fields can produce `FAILED` while retaining a sequence slot; missing or unparseable transaction ID/account/sequence information produces a rejected record instead.

### `GET /api/transactions/{id}`

Returns the persisted original transaction, including lifecycle, retry, and outcome fields.

Example response (`200 OK`):

```json
{
  "transactionId": "API-TXN-1",
  "requestId": "API-REQ-1",
  "accountId": "API-ACCOUNT",
  "sequenceNumber": 1,
  "type": "CREDIT",
  "amount": 25.00,
  "currency": "USD",
  "status": "PROCESSED",
  "pendingReason": null,
  "failureReason": null,
  "attemptCount": 1,
  "nextAttemptAt": null,
  "receivedAt": "2026-10-14T03:00:00Z",
  "updatedAt": "2026-10-14T03:00:00Z",
  "processedAt": "2026-10-14T03:00:00Z"
}
```

The timestamp values above are illustrative; returned times are generated by the application clock. An unknown transaction ID returns HTTP `404`.

### `GET /api/accounts/{id}`

Returns account state:

```json
{
  "accountId": "API-ACCOUNT",
  "balance": 25.00,
  "nextExpectedSeq": 2,
  "version": 2
}
```

An account that has not been created returns HTTP `404`.

### `GET /api/report`

Returns the same report model as JSON. It includes counts by transaction status, counts by pending and failure reason, duplicate count, retry totals, per-account summaries, rejected records with safe messages, and a ledger/balance integrity result.

Example response (abbreviated):

```json
{
  "countsByStatus": {
    "PENDING": 0,
    "PROCESSING": 0,
    "PROCESSED": 5,
    "FAILED": 4,
    "DUPLICATE": 1
  },
  "pendingByReason": {},
  "failedByReason": {
    "MALFORMED": 1,
    "INSUFFICIENT_FUNDS": 1,
    "SEQUENCE_TIMEOUT": 1,
    "VALIDATION_FAILED": 1
  },
  "duplicateCount": 1,
  "retriedTransactions": 1,
  "totalRetryAttempts": 1,
  "accounts": [
    {
      "accountId": "ACC-201",
      "balance": 85.00,
      "nextExpectedSeq": 4,
      "pendingCount": 0,
      "gaps": [],
      "oldestPendingAge": "PT0S"
    },
    {
      "accountId": "ACC-205",
      "balance": 0.00,
      "nextExpectedSeq": 1,
      "pendingCount": 0,
      "gaps": [{"from": 1, "to": 1}],
      "oldestPendingAge": "PT0S"
    }
  ],
  "rejectedRecords": [
    {
      "id": 1,
      "transactionId": null,
      "requestId": "REQ-MALFORMED",
      "accountId": "ACC-206",
      "sequenceNumber": 1,
      "failureReason": "MALFORMED",
      "safeMessage": "Required transaction identifiers are missing or invalid",
      "receivedAt": "2026-10-14T03:00:00Z"
    }
  ],
  "ledgerBalancesMatch": true
}
```

`oldestPendingAge` is a Java `Duration` serialized as an ISO-8601 duration. Gap ranges are inclusive. Rejected records contribute to failed totals but do not own transaction sequence slots.

### Error response shape

API errors contain only `code` and `message`; stack traces and internal exception details are not returned.

Malformed JSON or a null batch body returns HTTP `400`:

```json
{
  "code": "MALFORMED_REQUEST",
  "message": "Request body is invalid"
}
```

An unknown transaction or account returns HTTP `404`:

```json
{
  "code": "NOT_FOUND",
  "message": "Requested resource was not found"
}
```

Unexpected server errors return HTTP `500` with a generic `INTERNAL_ERROR` body.
