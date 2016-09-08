package tools.debugger.session;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.Breakpoint.SimpleCondition;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.DebuggerSession.SteppingLocation;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeVisitor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import som.interpreter.actors.ReceivedRootNode;
import som.interpreter.nodes.ExpressionNode;
import tools.debugger.WebDebugger;


public class Breakpoints {

  private DebuggerSession debuggerSession;

  private final WebDebugger webDebugger;
  private final Map<BreakpointId, Breakpoint> knownBreakpoints;
  private final Map<BreakpointId, ReceiverBreakpoint> receiverBreakpoints;
  private Assumption receiverBreakpointVersion;

  private final Debugger debugger;

  public Breakpoints(final Debugger debugger, final WebDebugger webDebugger) {
    this.knownBreakpoints = new HashMap<>();
    this.debugger    = debugger;
    this.webDebugger = webDebugger;
    this.receiverBreakpoints = new HashMap<>();
    this.receiverBreakpointVersion = Truffle.getRuntime().createAssumption("receiverBreakpointVersion");
  }

  private void ensureOpenDebuggerSession() {
    if (debuggerSession == null) {
      debuggerSession = debugger.startSession(webDebugger);
    }
  }

  public void doSuspend(final MaterializedFrame frame, final SteppingLocation steppingLocation) {
    ensureOpenDebuggerSession();
    debuggerSession.doSuspend(frame, steppingLocation);
  }

  public void prepareSteppingUntilNextRootNode() {
    ensureOpenDebuggerSession();
    debuggerSession.prepareSteppingUntilNextRootNode();
  }

  public abstract static class BreakpointId {

  }

  static class LineBreakpoint extends BreakpointId {
    private final URI sourceUri;
    private final int line;

    LineBreakpoint(final URI sourceUri, final int line) {
      this.sourceUri = sourceUri;
      this.line = line;
    }

    @Override
    public int hashCode() {
      return Objects.hash(sourceUri, line);
    }

    @Override
    public boolean equals(final Object obj) {
      if (obj == this) {
        return true;
      }
      if (obj == null || obj.getClass() != getClass()) {
        return false;
      }
      LineBreakpoint o = (LineBreakpoint) obj;
      return o.line == line && o.sourceUri.equals(sourceUri);
    }

    @Override
    public String toString() {
      return "LineBreakpoint[" + line + ", " + sourceUri.toString() + "]";
    }
  }

  public static class SectionBreakpoint extends BreakpointId {
    private final URI sourceUri;
    private final int startLine;
    private final int startColumn;
    private final int charLength;

    public SectionBreakpoint(final URI sourceUri, final int startLine,
        final int startColumn, final int charLength) {
      this.sourceUri = sourceUri;
      this.startLine = startLine;
      this.startColumn = startColumn;
      this.charLength  = charLength;
    }

    public SectionBreakpoint(final SourceSection section) {
      this(section.getSource().getURI(), section.getStartLine(),
          section.getStartColumn(), section.getCharLength());
    }

    @Override
    public int hashCode() {
      return Objects.hash(sourceUri, startLine, startColumn, charLength);
    }

    @Override
    public boolean equals(final Object obj) {
      if (obj == this) {
        return true;
      }
      if (obj == null || obj.getClass() != getClass()) {
        return false;
      }
      SectionBreakpoint o = (SectionBreakpoint) obj;
      return o.startLine == startLine && o.startColumn == startColumn
          && o.charLength == charLength && o.sourceUri.equals(sourceUri);
    }

    @Override
    public String toString() {
      return "SectionBreakpoint: startLine " + startLine + " startColumn "
          + startColumn + " charLength " + charLength + " sourceURi " + sourceUri;
    }
  }

  /**
   * Breakpoint on the RootTag node of a method.
   * The method is identified by the source section info of the breakpoint.
   */
  public static class RootBreakpoint extends SectionBreakpoint {
    public RootBreakpoint(final URI sourceUri, final int startLine,
        final int startColumn, final int charLength) {
      super(sourceUri, startLine, startColumn, charLength);
    }
  }

  public Breakpoint getLineBreakpoint(final URI sourceUri, final int line) throws IOException {
    BreakpointId bId = new LineBreakpoint(sourceUri, line);
    Breakpoint bp = knownBreakpoints.get(bId);

    if (bp == null) {
      ensureOpenDebuggerSession();
      WebDebugger.log("LineBreakpoint: " + bId);
      bp = Breakpoint.newBuilder(sourceUri).
          lineIs(line).
          build();
      debuggerSession.install(bp);
      knownBreakpoints.put(bId, bp);
    }
    return bp;
  }

  public Breakpoint getBreakpointOnSender(final URI sourceUri, final int startLine, final int startColumn, final int charLength) throws IOException {
    BreakpointId bId = new SectionBreakpoint(sourceUri, startLine, startColumn, charLength);
    Breakpoint bp = knownBreakpoints.get(bId);
    if (bp == null) {
      ensureOpenDebuggerSession();
      WebDebugger.log("SetSectionBreakpoint: " + bId);
      bp = Breakpoint.newBuilder(sourceUri).
          lineIs(startLine).
          columnIs(startColumn).
          sectionLength(charLength).
          build();
      debuggerSession.install(bp);
      knownBreakpoints.put(bId, bp);
    }
    return bp;
  }

  private static final class BreakWhenActivatedByAsyncMessage implements SimpleCondition {
    static BreakWhenActivatedByAsyncMessage INSTANCE = new BreakWhenActivatedByAsyncMessage();

    private BreakWhenActivatedByAsyncMessage() { }

    @Override
    public boolean evaluate() {
      RootCallTarget ct = (RootCallTarget) Truffle.getRuntime().getCallerFrame().getCallTarget();
      return (ct.getRootNode() instanceof ReceivedRootNode);
    }
  }

  private static final class FindRootTagNode implements NodeVisitor {

    private ExpressionNode result;

    public ExpressionNode getResult() {
      return result;
    }

    @Override
    public boolean visit(final Node node) {
      if (node instanceof ExpressionNode) {
        ExpressionNode expr = (ExpressionNode) node;
        if (expr.isMarkedAsRootExpression()) {
          result = expr;
          return false;
        }
      }
      return true;
    }

  }

  public Breakpoint getAsyncMessageRcvBreakpoint(final URI sourceUri,
      final int startLine, final int startColumn, final int charLength) throws IOException {
    BreakpointId bId = new RootBreakpoint(sourceUri, startLine, startColumn, charLength);
    Breakpoint bp = knownBreakpoints.get(bId);

    if (bp == null) {
      WebDebugger.log("RootBreakpoint: " + bId);
      Source source = webDebugger.getSource(sourceUri);
      assert source != null : "TODO: handle problem somehow? defer breakpoint creation on source loading? ugh...";

      SourceSection rootSS = source.createSection(startLine, startColumn, charLength);
      Set<RootNode> roots = webDebugger.getRootNodesBySource(source);
      for (RootNode root : roots) {
        if (rootSS.equals(root.getSourceSection())) {
          FindRootTagNode finder = new FindRootTagNode();
          root.accept(finder);
          ExpressionNode rootExpression = finder.getResult();
          assert rootExpression.getSourceSection() != null;

          ensureOpenDebuggerSession();
          bp = Breakpoint.newBuilder(rootExpression.getSourceSection()).
              build();
          debuggerSession.install(bp);
          bp.setCondition(BreakWhenActivatedByAsyncMessage.INSTANCE);
          knownBreakpoints.put(bId, bp);
        }
      }
    }
    return bp;
  }

  public synchronized void addReceiverBreakpoint(final URI sourceUri, final int startLine,
      final int startColumn, final int charLength) {
    SectionBreakpoint bId = new SectionBreakpoint(sourceUri, startLine, startColumn, charLength);
    assert !receiverBreakpoints.containsKey(bId) : "The receiver breakpoint is already saved";
    receiverBreakpoints.putIfAbsent(bId, new ReceiverBreakpoint(bId));

    receiverBreakpointVersion.invalidate();
    receiverBreakpointVersion = Truffle.getRuntime().createAssumption();
  }

  public static final class BreakpointInfo {
    public final ReceiverBreakpoint breakpoint;
    public final Assumption receiverBreakpointVersion;

    public BreakpointInfo(final ReceiverBreakpoint bp,
        final Assumption receiverBreakpointVersion) {
      this.breakpoint    = bp;
      this.receiverBreakpointVersion = receiverBreakpointVersion;
    }

    public boolean hasBreakpoint() { return breakpoint != null; }
    public boolean noBreakpoint()  { return breakpoint == null; }
  }

  public static class ReceiverBreakpoint {
    private SectionBreakpoint id;
    private boolean isEnabled;
    private Assumption unchanged;

    public ReceiverBreakpoint(final SectionBreakpoint id) {
      this.id   = id;
      isEnabled = true;
      unchanged = Truffle.getRuntime().createAssumption("unchanged breakpoint");
    }

    public synchronized void setEnabled(final boolean isEnabled) {
      if (this.isEnabled != isEnabled) {
        this.isEnabled = isEnabled;
        unchanged.invalidate();
        unchanged = Truffle.getRuntime().createAssumption("unchanged breakpoint");
      }
    }

    public boolean isEnabled() {
      return unchanged.isValid() && isEnabled;
    }

    /**
     * TODO: redundant, just a work around for the DSL, which has an issue with ! currently.
     */
    public boolean isDisabled() {
      return !isEnabled();
    }

    public Assumption getAssumption() {
      return unchanged;
    }
  }

  public synchronized BreakpointInfo hasReceiverBreakpoint(final SectionBreakpoint section) {
    ReceiverBreakpoint bp = receiverBreakpoints.get(section);
    return new BreakpointInfo(bp, receiverBreakpointVersion);
  }

  public BreakpointId getBreakpointId(final URI sourceUri,
      final int startLine, final int startColumn, final int charLength) {
    Set<BreakpointId> ids = knownBreakpoints.keySet();

    for (BreakpointId breakpointId : ids) {
      if (breakpointId instanceof SectionBreakpoint) {
        BreakpointId bId = new SectionBreakpoint(sourceUri, startLine, startColumn, charLength);
        SectionBreakpoint sb = (SectionBreakpoint) breakpointId;
        if (sb.equals(bId)) {
          return sb;
        }

      } else if (breakpointId instanceof LineBreakpoint) {
        BreakpointId bId = new LineBreakpoint(sourceUri, startLine);
        LineBreakpoint lb = (LineBreakpoint) breakpointId;
        if (lb.equals(bId)) {
          return lb;
        }
      }
    }

    return null;
  }
}
