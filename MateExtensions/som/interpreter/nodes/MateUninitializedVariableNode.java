package som.interpreter.nodes;

import som.compiler.Variable.Local;
import som.interpreter.MateNode;
import som.interpreter.nodes.LocalVariableNode.LocalVariableReadNode;
import som.interpreter.nodes.LocalVariableNode.LocalVariableWriteNode;
import som.interpreter.nodes.LocalVariableNodeFactory.LocalVariableReadNodeGen;
import som.interpreter.nodes.LocalVariableNodeFactory.LocalVariableWriteNodeGen;

import com.oracle.truffle.api.source.SourceSection;


public abstract class MateUninitializedVariableNode extends UninitializedVariableNode
    implements MateNode {

  public MateUninitializedVariableNode(Local variable, int contextLevel,
      SourceSection source) {
    super(variable, contextLevel, source);
  }

  public static final class MateUninitializedVariableReadNode extends UninitializedVariableReadNode {
    public MateUninitializedVariableReadNode(UninitializedVariableReadNode node) {
      super(node.variable, node.getContextLevel(), node.getSourceSection());
    }

    @Override
    protected LocalVariableReadNode specializedNode() {
      return new MateLocalVariableNode.MateLocalVariableReadNode(LocalVariableReadNodeGen.create(variable, getSourceSection()));
    }

    @Override
    public ExpressionNode asMateNode() {
      return null;
    }
  }

  public static final class MateUninitializedVariableWriteNode extends UninitializedVariableWriteNode {
    public MateUninitializedVariableWriteNode(UninitializedVariableWriteNode node) {
      super(node.variable, node.getContextLevel(), node.exp, node.getSourceSection());
    }

    @Override
    protected LocalVariableWriteNode specializedNode() {
      return new MateLocalVariableNode.MateLocalVariableWriteNode(
          LocalVariableWriteNodeGen.create(variable, getSourceSection(), exp));
    }

    @Override
    public ExpressionNode asMateNode() {
      return null;
    }
  }
}
