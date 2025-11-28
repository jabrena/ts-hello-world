package info.jab.demo;

import io.github.cdimascio.dotenv.Dotenv;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import agent.v1.AgentServiceGrpc;
import agent.v1.Agent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.MethodDescriptor;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.Channel;
import io.grpc.CallOptions;
import io.grpc.stub.ClientCalls;
import java.io.InputStream;
import java.io.IOException;
import java.io.File;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class App {

    private static final MethodDescriptor.Marshaller<Agent.AgentServerMessage> debugMarshaller = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(Agent.AgentServerMessage value) {
            return value.toByteString().newInput();
        }

        @Override
        public Agent.AgentServerMessage parse(InputStream stream) {
            System.out.println("DEBUG: parse called!"); 
            try {
                byte[] bytes = stream.readAllBytes();
                System.out.println("DEBUG: Received bytes (" + bytes.length + "): " + HexFormat.of().formatHex(bytes));
                // System.out.println("DEBUG: As String: " + new String(bytes)); // Optional
                return Agent.AgentServerMessage.parseFrom(bytes);
            } catch (IOException e) {
                System.out.println("DEBUG: Parse error: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
    };

    public static void main(String[] args) throws Exception {
        Dotenv dotenv = Dotenv.configure()
                .directory("/workspaces/ts-hello-world/")
                .filename(".env")
                .ignoreIfMissing()
                .load();
                
        String apiKey = dotenv.get("CURSOR_API_KEY");
        if (apiKey == null) apiKey = System.getenv("CURSOR_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("CURSOR_API_KEY not found");
            return;
        }

        System.out.println("Connecting to app.cursor.sh...");
        String accessToken = fetchAccessToken(apiKey);
        System.out.println("Successfully obtained Access Token");

        ManagedChannel channel = ManagedChannelBuilder.forAddress("api2.cursor.sh", 443)
                .useTransportSecurity()
                .build();

        try {
            Metadata metadata = new Metadata();
            metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + accessToken);
            metadata.put(Metadata.Key.of("backend-traceparent", Metadata.ASCII_STRING_MARSHALLER), "00-00000000000000000000000000000000-0000000000000000-00");
            metadata.put(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER), "00-00000000000000000000000000000000-0000000000000000-00");
            metadata.put(Metadata.Key.of("user-agent", Metadata.ASCII_STRING_MARSHALLER), "connect-es/1.7.0");
            metadata.put(Metadata.Key.of("x-cursor-client-version", Metadata.ASCII_STRING_MARSHALLER), "sdk-0.0.0");
            metadata.put(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER), UUID.randomUUID().toString());
            // metadata.put(Metadata.Key.of("grpc-accept-encoding", Metadata.ASCII_STRING_MARSHALLER), "identity");
            
            MethodDescriptor<Agent.AgentClientMessage, Agent.AgentServerMessage> originalMethod = AgentServiceGrpc.getRunMethod();
            MethodDescriptor<Agent.AgentClientMessage, Agent.AgentServerMessage> debugMethod = originalMethod.toBuilder()
                .setResponseMarshaller(debugMarshaller)
                .build();

            Channel interceptedChannel = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata));
            ClientCall<Agent.AgentClientMessage, Agent.AgentServerMessage> call = interceptedChannel.newCall(debugMethod, CallOptions.DEFAULT);

            CountDownLatch finishLatch = new CountDownLatch(1);
            AtomicReference<StreamObserver<Agent.AgentClientMessage>> requestStreamRef = new AtomicReference<>();

            StreamObserver<Agent.AgentServerMessage> responseObserver = new StreamObserver<Agent.AgentServerMessage>() {
                @Override
                public void onNext(Agent.AgentServerMessage value) {
                    System.out.println("Received message: " + value);
                    
                    if (value.hasExecServerMessage()) {
                        System.out.println("Received ExecServerMessage. Sending Response...");
                        Agent.ExecServerMessage serverMsg = value.getExecServerMessage();
                        Agent.AgentClientMessage reply = createExecClientMessage();
                                
                        System.err.println("DEBUG: Sending ExecClientMessage bytes: " + HexFormat.of().formatHex(reply.toByteArray()));
                        StreamObserver<Agent.AgentClientMessage> observer = requestStreamRef.get();
                        if (observer != null) {
                            observer.onNext(reply);
                            
                            // Send stream close
                            Agent.ExecClientControlMessage controlMsg = Agent.ExecClientControlMessage.newBuilder()
                                    .setStreamClose(Agent.ExecClientStreamClose.newBuilder().build())
                                    .build();
                            Agent.AgentClientMessage controlReply = Agent.AgentClientMessage.newBuilder()
                                    .setExecClientControlMessage(controlMsg)
                                    .build();
                            System.err.println("DEBUG: Sending ExecClientControlMessage bytes: " + HexFormat.of().formatHex(controlReply.toByteArray()));
                            observer.onNext(controlReply);
                        } else {
                            System.err.println("Request stream observer is null!");
                        }
                    }
                }

                @Override
                public void onError(Throwable t) {
                    System.err.println("Error in observer: " + t.getMessage());
                    finishLatch.countDown();
                }

                @Override
                public void onCompleted() {
                    System.out.println("Finished");
                    finishLatch.countDown();
                }
            };

            StreamObserver<Agent.AgentClientMessage> requestObserver = ClientCalls.asyncBidiStreamingCall(call, responseObserver);
            requestStreamRef.set(requestObserver);

            System.out.println("Sending RunRequest...");
            Agent.AgentClientMessage runMsg = createRunRequest();
            System.err.println("DEBUG: Sending bytes: " + HexFormat.of().formatHex(runMsg.toByteArray()));
            requestObserver.onNext(runMsg);
            
            if (!finishLatch.await(30, TimeUnit.SECONDS)) {
                System.out.println("Timeout waiting for response.");
            }
            requestObserver.onCompleted();

        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static Agent.ProjectLayout buildProjectLayout(File dir) {
        Agent.ProjectLayout.Builder builder = Agent.ProjectLayout.newBuilder()
                .setAbsPath(dir.getAbsolutePath())
                .setChildrenWereProcessed(true);

        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                if (file.isDirectory()) {
                    if (file.getName().equals("target") || file.getName().equals("node_modules") || file.getName().startsWith(".")) continue;
                    builder.addChildrenDirs(buildProjectLayout(file));
                } else {
                    builder.addChildrenFiles(Agent.FileEntry.newBuilder().setName(file.getName()).build());
                }
            }
        }
        return builder.build();
    }

    private static Agent.AgentClientMessage createExecClientMessage() {
        try {
            // Use parent directory to match TS client behavior
            File rootDir = new File("..").getCanonicalFile();
            
            Agent.RequestContextEnv env = Agent.RequestContextEnv.newBuilder()
                .setOsVersion("linux 6.10.14-linuxkit")
                .addWorkspacePaths(rootDir.getAbsolutePath())
                .setShell("zsh")
                .setTerminalsFolder("/home/vscode/.cursor/projects/workspaces-ts-hello-world/terminals")
                .setAgentSharedNotesFolder("/home/vscode/.cursor/projects/workspaces-ts-hello-world/agent-notes/shared")
                .build();

            Agent.ProjectLayout rootLayout = buildProjectLayout(rootDir);

            String gitStatus = "On branch feature/gprc\nChanges not staged for commit:\n  (use \"git add <file>...\" to update what will be committed)\n  (use \"git restore <file>...\" to discard changes in working directory)\n\tmodified:   README.md\n\tmodified:   hello-agent.ts\n\tmodified:   package-lock.json\n\tmodified:   package.json\n\nUntracked files:\n  (use \"git add <file>...\" to include in what will be committed)\n\tdecode_test.cjs\n\tframe_header\n\tinspect_schema.ts\n\tmaven-demo/\n\tproto/\n\trequest.grpc\n\trequest.pb\n\tresponse.grpc\n\nno changes added to commit (use \"git add\" and/or \"git commit -a\")\n";

            Agent.RequestContext ctx = Agent.RequestContext.newBuilder()
                .setWorkspacePath(rootDir.getAbsolutePath())
                .setEnv(env)
                .setSharedNotesListing("(No notes directory yet - will be created when you write your first note)")
                .addGitRepos(Agent.GitRepo.newBuilder().setPath(rootDir.getAbsolutePath()).setStatus(gitStatus).build())
                .addProjectLayouts(rootLayout)
                .build();

            Agent.RequestContextSuccess success = Agent.RequestContextSuccess.newBuilder()
                .setRequestContext(ctx)
                .build();

            Agent.RequestContextResult result = Agent.RequestContextResult.newBuilder()
                .setSuccess(success)
                .build();

            Agent.ExecClientMessage execMsg = Agent.ExecClientMessage.newBuilder()
                    //.setId(reqId)
                    .setRequestContextResult(result)
                    .build();
            
            return Agent.AgentClientMessage.newBuilder()
                    .setExecClientMessage(execMsg)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Agent.AgentClientMessage createRunRequest() {
        Agent.UserMessage userMessage = Agent.UserMessage.newBuilder()
                .setText("Can you say the days of the week?")
                .build();

        Agent.UserMessageAction userMsgAction = Agent.UserMessageAction.newBuilder()
                .setUserMessage(userMessage)
                .build();
        
        Agent.ConversationAction action = Agent.ConversationAction.newBuilder()
                .setUserMessageAction(userMsgAction)
                .build();
        
        Agent.ModelDetails modelDetails = Agent.ModelDetails.newBuilder()
                .setModelName("default")
                .build();
        
        Agent.ConversationStateStructure state = Agent.ConversationStateStructure.newBuilder().build();
        Agent.McpTools mcpTools = Agent.McpTools.newBuilder().build();
        String conversationId = UUID.randomUUID().toString();

        Agent.AgentRunRequest runRequest = Agent.AgentRunRequest.newBuilder()
                .setConversationState(state)
                .setAction(action)
                .setModelDetails(modelDetails)
                .setMcpTools(mcpTools)
                .setConversationId(conversationId)
                .build();

        return Agent.AgentClientMessage.newBuilder()
                .setRunRequest(runRequest)
                .build();
    }

    private static String fetchAccessToken(String apiKey) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api2.cursor.sh/auth/exchange_user_api_key"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to exchange API key. Status: " + response.statusCode() + ", Body: " + response.body());
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(response.body());
        return rootNode.get("accessToken").asText();
    }
}
