package som.matenodes;

import som.interpreter.SArguments;
import som.matenodes.MateAbstractSemanticNodesFactory.MateEnvironmentSemanticCheckNodeGen;
import som.matenodes.MateAbstractSemanticNodesFactory.MateObjectSemanticCheckNodeGen;
import som.matenodes.MateAbstractSemanticNodesFactory.MateSemanticCheckNodeGen;
import som.vm.MateSemanticsException;
import som.vm.MateUniverse;
import som.vm.constants.ExecutionLevel;
import som.vm.constants.Nil;
import som.vm.constants.ReflectiveOp;
import som.vmobjects.SInvokable;
import som.vmobjects.SMateEnvironment;
import som.vmobjects.SReflectiveObject;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.object.Locations.ConstantLocation;

public abstract class MateAbstractSemanticNodes {

  public static abstract class MateEnvironmentSemanticCheckNode extends Node {
    private final ReflectiveOp reflectiveOperation;

    protected MateEnvironmentSemanticCheckNode(ReflectiveOp operation) {
      super();
      reflectiveOperation = operation;
    }

    public abstract SInvokable executeGeneric(VirtualFrame frame);

    
    @Specialization(guards = "getEnvironment(frame) == null")
    public SInvokable doNoSemanticsInFrame(final VirtualFrame frame) {
      throw new MateSemanticsException();
    }
    
    @Specialization(guards = {"getEnvironment(frame) == environment"})
    public SInvokable doSemanticsInFrame(final VirtualFrame frame,
        @Cached("getEnvironment(frame)") final DynamicObject environment,
        @Cached("methodImplementingOperationOn(environment)") final SInvokable reflectiveMethod) {
        if (reflectiveMethod == null) throw new MateSemanticsException();
        return reflectiveMethod;
    }
    
    public static DynamicObject getEnvironment(VirtualFrame frame){
      return SArguments.getEnvironment(frame);
    }
    
    public SInvokable methodImplementingOperationOn(final DynamicObject environment){
      return SMateEnvironment.methodImplementing(environment, this.reflectiveOperation);
    }
  }

  public static abstract class MateObjectSemanticCheckNode extends Node {

    protected final ReflectiveOp reflectiveOperation;

    protected MateObjectSemanticCheckNode(ReflectiveOp operation) {
      super();
      reflectiveOperation = operation;
    }

    public abstract SInvokable executeGeneric(VirtualFrame frame,
        Object receiver);

    @Specialization(guards = "!isSReflectiveObject(receiver)")
    public SInvokable doStandardSOMForPrimitives(final VirtualFrame frame,
        final DynamicObject receiver) {
      throw new MateSemanticsException();
    }
    
    @Specialization(guards = {"receiver.getShape().getRoot() == rootShape"}, limit = "10")
    public SInvokable doSReflectiveObject(
        final VirtualFrame frame,
        final DynamicObject receiver,
        @Cached("receiver.getShape().getRoot()") final Shape rootShape,
        @Cached("getEnvironmentLocationOf(receiver.getShape())") final ConstantLocation cachedLocation,
        @Cached("getEnvironment(receiver, cachedLocation)") final DynamicObject cachedEnvironment,
        @Cached("environmentReflectiveMethod(cachedEnvironment, reflectiveOperation)") final SInvokable method) {
      if(method != null) {
        return method;
      }
      throw new MateSemanticsException();
    }
    
    @Specialization(contains={"doSReflectiveObject", "doStandardSOMForPrimitives"})
    public SInvokable doMegamorphicReceiver(
        final VirtualFrame frame,
        final DynamicObject receiver) {
      SInvokable method = environmentReflectiveMethod(SReflectiveObject.getEnvironment(receiver), this.reflectiveOperation);
      if (method == null) throw new MateSemanticsException();
      return method;
    }
    
    protected static SInvokable environmentReflectiveMethod(
        DynamicObject environment, ReflectiveOp operation) {
      if (environment == Nil.nilObject){
        return null;
      }
      return SMateEnvironment.methodImplementing(environment, operation);
    }
    
    public static DynamicObject getEnvironment(DynamicObject receiver, ConstantLocation location){
      return (DynamicObject)location.get(receiver);
    }
    
    public static ConstantLocation getEnvironmentLocationOf(Shape shape){
      return (ConstantLocation) shape.getProperty(SReflectiveObject.ENVIRONMENT).getLocation();
    }
    
    public static boolean isSReflectiveObject(DynamicObject object){
      return SReflectiveObject.isSReflectiveObject(object);
    }
  }

  public static abstract class MateSemanticCheckNode extends Node {

    @Child MateEnvironmentSemanticCheckNode environment;
    @Child MateObjectSemanticCheckNode      object;

    public abstract SInvokable execute(final VirtualFrame frame,
        Object[] arguments);

    protected Object doMateDispatchNode(final VirtualFrame frame,
        final SInvokable environment, final SReflectiveObject receiver) {
      throw new MateSemanticsException();
    }

    public MateSemanticCheckNode(MateEnvironmentSemanticCheckNode env,
        MateObjectSemanticCheckNode obj) {
      super();
      environment = env;
      object = obj;
    }

    public static MateSemanticCheckNode createForFullCheck(
        SourceSection source, ReflectiveOp operation) {
      return MateSemanticCheckNodeGen.create(
          MateEnvironmentSemanticCheckNodeGen.create(operation),
          MateObjectSemanticCheckNodeGen.create(operation));
    }

    @Specialization(assumptions = "getMateDeactivatedAssumption()")
    protected SInvokable mateDeactivated(final VirtualFrame frame, Object[] arguments) {
      throw new MateSemanticsException();
    }
    
    @Specialization(guards = "!executeBase(frame)" , 
        assumptions = "getMateActivatedAssumption()")
    protected SInvokable executeSOM(final VirtualFrame frame, Object[] arguments) {
      throw new MateSemanticsException();
    }

    @Specialization(guards = "executeBase(frame)",
        assumptions = "getMateActivatedAssumption()")
    protected SInvokable executeSemanticChecks(final VirtualFrame frame,
        Object[] arguments) {
      if (arguments[0] instanceof DynamicObject){
        try{
          return environment.executeGeneric(frame);
        } catch (MateSemanticsException e) {
          return object.executeGeneric(frame, arguments[0]);
        }
      }
      throw new MateSemanticsException(); 
    }

    public MateSemanticCheckNode(final SourceSection source,
        ReflectiveOp operation) {
      super(source);
      environment = MateEnvironmentSemanticCheckNodeGen.create(operation);
      object = MateObjectSemanticCheckNodeGen.create(operation);
    }

    public static boolean executeBase(VirtualFrame frame) {
      return SArguments.getExecutionLevel(frame) == ExecutionLevel.Base;
    }
    
    public static Assumption getMateDeactivatedAssumption() {
      return MateUniverse.current().getMateDeactivatedAssumption();
    }
    
    public static Assumption getMateActivatedAssumption() {
      return MateUniverse.current().getMateActivatedAssumption();
    }
  }
}
