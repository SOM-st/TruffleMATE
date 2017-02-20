package som.primitives.arithmetic;

import java.math.BigInteger;

import som.interpreter.nodes.nary.UnaryExpressionNode;
import som.primitives.Primitive;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.source.SourceSection;

@GenerateNodeFactory
@Primitive(klass = "Int", selector = "sqrt", eagerSpecializable = false)
@Primitive(klass = "Double", selector = "sqrt", eagerSpecializable = false)
@Primitive(selector = "sqrt", receiverType = {Long.class, BigInteger.class, Double.class})
public abstract class SqrtPrim extends UnaryExpressionNode {

  public SqrtPrim(final boolean eagWrap, final SourceSection source) {
    super(eagWrap, source);
  }

  private final BranchProfile longReturn   = BranchProfile.create();
  private final BranchProfile doubleReturn = BranchProfile.create();

  @Specialization
  public final Object doLong(final long receiver) {
    double result = Math.sqrt(receiver);

    if (result == Math.rint(result)) {
      longReturn.enter();
      return (long) result;
    } else {
      doubleReturn.enter();
      return result;
    }
  }

  @Specialization
  public final double doBigInteger(final BigInteger receiver) {
    return Math.sqrt(receiver.doubleValue());
  }

  @Specialization
  public final double doDouble(final double receiver) {
    return Math.sqrt(receiver);
  }
}
