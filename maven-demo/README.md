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

### Java Implementation

The `App.java` example demonstrates:
1. Loading `CURSOR_API_KEY` from `.env` or environment variables.
2. Exchanging the API key for a JWT using `java.net.http.HttpClient`.
3. Connecting to `api2.cursor.sh` using gRPC.

To run the demo:

```bash
# Ensure you have your key exported or in .env
export CURSOR_API_KEY="your_key"
cd maven-demo
./mvnw clean compile exec:java
```

**Note:** The gRPC connection currently establishes successfully but may encounter protocol parsing errors due to differences between standard gRPC and the Connect protocol used by the server, or mismatches in the protobuf definition.
