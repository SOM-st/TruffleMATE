package som.primitives;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;

import som.interpreter.nodes.nary.UnaryExpressionNode;
import som.vmobjects.SAbstractObject;
import som.vmobjects.SArray;
import som.vmobjects.SClass;


public class ClassPrims {

  @GenerateNodeFactory
  @Primitive(klass = "Class", selector = "name", eagerSpecializable = false)
  @ImportStatic(SClass.class)
  public abstract static class NamePrim extends UnaryExpressionNode {
    public NamePrim(final boolean eagWrap, final SourceSection source) {
      super(eagWrap, source);
    }

    @TruffleBoundary
    @Specialization(guards = "isSClass(receiver)")
    public final SAbstractObject doSClass(final DynamicObject receiver) {
      // CompilerAsserts.neverPartOfCompilation("Class>>NamePrim");
      return SClass.getName(receiver);
    }
  }

  @GenerateNodeFactory
  @Primitive(klass = "Class", selector = "superclass")
  @ImportStatic(SClass.class)
  public abstract static class SuperClassPrim extends UnaryExpressionNode {
    public SuperClassPrim(final boolean eagWrap, final SourceSection source) {
      super(eagWrap, source);
    }

    @Specialization(guards = "isSClass(receiver)")
    public final DynamicObject doSClass(final DynamicObject receiver) {
      // CompilerAsserts.neverPartOfCompilation("Class>>SuperClassPrim");
      return SClass.getSuperClass(receiver);
    }
  }

  @GenerateNodeFactory
  @Primitive(klass = "Class", selector = "methods")
  @ImportStatic(SClass.class)
  public abstract static class InstanceInvokablesPrim extends UnaryExpressionNode {
    public InstanceInvokablesPrim(final boolean eagWrap, final SourceSection source) {
      super(eagWrap, source);
    }

    @TruffleBoundary
    @Specialization(guards = "isSClass(receiver)")
    public final SArray doSClass(final DynamicObject receiver) {
      CompilerAsserts.neverPartOfCompilation("Class>>InstanceInvokablesPrim");
      return SClass.getInstanceInvokables(receiver);
    }
  }

  @GenerateNodeFactory
  @Primitive(klass = "Class", selector = "fields")
  @ImportStatic(SClass.class)
  public abstract static class InstanceFieldsPrim extends UnaryExpressionNode {
    public InstanceFieldsPrim(final boolean eagWrap, final SourceSection source) {
      super(eagWrap, source);
    }

    @Specialization(guards = "isSClass(receiver)")
    public final SArray doSClass(final DynamicObject receiver) {
      CompilerAsserts.neverPartOfCompilation("Class>>instanceFields");
      return SClass.getInstanceFields(receiver);
    }
  }
}
