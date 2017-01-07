package som.primitives;

import som.interpreter.nodes.ExpressionNode;
import som.interpreter.nodes.GlobalNode;
import som.interpreter.nodes.GlobalNode.UninitializedGlobalReadWithoutErrorNode;
import som.interpreter.nodes.SOMNode;
import som.primitives.Primitives.Specializer;
import som.primitives.SystemPrims.BinarySystemNode;
import som.vm.NotYetImplementedException;
import som.vm.Universe;
import som.vm.constants.Classes;
import som.vm.constants.Nil;
import som.vmobjects.SObject;
import som.vmobjects.SSymbol;

import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;


@ImportStatic(SystemPrims.class)
@Primitive(klass = "System", selector = "global:",
           specializer = GlobalPrim.IsSystemObject.class)
public abstract class GlobalPrim extends BinarySystemNode {
  protected GlobalPrim(boolean eagWrap, final SourceSection source) {
    super(eagWrap, source);
  }

  @Child private GetGlobalNode getGlobal = new UninitializedGetGlobal(0);

  @Specialization(guards = "receiverIsSystemObject(receiver)")
  public final Object doSObject(final VirtualFrame frame, final DynamicObject receiver, final SSymbol argument) {
    return getGlobal.getGlobal(frame, argument);
  }
  
  public static class IsSystemObject extends Specializer<ExpressionNode> {
    public IsSystemObject(final Primitive prim, final NodeFactory<ExpressionNode> fact) { super(prim, fact); }

    @Override
    public boolean matches(final Object[] args, final ExpressionNode[] argNodess) {
      try{
        return SObject.getSOMClass((DynamicObject) args[0]) == Classes.systemClass;
      } catch (ClassCastException e){
        return false;
      }
    }
  }


  private abstract static class GetGlobalNode extends SOMNode {
    protected static final int INLINE_CACHE_SIZE = 6;

    private GetGlobalNode() { super(null); }

    public abstract Object getGlobal(VirtualFrame frame, SSymbol argument);

    @Override
    public ExpressionNode getFirstMethodBodyNode() {
      throw new NotYetImplementedException();
    }
  }

  private static final class UninitializedGetGlobal extends GetGlobalNode {
    private final int depth;

    public UninitializedGetGlobal(final int depth) {
      this.depth = depth;
    }

    @Override
    public Object getGlobal(final VirtualFrame frame, final SSymbol argument) {
      return specialize(argument).
          getGlobal(frame, argument);
    }

    private GetGlobalNode specialize(final SSymbol argument) {
      if (depth < INLINE_CACHE_SIZE) {
        return replace(new CachedGetGlobal(argument, depth));
      } else {
        GetGlobalNode head = this;
        while (head.getParent() instanceof GetGlobalNode) {
          head = (GetGlobalNode) head.getParent();
        }
        return head.replace(new GetGlobalFallback());
      }
    }
  }

  private static final class CachedGetGlobal extends GetGlobalNode {
    private final int depth;
    private final SSymbol name;
    @Child private GlobalNode getGlobal;
    @Child private GetGlobalNode next;

    public CachedGetGlobal(final SSymbol name, final int depth) {
      this.depth = depth;
      this.name  = name;
      getGlobal = new UninitializedGlobalReadWithoutErrorNode(name, null);
      next = new UninitializedGetGlobal(this.depth + 1);
    }

    @Override
    public Object getGlobal(final VirtualFrame frame, final SSymbol argument) {
      if (name == argument) {
        return getGlobal.executeGeneric(frame);
      } else {
        return next.getGlobal(frame, argument);
      }
    }
  }

  private static final class GetGlobalFallback extends GetGlobalNode {

    private final Universe universe;

    public GetGlobalFallback() {
      this.universe = Universe.getCurrent();
    }

    @Override
    public Object getGlobal(final VirtualFrame frame, final SSymbol argument) {
      Object result = universe.getGlobal(argument);
      return result != null ? result : Nil.nilObject;
    }
  }
}
