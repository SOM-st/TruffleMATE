package tools.debugger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
import som.interpreter.actors.Actor.ActorProcessingThread;
import som.primitives.threading.ThreadPrimitives.SomThread;
import tools.debugger.frontend.Suspension;
import tools.debugger.message.InitialBreakpointsMessage;
import tools.debugger.message.Message.IncommingMessage;
import tools.debugger.message.Message.OutgoingMessage;
import tools.debugger.message.ScopesRequest;
import tools.debugger.message.ScopesResponse;
import tools.debugger.message.SourceMessage;
import tools.debugger.message.StackTraceRequest;
import tools.debugger.message.StackTraceResponse;
import tools.debugger.message.StepMessage.Resume;
import tools.debugger.message.StepMessage.Return;
import tools.debugger.message.StepMessage.StepInto;
import tools.debugger.message.StepMessage.StepOver;
import tools.debugger.message.StepMessage.Stop;
import tools.debugger.message.StoppedMessage;
import tools.debugger.message.SuspendedEventMessage;
import tools.debugger.message.SymbolMessage;
import tools.debugger.message.UpdateBreakpoint;
import tools.debugger.message.VariablesRequest;
import tools.debugger.message.VariablesResponse;
import tools.debugger.session.AsyncMessageReceiverBreakpoint;
import tools.debugger.session.BreakpointInfo;
import tools.debugger.session.Breakpoints;
import tools.debugger.session.LineBreakpoint;
import tools.debugger.session.MessageReceiverBreakpoint;
import tools.debugger.session.MessageSenderBreakpoint;
import tools.debugger.session.PromiseResolutionBreakpoint;
import tools.debugger.session.PromiseResolverBreakpoint;


/**
 * The WebDebugger connects the Truffle debugging facilities with a HTML5
 * application using WebSockets and JSON.
 */
@Registration(id = WebDebugger.ID)
public class WebDebugger extends TruffleInstrument implements SuspendedCallback {

  public static final String ID = "web-debugger";

  private FrontendConnector connector;
  private Instrumenter      instrumenter;
  private Breakpoints       breakpoints;
  private boolean debuggerProtocol;

  private final Map<Source, Map<SourceSection, Set<Class<? extends Tags>>>> loadedSourcesTags = new HashMap<>();
  private final Map<Source, Set<RootNode>> rootNodes = new HashMap<>();

  private int nextActivityId = 0;
  private final Map<Object, Suspension> activityToSuspension = new HashMap<>();
  private final Map<Integer, Suspension> idToSuspension = new HashMap<>();

  public void useDebuggerProtocol(final boolean debuggerProtocol) {
    this.debuggerProtocol = debuggerProtocol;
  }

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

  public void prepareSteppingUntilNextRootNode() {
    breakpoints.prepareSteppingUntilNextRootNode();
  }

  public void prepareSteppingAfterNextRootNode() {
    breakpoints.prepareSteppingAfterNextRootNode();
  }

  synchronized SuspendedEvent getSuspendedEvent(final int activityId) {
    Suspension suspension = idToSuspension.get(activityId);
    assert suspension != null;
    assert suspension.getEvent() != null;
    return suspension.getEvent();
  }

  Suspension getSuspension(final int activityId) {
    return idToSuspension.get(activityId);
  }

  private synchronized Suspension getSuspension(final Object activity) {
    Suspension suspension = activityToSuspension.get(activity);
    if (suspension == null) {
      int id = nextActivityId;
      nextActivityId += 1;
      suspension = new Suspension(activity, id);

      activityToSuspension.put(activity, suspension);
      idToSuspension.put(id, suspension);
    }
    return suspension;
  }

  private Suspension getSuspension() {
    Thread thread = Thread.currentThread();
    Object current;
    if (thread instanceof ActorProcessingThread) {
      current = ((ActorProcessingThread) thread).currentMessage.getTarget();
    } else if (thread instanceof SomThread) {
      current = thread;
    } else {
      assert thread.getClass() == Thread.class : "Should support other thread subclasses explicitly";
      current = thread;
    }
    return getSuspension(current);
  }

  @Override
  public void onSuspend(final SuspendedEvent e) {
    Suspension suspension = getSuspension();
    suspension.update(e);

    if (debuggerProtocol) {
      connector.sendStoppedMessage(suspension);
    } else {
      connector.sendSuspendedEvent(suspension);
    }
    suspension.suspend();
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
    ClassHierarchyAdapterFactory<OutgoingMessage> outMsgAF = new ClassHierarchyAdapterFactory<>(OutgoingMessage.class, "type");
    outMsgAF.register("source",       SourceMessage.class);
    outMsgAF.register("suspendEvent", SuspendedEventMessage.class);
    outMsgAF.register("StoppedEvent", StoppedMessage.class);
    outMsgAF.register("symbolMessage",      SymbolMessage.class);
    outMsgAF.register("StackTraceResponse", StackTraceResponse.class);
    outMsgAF.register("ScopesResponse",     ScopesResponse.class);
    outMsgAF.register("VariablesResponse",  VariablesResponse.class);

    ClassHierarchyAdapterFactory<IncommingMessage> inMsgAF = new ClassHierarchyAdapterFactory<>(IncommingMessage.class, "action");
    inMsgAF.register(INITIAL_BREAKPOINTS, InitialBreakpointsMessage.class);
    inMsgAF.register(UPDATE_BREAKPOINT,   UpdateBreakpoint.class);
    inMsgAF.register("stepInto", StepInto.class);
    inMsgAF.register("stepOver", StepOver.class);
    inMsgAF.register("return",   Return.class);
    inMsgAF.register("resume",   Resume.class);
    inMsgAF.register("stop",     Stop.class);
    inMsgAF.register("StackTraceRequest", StackTraceRequest.class);
    inMsgAF.register("ScopesRequest",     ScopesRequest.class);
    inMsgAF.register("VariablesRequest",  VariablesRequest.class);

    ClassHierarchyAdapterFactory<BreakpointInfo> breakpointAF = new ClassHierarchyAdapterFactory<>(BreakpointInfo.class, "type");
    breakpointAF.register(LineBreakpoint.class);
    breakpointAF.register(MessageSenderBreakpoint.class);
    breakpointAF.register(MessageReceiverBreakpoint.class);
    breakpointAF.register(AsyncMessageReceiverBreakpoint.class);
    breakpointAF.register(PromiseResolutionBreakpoint.class);
    breakpointAF.register(PromiseResolverBreakpoint.class);

    return new GsonBuilder().
        registerTypeAdapterFactory(outMsgAF).
        registerTypeAdapterFactory(inMsgAF).
        registerTypeAdapterFactory(breakpointAF).
        create();
  }
}
