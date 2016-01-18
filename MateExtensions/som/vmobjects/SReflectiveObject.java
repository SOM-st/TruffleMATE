/**
 * Copyright (c) 2015 Guido Chari, gchari@dc.uba.ar
 * LaFHIS lab, Universidad de Buenos Aires, Buenos Aires, Argentina
 * http://www.lafhis.dc.uba.ar
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package som.vmobjects;

import som.vm.Universe;
import som.vm.constants.Nil;

import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectFactory;
import com.oracle.truffle.api.object.ObjectType;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.object.ShapeImpl;
import com.oracle.truffle.object.Transition;
import com.oracle.truffle.object.basic.ShapeBasic;

public class SReflectiveObject extends SObject {
  public static final SSymbol ENVIRONMENT = Universe.current().symbolFor("environment");
  public static final SReflectiveObjectObjectType SREFLECTIVE_OBJECT_TYPE = new SReflectiveObjectObjectType();
  
  protected static final Shape SREFLECTIVE_OBJECT_TEMP_SHAPE = 
      INIT_NIL_SHAPE.createSeparateShape(INIT_NIL_SHAPE.getSharedData())
      .changeType(SREFLECTIVE_OBJECT_TYPE);
      //.defineProperty(ENVIRONMENT, Nil.nilObject, 0);
  
  //protected static final Shape SREFLECTIVE_OBJECT_SHAPE = SREFLECTIVE_OBJECT_TEMP_SHAPE.defineProperty(ENVIRONMENT, Nil.nilObject, 0);    
  protected static final Shape SREFLECTIVE_OBJECT_SHAPE = 
      SREFLECTIVE_OBJECT_TEMP_SHAPE.addProperty(Property.create(ENVIRONMENT, SREFLECTIVE_OBJECT_TEMP_SHAPE.allocator().constantLocation(Nil.nilObject), 0));
      
  public static final DynamicObjectFactory SREFLECTIVE_OBJECT_FACTORY = SREFLECTIVE_OBJECT_SHAPE.createFactory();
  
  //public static final ConstantLocation ENVIRONMENT_LOCATION = (ConstantLocation) SREFLECTIVE_OBJECT_SHAPE.getProperty(ENVIRONMENT).getLocation();
      
  public static final Shape createObjectShapeForClass(final DynamicObject clazz) {
    return new ShapeBasic(SREFLECTIVE_OBJECT_SHAPE.getLayout(), 
        clazz, 
        (ShapeImpl) SREFLECTIVE_OBJECT_SHAPE, 
        SREFLECTIVE_OBJECT_SHAPE.getObjectType(), 
        ((ShapeImpl) SREFLECTIVE_OBJECT_SHAPE).getPropertyMap(),
        new Transition.ObjectTypeTransition(SREFLECTIVE_OBJECT_SHAPE.getObjectType()), 
        SREFLECTIVE_OBJECT_SHAPE.allocator(), 
        SREFLECTIVE_OBJECT_SHAPE.getId());
    //return new ShapeBasic(this, clazz, operations, id);
    //return SREFLECTIVE_OBJECT_SHAPE.createSeparateShape(clazz);
  }
  
  //Todo: Is this method optimizable by caching the location? 
  public static final DynamicObject getEnvironment(final DynamicObject obj) {
    //CompilerAsserts.neverPartOfCompilation("Caller needs to be optimized");
    return (DynamicObject) obj.get(ENVIRONMENT, Nil.nilObject);
    //return (DynamicObject) ENVIRONMENT_LOCATION.get(obj, true);
  }

  public static final void setEnvironment(final DynamicObject obj, final DynamicObject value) {
    //Shape aanewShape = SREFLECTIVE_OBJECT_TEMP_SHAPE.addProperty(Property.create(ENVIRONMENT, SREFLECTIVE_OBJECT_TEMP_SHAPE.allocator().constantLocation(value), 0));
    Shape oldShape = obj.getShape();
    Shape newShape = oldShape.createSeparateShape(obj.getShape().getSharedData()).
        replaceProperty(oldShape.getProperty(ENVIRONMENT), Property.create(ENVIRONMENT, oldShape.allocator().constantLocation(value), 0));
    obj.setShapeAndGrow(oldShape, newShape);
  }
  
  private static final class SReflectiveObjectObjectType extends ObjectType {
    @Override
    public String toString() {
      return "SReflectiveObject";
    }
  }
  
  public static boolean isSReflectiveObject(final DynamicObject obj) {
    //return false;
    return obj.getShape().getObjectType() == SREFLECTIVE_OBJECT_TYPE;
  }
  
  public static boolean isSReflectiveObject(ObjectType type) {
    return type == SREFLECTIVE_OBJECT_TYPE;
  }
}