package som.interpreter.nodes.nary;

import som.interpreter.nodes.AbstractMessageSpecializationsFactory;
import som.interpreter.nodes.ExpressionNode;
import som.interpreter.nodes.PreevaluatedExpression;
import som.vmobjects.SSymbol;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.SourceSection;


@NodeChildren({
  @NodeChild(value = "receiver",  type = ExpressionNode.class),
  @NodeChild(value = "firstArg",  type = ExpressionNode.class),
  @NodeChild(value = "secondArg", type = ExpressionNode.class),
  @NodeChild(value = "thirdArg",  type = ExpressionNode.class)})
public abstract class QuaternaryExpressionNode extends EagerlySpecializableNode
    implements ExpressionWithReceiver, PreevaluatedExpression {

  public abstract ExpressionNode getFirstArg();
  public abstract ExpressionNode getSecondArg();
  public abstract ExpressionNode getThirdArg();

  public QuaternaryExpressionNode(final boolean eagerlyWrapped, final SourceSection sourceSection) {
    super(eagerlyWrapped, sourceSection);
  }

  public abstract Object executeEvaluated(VirtualFrame frame,
      Object receiver, Object firstArg, Object secondArg,
      Object thirdArg);

  @Override
  public final Object doPreEvaluated(final VirtualFrame frame,
      final Object[] arguments) {
    return executeEvaluated(frame, arguments[0], arguments[1], arguments[2], arguments[3]);
  }

  public Object[] evaluateArguments(final VirtualFrame frame) {
    Object[] arguments = new Object[4];
    arguments[0] = this.getReceiver().executeGeneric(frame);
    arguments[1] = this.getFirstArg().executeGeneric(frame);
    arguments[2] = this.getSecondArg().executeGeneric(frame);
    arguments[3] = this.getThirdArg().executeGeneric(frame);
    return arguments;
  }

  @Override
  public EagerPrimitive wrapInEagerWrapper(
      final EagerlySpecializableNode prim, final SSymbol selector,
      final ExpressionNode[] arguments, final VirtualFrame frame, final AbstractMessageSpecializationsFactory factory) {
    return factory.quaternaryPrimitiveFor(selector,
        arguments[0], arguments[1], arguments[2],
        arguments[3], this, frame);
  }
}
