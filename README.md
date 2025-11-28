# Experiments with new Cursor Service

## Token Generation

The `CURSOR_API_KEY` cannot be used directly for gRPC calls. It must be exchanged for a JWT `accessToken`.

### Using Curl

```bash
# Export your API key
export CURSOR_API_KEY="your_api_key_here"

# Exchange API key for JWT
curl -X POST https://api2.cursor.sh/auth/exchange_user_api_key \
  -H "Authorization: Bearer $CURSOR_API_KEY" \
  -H "Content-Type: application/json" \
  -d "{}"
```

The response JSON contains `accessToken` which should be used in the `authorization` header for gRPC calls.

## TypeScript Diagnostic Tool (`hello-agent.ts`)

The `hello-agent.ts` script has been evolved to:
1.  **Intercept Requests:** Patches `@cursor-ai/january` to log gRPC requests headers to `proto/network.log`.
2.  **Verify gRPC Support:** Patches the transport creation to use `createGrpcTransport`, confirming that `api2.cursor.sh` supports standard gRPC over HTTP/2.
3.  **Inspect Response:** Uses reflection to inspect the structure of received messages.

To run:
```bash
export CURSOR_API_KEY="your_key"
npx tsx hello-agent.ts
```

## Java Implementation (`maven-demo`)

The Java implementation demonstrates:
1.  Dynamic API Key exchange.
2.  Standard gRPC connection setup using `io.grpc`.

To run:
```bash
export CURSOR_API_KEY="your_key"
cd maven-demo
./mvnw clean compile exec:java
```
