package tools.debugger.session;

import tools.SourceCoordinate.FullSourceCoordinate;
import tools.debugger.FrontendConnector;


/**
 * Breakpoint on the RootTag node of a method, if the method was activated
 * asynchronously.
 *
 * <p>The method is identified by the source section info of the breakpoint.
 */
public class AsyncMessageReceiverBreakpoint extends SectionBreakpoint {
  public AsyncMessageReceiverBreakpoint(final boolean enabled, final FullSourceCoordinate coord) {
    super(enabled, coord);
  }

  /**
   * Note: Meant for use by serialization.
   */
  protected AsyncMessageReceiverBreakpoint() {
    super();
  }

  @Override
  public void registerOrUpdate(final FrontendConnector frontend) {
    frontend.registerOrUpdate(this);
  }
}
