package tools.debugger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession.SteppingLocation;
import com.oracle.truffle.api.debug.SuspendedCallback;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Registration;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import gson.ClassHierarchyAdapterFactory;
import tools.debugger.message.InitialBreakpointsResponds;
import tools.debugger.message.Message;
import tools.debugger.message.MessageHistory;
import tools.debugger.message.Respond;
import tools.debugger.message.SourceMessage;
import tools.debugger.message.StepMessage.Resume;
import tools.debugger.message.StepMessage.Return;
import tools.debugger.message.StepMessage.StepInto;
import tools.debugger.message.StepMessage.StepOver;
import tools.debugger.message.StepMessage.Stop;
import tools.debugger.message.SuspendedEventMessage;
import tools.debugger.message.UpdateBreakpoint;
import tools.debugger.session.AsyncMessageReceiveBreakpoint;
import tools.debugger.session.BreakpointInfo;
import tools.debugger.session.Breakpoints;
import tools.debugger.session.LineBreakpoint;
import tools.debugger.session.MessageReceiveBreakpoint;
import tools.debugger.session.MessageSenderBreakpoint;


/**
 * The WebDebugger connects the Truffle debugging facilities with a HTML5
 * application using WebSockets and JSON.
 */
@Registration(id = WebDebugger.ID)
public class WebDebugger extends TruffleInstrument implements SuspendedCallback {

  public static final String ID = "web-debugger";

  private FrontendConnector connector;

  private Instrumenter instrumenter;

  private final Map<Source, Map<SourceSection, Set<Class<? extends Tags>>>> loadedSourcesTags = new HashMap<>();
  private final Map<Source, Set<RootNode>> rootNodes = new HashMap<>();

  private Breakpoints breakpoints;

  private int nextSuspendEventId = 0;
  private final Map<String, SuspendedEvent> suspendEvents  = new HashMap<>();
  private final Map<String, CompletableFuture<Object>> suspendFutures = new HashMap<>();

  public void reportSyntaxElement(final Class<? extends Tags> type,
      final SourceSection source) {
    Map<SourceSection, Set<Class<? extends Tags>>> sections = loadedSourcesTags.computeIfAbsent(
        source.getSource(), s -> new HashMap<>());
    Set<Class<? extends Tags>> tags = sections.computeIfAbsent(source, s -> new HashSet<>(2));
    tags.add(type);
  }

  public void reportLoadedSource(final Source source) {
    connector.sendLoadedSource(source, loadedSourcesTags, rootNodes);
  }

  public void reportRootNodeAfterParsing(final RootNode rootNode) {
    assert rootNode.getSourceSection() != null : "RootNode without source section";
    Set<RootNode> roots = rootNodes.computeIfAbsent(
        rootNode.getSourceSection().getSource(), s -> new HashSet<>());
    assert !roots.contains(rootNode) : "This method was parsed twice? should not happen";
    roots.add(rootNode);
  }

  SuspendedEvent getSuspendedEvent(final String id) {
    return suspendEvents.get(id);
  }

  CompletableFuture<Object> getSuspendFuture(final String id) {
    return suspendFutures.get(id);
  }

  private String getNextSuspendEventId() {
    int id = nextSuspendEventId;
    nextSuspendEventId += 1;
    return "se-" + id;
  }

  public void prepareSteppingUntilNextRootNode() {
    breakpoints.prepareSteppingUntilNextRootNode();
  }

  @Override
  public void onSuspend(final SuspendedEvent e) {
    // TODO: I need to capture the stack here, to make sure it is accessible
    //       also when running on Graal
    String id = getNextSuspendEventId();
    CompletableFuture<Object> future = new CompletableFuture<>();
    suspendEvents.put(id, e);
    suspendFutures.put(id, future);

    connector.sendSuspendedEvent(e, id);

    try {
      future.get();
    } catch (InterruptedException | ExecutionException e1) {
      log("[DEBUGGER] Future failed:");
      e1.printStackTrace();
    }
  }

  public void suspendExecution(final MaterializedFrame haltedFrame,
      final SteppingLocation steppingLocation) {
    breakpoints.doSuspend(haltedFrame, steppingLocation);
  }

  public static void log(final String str) {
    // Checkstyle: stop
    System.out.println(str);
    // Checkstyle: resume
  }

  @Override
  protected void onDispose(final Env env) {
    connector.sendActorHistory();
    connector.shutdown();
  }

  @Override
  protected void onCreate(final Env env) {
    instrumenter = env.getInstrumenter();
    env.registerService(this);
  }

  public void startServer(final Debugger dbg) {
    breakpoints = new Breakpoints(dbg, this);
    connector = new FrontendConnector(breakpoints, instrumenter, this,
        createJsonProcessor());
    connector.awaitClient();
  }

  public Breakpoints getBreakpoints() {
    return breakpoints;
  }

  // TODO: to be removed
  private static final String INITIAL_BREAKPOINTS = "initialBreakpoints";
  private static final String UPDATE_BREAKPOINT   = "updateBreakpoint";

  public static Gson createJsonProcessor() {
    ClassHierarchyAdapterFactory<Message> msgAF = new ClassHierarchyAdapterFactory<>(Message.class, "type");
    msgAF.register("source",       SourceMessage.class);
    msgAF.register("suspendEvent", SuspendedEventMessage.class);
    msgAF.register("messageHistory", MessageHistory.class);

    ClassHierarchyAdapterFactory<Respond> respondAF = new ClassHierarchyAdapterFactory<>(Respond.class, "action");
    respondAF.register(INITIAL_BREAKPOINTS, InitialBreakpointsResponds.class);
    respondAF.register(UPDATE_BREAKPOINT,   UpdateBreakpoint.class);
    respondAF.register("stepInto", StepInto.class);
    respondAF.register("stepOver", StepOver.class);
    respondAF.register("return",   Return.class);
    respondAF.register("resume",   Resume.class);
    respondAF.register("stop",     Stop.class);

    ClassHierarchyAdapterFactory<BreakpointInfo> breakpointAF = new ClassHierarchyAdapterFactory<>(BreakpointInfo.class, "type");
    breakpointAF.register(LineBreakpoint.class);
    breakpointAF.register(MessageSenderBreakpoint.class);
    breakpointAF.register(MessageReceiveBreakpoint.class);
    breakpointAF.register(AsyncMessageReceiveBreakpoint.class);

    return new GsonBuilder().
        registerTypeAdapterFactory(msgAF).
        registerTypeAdapterFactory(respondAF).
        registerTypeAdapterFactory(breakpointAF).
        create();
  }
}
