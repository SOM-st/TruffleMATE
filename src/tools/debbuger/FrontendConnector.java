package tools.debugger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.java_websocket.WebSocket;

import com.google.gson.Gson;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.sun.net.httpserver.HttpServer;

import som.VmSettings;
import som.interpreter.actors.Actor;
import som.vmobjects.SSymbol;
import tools.SourceCoordinate;
import tools.SourceCoordinate.TaggedSourceCoordinate;
import tools.Tagging;
import tools.actors.ActorExecutionTrace;
import tools.debugger.frontend.Suspension;
import tools.debugger.message.Message;
import tools.debugger.message.Message.OutgoingMessage;
import tools.debugger.message.ScopesResponse;
import tools.debugger.message.SourceMessage;
import tools.debugger.message.SourceMessage.SourceData;
import tools.debugger.message.StackTraceResponse;
import tools.debugger.message.StoppedMessage;
import tools.debugger.message.SuspendedEventMessage;
import tools.debugger.message.SymbolMessage;
import tools.debugger.message.VariablesResponse;
import tools.debugger.session.AsyncMessageReceiverBreakpoint;
import tools.debugger.session.Breakpoints;
import tools.debugger.session.LineBreakpoint;
import tools.debugger.session.MessageReceiverBreakpoint;
import tools.debugger.session.MessageSenderBreakpoint;
import tools.debugger.session.PromiseResolutionBreakpoint;
import tools.debugger.session.PromiseResolverBreakpoint;

/**
 * Connect the debugger to the UI front-end.
 */
public class FrontendConnector {

  private Instrumenter instrumenter;

  private final Breakpoints breakpoints;
  private final WebDebugger webDebugger;

  /**
   * Serves the static resources.
   */
  private final HttpServer contentServer;

  /**
   * Receives requests from the client.
   */
  private final WebSocketHandler receiver;
  private final BinaryWebSocketHandler binaryHandler;

  /**
   * Sends requests to the client.
   */
  private WebSocket sender;

  private WebSocket binarySender;

  /**
   * Future to await the client's connection.
   */
  private CompletableFuture<WebSocket> clientConnected;

  private final Gson gson;
  private static final int MESSAGE_PORT = 7977;
  private static final int BINARY_PORT = 7978;
  private static final int DEBUGGER_PORT = 8888;

  private final ArrayList<Source> notReady = new ArrayList<>(); // TODO rename: toBeSend

  public FrontendConnector(final Breakpoints breakpoints,
      final Instrumenter instrumenter, final WebDebugger webDebugger,
      final Gson gson) {
    this.instrumenter = instrumenter;
    this.breakpoints = breakpoints;
    this.webDebugger = webDebugger;
    this.gson = gson;

    clientConnected = new CompletableFuture<WebSocket>();

    try {
      log("[DEBUGGER] Initialize HTTP and WebSocket Server for Debugger");
      receiver = initializeWebSocket(MESSAGE_PORT, clientConnected);
      log("[DEBUGGER] Started WebSocket Server");

      binaryHandler = new BinaryWebSocketHandler(new InetSocketAddress(BINARY_PORT));
      binaryHandler.start();

      contentServer = initializeHttpServer(DEBUGGER_PORT);
      log("[DEBUGGER] Started HTTP Server");
      log("[DEBUGGER]   URL: http://localhost:" + DEBUGGER_PORT + "/index.html");
    } catch (IOException e) {
      log("Failed starting WebSocket and/or HTTP Server");
      throw new RuntimeException(e);
    }
    // now we continue execution, but we wait for the future in the execution
    // event
  }

  private WebSocketHandler initializeWebSocket(final int port,
      final Future<WebSocket> clientConnected) {
    InetSocketAddress address = new InetSocketAddress(port);
    WebSocketHandler server = new WebSocketHandler(address, this, gson);
    server.start();
    return server;
  }

  private HttpServer initializeHttpServer(final int port) throws IOException {
    InetSocketAddress address = new InetSocketAddress(port);
    HttpServer httpServer = HttpServer.create(address, 0);
    httpServer.createContext("/", new WebResourceHandler());
    httpServer.setExecutor(null);
    httpServer.start();
    return httpServer;
  }

  private void ensureConnectionIsAvailable() {
    assert receiver != null;
    assert sender != null;
    assert sender.isOpen();
  }

  // TODO: simplify, way to convoluted
  private static TaggedSourceCoordinate[] createSourceSections(final Source source,
      final Map<Source, Map<SourceSection, Set<Class<? extends Tags>>>> sourcesTags,
      final Instrumenter instrumenter, final Set<RootNode> rootNodes) {
    Set<SourceSection> sections = new HashSet<>();
    Map<SourceSection, Set<Class<? extends Tags>>> tagsForSections = sourcesTags.get(source);

    if (tagsForSections != null) {
      Tagging.collectSourceSectionsAndTags(rootNodes, tagsForSections, instrumenter);
      for (SourceSection section : tagsForSections.keySet()) {
        if (section.getSource() == source) {
          sections.add(section);
        }
      }
    }

    TaggedSourceCoordinate[] result = new TaggedSourceCoordinate[sections.size()];
    int i = 0;
    for (SourceSection section : sections) {
      result[i] = SourceCoordinate.create(section, tagsForSections.get(section));
      i += 1;
    }

    return result;
  }

  private void sendSource(final Source source,
      final Map<Source, Map<SourceSection, Set<Class<? extends Tags>>>> loadedSourcesTags,
      final Set<RootNode> rootNodes) {
    SourceData[] sources = new SourceData[1];
    sources[0] = new SourceData(source.getCode(), source.getMimeType(),
        source.getName(), source.getURI().toString(),
        createSourceSections(source, loadedSourcesTags, instrumenter, rootNodes),
        SourceMessage.createMethodDefinitions(rootNodes));
    send(new SourceMessage(sources));
  }

  private void send(final Message msg) {
    ensureConnectionIsAvailable();
    sender.send(gson.toJson(msg, OutgoingMessage.class));
  }

  private void sendBufferedSources(
      final Map<Source, Map<SourceSection, Set<Class<? extends Tags>>>> loadedSourcesTags,
      final Map<Source, Set<RootNode>> rootNodes) {
    if (!notReady.isEmpty()) {
      for (Source s : notReady) {
        sendSource(s, loadedSourcesTags, rootNodes.get(s));
      }
      notReady.clear();
    }
  }

  public void sendLoadedSource(final Source source,
      final Map<Source, Map<SourceSection, Set<Class<? extends Tags>>>> loadedSourcesTags,
      final Map<Source, Set<RootNode>> rootNodes) {
    if (receiver == null || sender == null) {
      notReady.add(source);
      return;
    }

    ensureConnectionIsAvailable();
    sendBufferedSources(loadedSourcesTags, rootNodes);
    sendSource(source, loadedSourcesTags, rootNodes.get(source));
  }

  public void sendSymbols(final ArrayList<SSymbol> symbolstowrite) {
    send(new SymbolMessage(symbolstowrite));
  }

  public void sendTracingData(final ByteBuffer b) {
    binarySender.send(b);
  }

  public void awaitClient() {
    assert clientConnected != null;
    assert binaryHandler.getConnection() != null;
    log("[DEBUGGER] Waiting for debugger to connect.");
    try {
      sender = clientConnected.get();
      if (VmSettings.ACTOR_TRACING) {
        binarySender = binaryHandler.getConnection().get();
      }
    } catch (InterruptedException | ExecutionException ex) {
      throw new RuntimeException(ex);
    }
    ActorExecutionTrace.setFrontEnd(this);
    log("[DEBUGGER] Debugger connected.");
  }

  public void sendSuspendedEvent(final Suspension suspension) {
    sendTracingData();
    send(SuspendedEventMessage.create(
        suspension.getEvent(),
        SUSPENDED_EVENT_ID_PREFIX + suspension.activityId));
  }

  public void sendStackTrace(final int startFrame, final int levels,
      final Suspension suspension, final int requestId) {
    send(StackTraceResponse.create(startFrame, levels, suspension, requestId));
  }

  public void sendScopes(final int frameId, final Suspension suspension,
      final int requestId) {
    send(ScopesResponse.create(frameId, suspension, requestId));
  }

  public void sendVariables(final int varRef, final int requestId, final Suspension suspension) {
    send(VariablesResponse.create(varRef, requestId, suspension));
  }
  private static final String SUSPENDED_EVENT_ID_PREFIX = "se-";

  public void sendStoppedMessage(final Suspension suspension) {
    send(StoppedMessage.create(suspension));
  }

  public void sendTracingData() {
    if (VmSettings.ACTOR_TRACING) {
      Actor.forceSwapBuffers();
    }
  }

  public void registerOrUpdate(final LineBreakpoint bp) {
    breakpoints.addOrUpdate(bp);
  }

  public void registerOrUpdate(final MessageSenderBreakpoint bp) {
    breakpoints.addOrUpdate(bp);
  }

  public void registerOrUpdate(final MessageReceiverBreakpoint bp) {
    breakpoints.addOrUpdate(bp);
  }

  public void registerOrUpdate(final AsyncMessageReceiverBreakpoint bp) {
    breakpoints.addOrUpdate(bp);
  }

  public void registerOrUpdate(final PromiseResolutionBreakpoint bp) {
    breakpoints.addOrUpdate(bp);
  }

  public void registerOrUpdate(final PromiseResolverBreakpoint bp) {
    breakpoints.addOrUpdate(bp);
  }

  public Suspension getSuspension(final int activityId) {
    return webDebugger.getSuspension(activityId);
  }

  public Suspension getSuspension(final String suspendedEventId) {
    int activityId = Integer.valueOf(suspendedEventId.substring(SUSPENDED_EVENT_ID_PREFIX.length()));
    return webDebugger.getSuspension(activityId);
  }

  public Suspension getSuspensionForGlobalId(final int globalId) {
    return webDebugger.getSuspension(Suspension.getActivityIdFromGlobalId(globalId));
  }

  public SuspendedEvent getSuspendedEvent(final String id) {
    int activityId = Integer.valueOf(id.substring(SUSPENDED_EVENT_ID_PREFIX.length()));
    return webDebugger.getSuspendedEvent(activityId);
  }

  static void log(final String str) {
    // Checkstyle: stop
    System.out.println(str);
    // Checkstyle: resume
  }

  public void completeConnection(final WebSocket conn, final boolean debuggerProtocol) {
    clientConnected.complete(conn);
    webDebugger.useDebuggerProtocol(debuggerProtocol);
  }

  public void shutdown() {
    int delaySec = 5;
    contentServer.stop(delaySec);

    sender.close();
    if (binarySender != null) {
      binarySender.close();
    }
    try {
      int delayMsec = 1000;
      receiver.stop(delayMsec);
      if (binarySender != null) {
        binaryHandler.stop(delayMsec);
      }
    } catch (InterruptedException e) { }
  }
}
