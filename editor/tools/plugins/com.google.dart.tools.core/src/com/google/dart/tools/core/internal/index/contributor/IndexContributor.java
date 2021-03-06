/*
 * Copyright (c) 2012, the Dart project authors.
 * 
 * Licensed under the Eclipse Public License v1.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.dart.tools.core.internal.index.contributor;

import com.google.dart.compiler.ast.ASTVisitor;
import com.google.dart.compiler.ast.DartArrayAccess;
import com.google.dart.compiler.ast.DartBinaryExpression;
import com.google.dart.compiler.ast.DartClass;
import com.google.dart.compiler.ast.DartClassMember;
import com.google.dart.compiler.ast.DartDeclaration;
import com.google.dart.compiler.ast.DartExpression;
import com.google.dart.compiler.ast.DartField;
import com.google.dart.compiler.ast.DartFieldDefinition;
import com.google.dart.compiler.ast.DartFunction;
import com.google.dart.compiler.ast.DartFunctionExpression;
import com.google.dart.compiler.ast.DartFunctionObjectInvocation;
import com.google.dart.compiler.ast.DartFunctionTypeAlias;
import com.google.dart.compiler.ast.DartIdentifier;
import com.google.dart.compiler.ast.DartLabel;
import com.google.dart.compiler.ast.DartMethodDefinition;
import com.google.dart.compiler.ast.DartMethodInvocation;
import com.google.dart.compiler.ast.DartNewExpression;
import com.google.dart.compiler.ast.DartNode;
import com.google.dart.compiler.ast.DartParameterizedTypeNode;
import com.google.dart.compiler.ast.DartPropertyAccess;
import com.google.dart.compiler.ast.DartRedirectConstructorInvocation;
import com.google.dart.compiler.ast.DartStatement;
import com.google.dart.compiler.ast.DartSuperConstructorInvocation;
import com.google.dart.compiler.ast.DartTypeNode;
import com.google.dart.compiler.ast.DartUnaryExpression;
import com.google.dart.compiler.ast.DartUnit;
import com.google.dart.compiler.ast.DartUnqualifiedInvocation;
import com.google.dart.compiler.common.SourceInfo;
import com.google.dart.compiler.parser.Token;
import com.google.dart.compiler.resolver.ClassElement;
import com.google.dart.compiler.resolver.ConstructorElement;
import com.google.dart.compiler.resolver.FieldElement;
import com.google.dart.compiler.resolver.MethodElement;
import com.google.dart.compiler.resolver.VariableElement;
import com.google.dart.compiler.type.InterfaceType;
import com.google.dart.compiler.type.Type;
import com.google.dart.tools.core.DartCore;
import com.google.dart.tools.core.DartCoreDebug;
import com.google.dart.tools.core.index.Element;
import com.google.dart.tools.core.index.Location;
import com.google.dart.tools.core.index.Relationship;
import com.google.dart.tools.core.index.Resource;
import com.google.dart.tools.core.internal.index.store.IndexStore;
import com.google.dart.tools.core.internal.index.util.ElementFactory;
import com.google.dart.tools.core.internal.index.util.ResourceFactory;
import com.google.dart.tools.core.model.CompilationUnit;
import com.google.dart.tools.core.model.DartLibrary;
import com.google.dart.tools.core.model.DartModelException;
import com.google.dart.tools.core.utilities.collections.IntStack;
import com.google.dart.tools.core.utilities.io.PrintStringWriter;

import java.util.ArrayList;

import javax.lang.model.element.TypeElement;

/**
 * Instances of the class <code>IndexContributor</code> visit an AST structure to compute the data
 * and relationships to be contributed to an index.
 */
public class IndexContributor extends ASTVisitor<Void> {
  /*
   * TODO(brianwilkerson) This class does not find or record implicit references, such as the
   * implicit invocation of toString() when an expression is embedded in a string interpolation
   * expression.
   */

  /**
   * The index to which data and relationships will be contributed.
   */
  private IndexStore index;

  /**
   * The library containing the compilation unit that is being visited.
   */
  private DartLibrary library;

  /**
   * The compilation unit that is being visited.
   */
  private CompilationUnit compilationUnit;

  /**
   * The cached source of the compilation unit being visited, or <code>null</code> if the source has
   * not yet been accessed.
   */
  private String sourceCode;

  /**
   * A resource representing the compilation unit that defines the library containing the
   * compilation unit being visited. This can be the same as the compilation unit being visited.
   */
  private Resource libraryResource;

  /**
   * An element representing the compilation unit that defines the library containing the
   * compilation unit being visited.
   */
  private Element libraryElement;

  /**
   * A resource representing the compilation unit being visited.
   */
  private Resource compilationUnitResource;

  /**
   * A stack whose top element (the element with the largest index) is an element representing the
   * inner-most enclosing scope.
   */
  private ArrayList<Element> elementStack = new ArrayList<Element>();

  /**
   * The number of milliseconds taken to convert AST nodes or elements into core model elements.
   */
  private long bindingTime = 0L;

  /**
   * The number of relationships that have been added to the index by this contributor. Used for
   * debugging purposes.
   */
  private int relationshipCount = 0;

  /**
   * A writer used to produce tracing output. This is used so that all of the tracing output will
   * appear together in a single entry in the log.
   */
  private PrintStringWriter traceWriter;

  /**
   * The element id used for library elements.
   */
  private static final String LIBRARY_ELEMENT_ID = "#library";

  /**
   * A marker that is used to indicate that the source for the compilation unit cannot be accessed.
   */
  private static String MISSING_SOURCE = "";

  /**
   * A stack containing one value for each name scope that has been entered, where the values are a
   * count of the number of unnamed functions that have been found within that scope. These counts
   * are used to synthesize a name for those functions. The innermost scope is at the top of the
   * stack.
   */
  private IntStack unnamedFunctionCount = new IntStack();

  /**
   * Initialize a newly created contributor to contribute data and relationships to the given index
   * while processing the AST structure associated with the given compilation unit.
   * 
   * @param index the index to which data and relationships will be contributed
   * @param compilationUnit the compilation unit that will be visited
   * @throws DartModelException if the library associated with the compilation unit does not have a
   *           defining compilation unit
   */
  public IndexContributor(IndexStore index, CompilationUnit compilationUnit)
      throws DartModelException {
    this.index = index;
    library = compilationUnit.getLibrary();
    this.compilationUnit = compilationUnit;
    libraryResource = getResource(library.getDefiningCompilationUnit());
    libraryElement = new Element(libraryResource, LIBRARY_ELEMENT_ID);
    compilationUnitResource = getResource(compilationUnit);
    if (DartCoreDebug.TRACE_INDEX_CONTRIBUTOR) {
      traceWriter = new PrintStringWriter();
      traceWriter.print("Contributions from ");
      traceWriter.println(compilationUnit.getElementName());
      traceWriter.println();
    }
  }

  public long getBindingTime() {
    return bindingTime;
  }

  /**
   * Return the number of relationships that have been added to the index by this contributor.
   * 
   * @return the number of relationships that have been added to the index by this contributor
   */
  public int getRelationshipCount() {
    return relationshipCount;
  }

  /**
   * Write any accumulated tracing information to the log.
   */
  public void logTrace() {
    if (traceWriter != null) {
      DartCore.logInformation(traceWriter.toString());
    }
  }

  @Override
  public Void visitArrayAccess(DartArrayAccess node) {
    com.google.dart.compiler.resolver.Element element = node.getElement();
    if (element instanceof MethodElement) {
      // TODO(brianwilkerson) Find the real source range associated with the operator.
      DartExpression index = node.getKey();
      processMethodInvocation(index.getSourceInfo().getOffset() - 1,
          index.getSourceInfo().getLength() + 2, (MethodElement) element);
    } else {
      notFound("array access", node);
    }
    return super.visitArrayAccess(node);
  }

  @Override
  public Void visitBinaryExpression(DartBinaryExpression node) {
    Token operatorToken = node.getOperator();
    if (operatorToken.isUserDefinableOperator()) {
      com.google.dart.compiler.resolver.Element element = node.getElement();
      if (element instanceof MethodElement) {
        String operator = operatorToken.getSyntax();
        DartExpression leftOperand = node.getArg1();
        DartExpression rghtOperand = node.getArg2();
        int offset = findOffset(operator, leftOperand.getSourceInfo().getOffset()
            + leftOperand.getSourceInfo().getLength(), rghtOperand.getSourceInfo().getOffset() - 1);
        if (offset < 0) {
          // TODO(brianwilkerson) Handle a missing offset.
        }
        processMethodInvocation(offset, operator.length(), (MethodElement) element);
      } else {
        notFound("binary expression: " + operatorToken.getSyntax(), node);
      }
    }
    return super.visitBinaryExpression(node);
  }

  @Override
  public Void visitClass(DartClass node) {
    enterScope(getElement(node));
    try {
      processClass(node);
      super.visitClass(node);
    } finally {
      exitScope();
    }
    return null;
  }

  @Override
  public Void visitField(DartField node) {
    enterScope(getElement(node));
    try {
      processField(node.getName(), node.getElement());
      super.visitField(node);
    } finally {
      exitScope();
    }
    return null;
  }

  @Override
  public Void visitFieldDefinition(DartFieldDefinition node) {
    // TODO(brianwilkerson) We need to decide how we're going to represent references to the type:
    // one reference from the class that contains the field declaration, or one reference for each
    // field being declared.
    DartTypeNode type = node.getTypeNode();
    if (type != null) {
      type.accept(this);
    }
    for (DartField field : node.getFields()) {
      visitField(field);
    }
    return null;
  }

  @Override
  public Void visitFunction(DartFunction node) {
    if (node.getParent() instanceof DartMethodDefinition) {
      super.visitFunction(node);
      return null;
    }
    enterScope(getElement(node));
    try {
      processFunction(node);
      super.visitFunction(node);
    } finally {
      exitScope();
    }
    return null;
  }

  @Override
  public Void visitFunctionObjectInvocation(DartFunctionObjectInvocation node) {
    com.google.dart.compiler.resolver.Element element = node.getElement();
    if (element instanceof MethodElement) {
      processMethodInvocation(getIdentifier(node.getTarget()), (MethodElement) element);
    } else {
      notFound("function invocation: [" + (element == null ? "null" : element.toString()) + "] "
          + node.toString(), node);
    }
    return super.visitFunctionObjectInvocation(node);
  }

  @Override
  public Void visitFunctionTypeAlias(DartFunctionTypeAlias node) {
    enterScope(getElement(node));
    try {
      processFunctionTypeAlias(node);
      super.visitFunctionTypeAlias(node);
    } finally {
      exitScope();
    }
    return null;
  }

  @Override
  public Void visitIdentifier(DartIdentifier node) {
    if (isNameInDeclaration(node)) {
      return null;
    }
    com.google.dart.compiler.resolver.Element element = node.getElement();
    if (element == null) {
      DartNode parent = node.getParent();
      if (parent instanceof DartTypeNode) {
        DartNode grandparent = parent.getParent();
        if (grandparent instanceof DartNewExpression) {
          element = ((DartNewExpression) grandparent).getElement();
        }
        if (element == null) {
          Type type = ((DartTypeNode) parent).getType();
          if (type instanceof InterfaceType) {
            processTypeReference(node, (InterfaceType) type);
          }
          return super.visitIdentifier(node);
        }
      } else if (parent instanceof DartMethodInvocation) {
        DartMethodInvocation invocation = (DartMethodInvocation) parent;
        if (node == invocation.getFunctionName()) {
          element = invocation.getElement();
        }
      } else if (parent instanceof DartPropertyAccess) {
        DartPropertyAccess access = (DartPropertyAccess) parent;
        if (node == access.getName()) {
          element = access.getElement();
        }
      } else if (parent instanceof DartUnqualifiedInvocation) {
        DartUnqualifiedInvocation invocation = (DartUnqualifiedInvocation) parent;
        if (node == invocation.getTarget()) {
          element = invocation.getElement();
        }
      } else if (parent instanceof DartParameterizedTypeNode) {
        DartParameterizedTypeNode typeNode = (DartParameterizedTypeNode) parent;
        DartNode grandparent = typeNode.getParent();
        if (grandparent instanceof DartClass) {
          DartClass classDef = (DartClass) grandparent;
          if (typeNode == classDef.getDefaultClass()) {
            InterfaceType defaultClass = classDef.getElement().getDefaultClass();
            if (defaultClass != null) {
              element = defaultClass.getElement();
            }
          }
        }
      }
    }
    if (element instanceof ClassElement) {
      processTypeReference(node, ((ClassElement) element).getType());
    } else if (element instanceof FieldElement) {
      boolean isAssignedTo = isAssignedTo(node);
      Element indexElement = getElement((FieldElement) element, !isAssignedTo, isAssignedTo);
      Location location = getLocation(node);
      if (isAssignedTo) {
        if (isQualified(node)) {
          recordRelationship(indexElement, IndexConstants.IS_MODIFIED_BY_QUALIFIED, location);
        } else {
          recordRelationship(indexElement, IndexConstants.IS_MODIFIED_BY_UNQUALIFIED, location);
        }
      } else {
        if (isQualified(node)) {
          recordRelationship(indexElement, IndexConstants.IS_ACCESSED_BY_QUALIFIED, location);
        } else {
          recordRelationship(indexElement, IndexConstants.IS_ACCESSED_BY_UNQUALIFIED, location);
        }
      }
    }
    return super.visitIdentifier(node);
  }

  @Override
  public Void visitMethodDefinition(DartMethodDefinition node) {
    if (node.getParent() instanceof DartField) {
      super.visitMethodDefinition(node);
      return null;
    }
    enterScope(getElement(node));
    try {
      processMethodDefinition(node);
      super.visitMethodDefinition(node);
    } finally {
      exitScope();
    }
    return null;
  }

  @Override
  public Void visitMethodInvocation(DartMethodInvocation node) {
    com.google.dart.compiler.resolver.Element element = node.getElement();
    if (element == null) {
      element = node.getElement();
    }
    if (element instanceof MethodElement) {
      processMethodInvocation(node.getFunctionName(), (MethodElement) element);
    } else {
      notFound("method invocation: " + node.toString(), node);
    }
    return super.visitMethodInvocation(node);
  }

  @Override
  public Void visitNewExpression(DartNewExpression node) {
    com.google.dart.compiler.resolver.Element element = node.getElement();
    if (element instanceof MethodElement) {
      DartNode className = node.getConstructor();
      processMethodInvocation(getIdentifier(className), (MethodElement) element);
    } else {
      notFound("new expression", node);
    }
    return super.visitNewExpression(node);
  }

  @Override
  public Void visitRedirectConstructorInvocation(DartRedirectConstructorInvocation node) {
    com.google.dart.compiler.resolver.Element element = node.getElement();
    if (element instanceof MethodElement) {
      processMethodInvocation(node.getName(), (MethodElement) element);
    } else {
      notFound("redirect constructor invocation", node);
    }
    return super.visitRedirectConstructorInvocation(node);
  }

  @Override
  public Void visitSuperConstructorInvocation(DartSuperConstructorInvocation node) {
    com.google.dart.compiler.resolver.Element element = node.getElement();
    if (element instanceof MethodElement) {
      // TODO(brianwilkerson) The name is always null, so we can't record references to the invoked
      // constructor.
      processMethodInvocation(node.getName(), (MethodElement) element);
    } else {
      notFound("super constructor invocation", node);
    }
    return super.visitSuperConstructorInvocation(node);
  }

  @Override
  public Void visitUnaryExpression(DartUnaryExpression node) {
    com.google.dart.compiler.resolver.Element element = node.getElement();
    if (element instanceof MethodElement) {
      String operator = node.getOperator().getSyntax();
      DartExpression operand = node.getArg();
      int offset;
      if (node.isPrefix()) {
        offset = findOffset(operator, node.getSourceInfo().getOffset(),
            operand.getSourceInfo().getOffset() - 1);
      } else {
        offset = findOffset(operator, operand.getSourceInfo().getOffset()
            + operand.getSourceInfo().getLength(), node.getSourceInfo().getOffset()
            + node.getSourceInfo().getLength() - 1);
      }
      if (offset < 0) {
        // TODO(brianwilkerson) Handle a missing offset.
      }
      processMethodInvocation(offset, operator.length(), (MethodElement) element);
    } else {
      notFound("unary expression: " + node.getOperator().getSyntax(), node);
    }
    return super.visitUnaryExpression(node);
  }

  @Override
  public Void visitUnit(DartUnit node) {
    unnamedFunctionCount.push(0);
    try {
      super.visitUnit(node);
    } finally {
      unnamedFunctionCount.pop();
      if (!unnamedFunctionCount.isEmpty()) {
        DartCore.logError(unnamedFunctionCount.size()
            + " scopes entered but not exited while visiting " + compilationUnit.getElementName());
        unnamedFunctionCount.clear();
      }
    }
    return null;
  }

  @Override
  public Void visitUnqualifiedInvocation(DartUnqualifiedInvocation node) {
    com.google.dart.compiler.resolver.Element element = node.getElement();
    if (element instanceof MethodElement) {
      processMethodInvocation(node.getTarget(), (MethodElement) element);
    } else if (element instanceof FieldElement) {
      processField(node.getTarget(), (FieldElement) element);
    } else if (element instanceof VariableElement) {
      processVariable(node.getTarget(), (VariableElement) element);
    } else {
      notFound("unqualified invocation", node);
    }
    return super.visitUnqualifiedInvocation(node);
  }

  /**
   * Enter a new scope represented by the given element.
   * 
   * @param element the element of the scope being entered
   */
  private void enterScope(Element element) {
    elementStack.add(element);
    unnamedFunctionCount.push(0);
  }

  /**
   * Exit the current scope.
   */
  private void exitScope() {
    elementStack.remove(elementStack.size() - 1);
    unnamedFunctionCount.pop();
  }

  /**
   * Return the method defined in the given type that matches the given method.
   * 
   * @param method the method being matched against
   * @param type the type in which the candidate methods are declared
   * @return the method defined in the type that matches the method
   */
  private MethodElement findMatchingMethod(MethodElement method, ClassElement type) {
    if (method instanceof ConstructorElement) {
      for (ConstructorElement candidateMethod : type.getConstructors()) {
        if (matches(method, candidateMethod)) {
          return candidateMethod;
        }
      }
    } else {
      for (com.google.dart.compiler.resolver.Element member : type.getMembers()) {
        if (member instanceof MethodElement) {
          MethodElement candidateMethod = (MethodElement) member;
          if (matches(method, candidateMethod)) {
            return candidateMethod;
          }
        }
      }
    }
    return null;
  }

  /**
   * Return the offset in the source of the first occurrence of the target string that occurs
   * between the given start and end indexes. Return a negative value if the target could not be
   * located.
   * 
   * @param target the string being searched for
   * @param startIndex the smallest index that can be returned
   * @param endIndex the largest index that can be returned
   * @return the location of the given string within the given range
   */
  private int findOffset(String target, int startIndex, int endIndex) {
    String source = getSource();
    if (source == null) {
      return -1;
    }
    // TODO(brianwilkerson) This doesn't handle the possibility that the found occurrence is inside
    // a comment.
    int offset = source.indexOf(target, startIndex);
    if (offset > endIndex - target.length()) {
      return -1;
    }
    return startIndex;
  }

  /**
   * Return the method that the given method overrides, or <code>null</code> if the given method
   * does not override another method.
   * 
   * @param method the method that might override another method
   * @return the method that the given method overrides
   */
  private MethodElement findOverriddenMethod(MethodElement method) {
    com.google.dart.compiler.resolver.Element enclosingElement = method.getEnclosingElement();
    if (!(enclosingElement instanceof ClassElement)) {
      // The element represents a function, and functions cannot override other functions.
      return null;
    }
    ClassElement superclass = getSuperclass((ClassElement) enclosingElement);
    while (superclass != null) {
      MethodElement matchingMethod = findMatchingMethod(method, superclass);
      if (matchingMethod != null) {
        return matchingMethod;
      }
      superclass = getSuperclass(superclass);
    }
    return null;
  }

  /**
   * Return an element representing the given type, or <code>null</code> if the given type cannot be
   * represented as an element.
   * 
   * @param element the type element to be represented
   * @return an element representing the given type
   */
  private Element getElement(ClassElement element) {
    try {
      return ElementFactory.getElement(element);
    } catch (DartModelException exception) {
      DartCore.logError("Could not getElement for class element " + element.getName(), exception);
    }
    return null;
  }

  /**
   * Return an element representing the given type.
   * 
   * @param node the node representing the declaration of the type
   * @return an element representing the given type
   */
  private Element getElement(DartClass node) {
    return new Element(compilationUnitResource,
        ElementFactory.composeElementId(node.getClassName()));
  }

  /**
   * Return an element representing the given field.
   * 
   * @param node the node representing the declaration of the field
   * @return an element representing the given field
   */
  private Element getElement(DartField node) {
    return new Element(compilationUnitResource, ElementFactory.composeElementId(peekElement(),
        node.getName().getName()));
  }

  /**
   * Return an element representing the given function.
   * 
   * @param node the node representing the declaration of the function
   * @return an element representing the given function
   */
  private Element getElement(DartFunction node) {
    String functionName = null;
    DartNode parent = node.getParent();
    if (parent instanceof DartFunctionExpression) {
      functionName = ((DartFunctionExpression) parent).getFunctionName();
    }
    if (functionName == null) {
      functionName = Integer.toString(unnamedFunctionCount.peek());
      unnamedFunctionCount.increment(1);
    }
    return new Element(compilationUnitResource, ElementFactory.composeElementId(peekElement(),
        functionName));
  }

  /**
   * Return an element representing the given function type.
   * 
   * @param node the node representing the declaration of the function type
   * @return an element representing the given function type
   */
  private Element getElement(DartFunctionTypeAlias node) {
    return new Element(compilationUnitResource,
        ElementFactory.composeElementId(node.getName().getName()));
  }

  /**
   * Return an element representing the given method.
   * 
   * @param node the node representing the declaration of the method
   * @return an element representing the given method
   */
  private Element getElement(DartMethodDefinition node) {
    return new Element(compilationUnitResource, ElementFactory.composeElementId(peekElement(),
        toString(node.getName())));
  }

  /**
   * Return an element representing the given field.
   * 
   * @param element the field element to be represented
   * @param allowGetter <code>true</code> if a getter is allowed to be returned
   * @param allowSetter <code>true</code> if a setter is allowed to be returned
   * @return an element representing the given field
   */
  private Element getElement(FieldElement element, boolean allowGetter, boolean allowSetter) {
    try {
      return ElementFactory.getElement(element, allowGetter, allowSetter);
    } catch (DartModelException exception) {
      DartCore.logError("Could not getElement for field element " + element.getName(), exception);
    }
    return null;
  }

  /**
   * Return an element representing the given type, or <code>null</code> if the given type cannot be
   * represented as an element.
   * 
   * @param node the node representing the declaration of the type
   * @return an element representing the given type
   */
  private Element getElement(InterfaceType type) {
    return getElement(type.getElement());
  }

  /**
   * Return an element representing the given method.
   * 
   * @param element the element representing the method
   * @return an element representing the given method
   */
  private Element getElement(MethodElement element) {
    try {
      return ElementFactory.getElement(element);
    } catch (DartModelException exception) {
      DartCore.logError("Could not getElement for method element " + element.getName(), exception);
    }
    return null;
  }

  /**
   * Return the identifier associated with the given node, or <code>null</code> if there is no
   * identifier associated with the node.
   * 
   * @param node the node with which the identifier is associated
   * @return the identifier associated with the given node
   */
  private DartIdentifier getIdentifier(DartNode node) {
    if (node instanceof DartIdentifier) {
      return (DartIdentifier) node;
    } else if (node instanceof DartPropertyAccess) {
      return ((DartPropertyAccess) node).getName();
    } else if (node instanceof DartTypeNode) {
      return getIdentifier(((DartTypeNode) node).getIdentifier());
    }
    DartCore.logInformation("Could not getIdentifier for " + node.getClass().getName());
    return null;
  }

  /**
   * Return a location representing the location of the name of the given type.
   * 
   * @param node the node representing the declaration of the type
   * @return a location representing the location of the name of the given type
   */
  private Location getLocation(DartClass node) {
    return getLocation(peekElement(), node.getName());
  }

  /**
   * Return a location representing the location of the name of the given function.
   * 
   * @param node the node representing the declaration of the function
   * @return a location representing the location of the name of the given function
   */
  private Location getLocation(DartFunction node) {
    com.google.dart.compiler.resolver.Element element = node.getElement();
    if (element instanceof MethodElement) {
      MethodElement method = (MethodElement) element;
      SourceInfo location = method.getNameLocation();
      return getLocation(peekElement(), location.getOffset(), location.getLength());
    }
    return null;
  }

  /**
   * Return a location representing the location of the name of the given function type.
   * 
   * @param node the node representing the declaration of the function type
   * @return a location representing the location of the name of the given function type
   */
  private Location getLocation(DartFunctionTypeAlias node) {
    return getLocation(peekElement(), node.getName());
  }

  /**
   * Return a location representing the given identifier.
   * 
   * @param node the identifier whose location is to be returned
   * @return a location representing the given identifier
   */
  private Location getLocation(DartIdentifier node) {
    return getLocation(peekElement(), node);
  }

  /**
   * Return a location representing the location of the name of the given method.
   * 
   * @param node the node representing the declaration of the method
   * @return a location representing the location of the name of the given method
   */
  private Location getLocation(DartMethodDefinition node) {
    return getLocation(peekElement(), node.getName());
  }

  /**
   * Return a location representing the location of the given node within the given element.
   * 
   * @param element the element containing the location
   * @param node the node representing the source range of the location
   * @return a location representing the location of the given node within the given element
   */
  private Location getLocation(Element element, DartNode node) {
    if (element == null) {
      element = libraryElement;
    }
    return new Location(element, node.getSourceInfo().getOffset(), node.getSourceInfo().getLength());
  }

  /**
   * Return a location representing the location of the offset and length within the given element.
   * 
   * @param element the element containing the location
   * @param offset the offset of the location
   * @param length the length of the location
   * @return a location representing the location of the given offset and length within the given
   *         element
   */
  private Location getLocation(Element element, int offset, int length) {
    return new Location(element, offset, length);
  }

  /**
   * Return a location representing the given offset and length.
   * 
   * @param offset the offset of the location
   * @param length the length of the location
   * @return a location representing the given offset and length
   */
  private Location getLocation(int offset, int length) {
    return getLocation(peekElement(), offset, length);
  }

  private Location getLocation(InterfaceType type) {
    Element typeElement = getElement(type);
    if (typeElement == null) {
      return null;
    }
    SourceInfo info = type.getElement().getNameLocation();
    return new Location(typeElement, info.getOffset(), info.getLength());
  }

  /**
   * Return the resource representing the given compilation unit.
   * 
   * @param compilationUnit the compilation unit to be represented as a resource
   * @return the resource representing the given compilation unit
   */
  private Resource getResource(CompilationUnit compilationUnit) {
    if (compilationUnit != null) {
      try {
        return ResourceFactory.getResource(compilationUnit);
      } catch (DartModelException exception) {
        DartCore.logError("Could not get underlying resource for compilation unit "
            + compilationUnit.getElementName(), exception);
      }
    }
    return libraryResource;
  }

  /**
   * Return the source of the compilation unit being visited, or <code>null</code> if the source
   * cannot be accessed.
   * 
   * @return the source of the compilation unit being visited
   */
  private String getSource() {
    if (sourceCode == null) {
      try {
        sourceCode = compilationUnit.getSource();
      } catch (DartModelException exception) {
        DartCore.logError("Could not access source for " + compilationUnit.getElementName(),
            exception);
        sourceCode = MISSING_SOURCE;
      }
    }
    if (sourceCode == MISSING_SOURCE) {
      return null;
    }
    return sourceCode;
  }

  /**
   * Return the superclass of the given class, or <code>null</code> if the given class does not have
   * a superclass or if the superclass cannot be determined.
   * 
   * @param classElement the class being accessed
   * @return the superclass of the given class
   */
  private ClassElement getSuperclass(ClassElement classElement) {
    InterfaceType superType = classElement.getSupertype();
    if (superType == null) {
      return null;
    }
    return superType.getElement();
  }

  private boolean isAssignedTo(DartIdentifier node) {
    DartNode child = node;
    DartNode parent = child.getParent();
    while (parent != null) {
      if (parent instanceof DartBinaryExpression) {
        DartBinaryExpression binary = (DartBinaryExpression) parent;
        if (binary.getOperator().isAssignmentOperator() && child == binary.getArg1()) {
          return true;
        }
      } else if (parent instanceof DartPropertyAccess) {
        if (child != ((DartPropertyAccess) parent).getName()) {
          return false;
        }
      } else if (parent instanceof DartStatement || parent instanceof DartClassMember<?>
          || parent instanceof DartClass) {
        return false;
      }
      child = parent;
      parent = child.getParent();
    }
    return false;
  }

  /**
   * Return <code>true</code> if the given identifier represents the name in a declaration of that
   * name.
   * 
   * @param node the identifier being tested
   * @return <code>true</code> if the given identifier is the name in a declaration
   */
  private boolean isNameInDeclaration(DartIdentifier node) {
    DartNode parent = node.getParent();
    if (parent instanceof DartDeclaration) {
      return ((DartDeclaration<?>) parent).getName() == node;
    }
    if (parent instanceof DartFunctionExpression) {
      return ((DartFunctionExpression) parent).getName() == node;
    }
    if (parent instanceof DartLabel) {
      return ((DartLabel) parent).getLabel() == node;
    }
    return false;
  }

  private boolean isQualified(DartIdentifier node) {
    return node.getParent() instanceof DartPropertyAccess
        || node.getParent() instanceof DartMethodInvocation;
  }

  /**
   * Return <code>true</code> if the given candidate matches the given target.
   * 
   * @param targetMethod the method being matched against
   * @param candidateMethod the candidate being compared to the target
   * @return <code>true</code> if the candidate matches the target
   */
  private boolean matches(MethodElement targetMethod, MethodElement candidateMethod) {
    return targetMethod.getName().equals(candidateMethod.getName());
  }

  /**
   * If debugging is enabled, report that we were not able to find an element corresponding to the
   * given node.
   * 
   * @param string a description of what the node represents
   * @param node the node that should have had an element associated with it
   */
  private void notFound(String string, DartNode node) {
    if (node == null) {
      notFound(string, -1, 0);
    } else {
      notFound(string, node.getSourceInfo().getOffset(), node.getSourceInfo().getLength());
    }
  }

  /**
   * If debugging is enabled, report that we were not able to find an element corresponding to a
   * node at the given location.
   * 
   * @param string a description of what the node represents
   * @param offset the offset of the node that should have had an element associated with it
   * @param length the length of the node that should have had an element associated with it
   */
  private void notFound(String string, int offset, int length) {
    if (DartCoreDebug.DEBUG_INDEX_CONTRIBUTOR) {
      PrintStringWriter writer = new PrintStringWriter();
      writer.print(string);
      writer.print(" in ");
      writer.print(compilationUnit.getElementName());
      if (offset >= 0) {
        writer.print(" [");
        writer.print(offset);
        writer.print(", ");
        writer.print(offset + length - 1);
        writer.print("]");
      }
      DartCore.logInformation(writer.toString());
    }
  }

  /**
   * Return the element representing the inner-most enclosing scope, or <code>null</code> if we are
   * at the top-level of the compilation unit.
   * 
   * @return the element representing the inner-most enclosing scope
   */
  private Element peekElement() {
    for (int i = elementStack.size() - 1; i >= 0; i--) {
      Element element = elementStack.get(i);
      if (element != null) {
        return element;
      }
    }
    return null;
  }

  /**
   * Record any information implied by the given class definition.
   * 
   * @param node the node representing the definition of the class
   */
  private void processClass(DartClass node) {
    //
    // Record the class as being contained by the workspace and the library.
    //
    if (node.isInterface()) {
      recordRelationship(IndexConstants.UNIVERSE, IndexConstants.DEFINES_INTERFACE,
          getLocation(node));
      recordRelationship(libraryElement, IndexConstants.DEFINES_INTERFACE, getLocation(node));
    } else {
      recordRelationship(IndexConstants.UNIVERSE, IndexConstants.DEFINES_CLASS, getLocation(node));
      recordRelationship(libraryElement, IndexConstants.DEFINES_CLASS, getLocation(node));
    }
    //
    // Record the class as being a subtype of it's supertypes.
    //
    com.google.dart.compiler.resolver.Element binding = node.getElement();
    if (binding instanceof ClassElement) {
      ClassElement classElement = (ClassElement) binding;
      InterfaceType superclass = classElement.getSupertype();
      if (superclass != null) {
        processSupertype(node, superclass);
      }
      for (InterfaceType type : classElement.getInterfaces()) {
        processSupertype(node, type);
      }
    } else {
      notFound("unqualified invocation", node);
    }
  }

  /**
   * Record any information implied by the given field definition.
   * 
   * @param node the node representing the name of the field
   * @param element the element describing the field
   */
  private void processField(DartIdentifier node, FieldElement element) {
    if (element.getEnclosingElement() instanceof TypeElement) {
      recordRelationship(libraryElement, IndexConstants.DEFINES_FIELD, getLocation(node));
    } else {
      recordRelationship(libraryElement, IndexConstants.DEFINES_VARIABLE, getLocation(node));
    }
  }

  /**
   * Record any information implied by the given function definition.
   * 
   * @param node the node representing the definition of the function
   */
  private void processFunction(DartFunction node) {
    recordRelationship(libraryElement, IndexConstants.DEFINES_FUNCTION, getLocation(node));
  }

  /**
   * Record any information implied by the given function type definition.
   * 
   * @param node the node representing the definition of the function type
   */
  private void processFunctionTypeAlias(DartFunctionTypeAlias node) {
    //
    // Record the function type as being contained by the workspace and the library.
    //
    recordRelationship(IndexConstants.UNIVERSE, IndexConstants.DEFINES_FUNCTION_TYPE,
        getLocation(node));
    recordRelationship(libraryElement, IndexConstants.DEFINES_FUNCTION_TYPE, getLocation(node));
  }

  /**
   * Record any information implied by the given method definition.
   * 
   * @param node the node representing the definition of the method
   */
  private void processMethodDefinition(DartMethodDefinition node) {
    recordRelationship(libraryElement, IndexConstants.DEFINES_METHOD, getLocation(node));
    com.google.dart.compiler.resolver.Element element = node.getElement();
    if (element instanceof MethodElement) {
      MethodElement methodElement = (MethodElement) element;
      MethodElement overridenMethodElement = findOverriddenMethod(methodElement);
      if (overridenMethodElement != null) {
        recordRelationship(getElement(overridenMethodElement), IndexConstants.IS_OVERRIDDEN_BY,
            getLocation(node));
      }
    } else {
      notFound("unqualified invocation", node);
    }
  }

  /**
   * Record the invocation of a method or function.
   * 
   * @param methodName the node representing the name of the method being invoked
   * @param binding the element representing the method being invoked
   */
  private void processMethodInvocation(DartIdentifier methodName, MethodElement binding) {
    if (methodName == null || binding == null) {
      notFound("method invocation", methodName);
      return;
    }
    Relationship relationship = isQualified(methodName) ? IndexConstants.IS_INVOKED_BY_QUALIFIED
        : IndexConstants.IS_INVOKED_BY_UNQUALIFIED;
    recordRelationship(getElement(binding), relationship, getLocation(methodName));
  }

  /**
   * Record the invocation of a method or function.
   * 
   * @param offset the offset of the name of the method being invoked
   * @param length the length of the name of the method being invoked
   * @param binding the element representing the method being invoked
   */
  private void processMethodInvocation(int offset, int length, MethodElement binding) {
    if (binding == null) {
      notFound("method invocation", offset, length);
      return;
    }
    recordRelationship(getElement(binding), IndexConstants.IS_INVOKED_BY_QUALIFIED,
        getLocation(offset, length));
  }

  private void processSupertype(DartClass node, InterfaceType binding) {
    if (node.isInterface() == binding.getElement().isInterface()) {
      recordRelationship(getElement(binding), IndexConstants.IS_EXTENDED_BY, getLocation(node));
      recordRelationship(getElement(node), IndexConstants.EXTENDS, getLocation(binding));
    } else {
      recordRelationship(getElement(binding), IndexConstants.IS_IMPLEMENTED_BY, getLocation(node));
      recordRelationship(getElement(node), IndexConstants.IMPLEMENTS, getLocation(binding));
    }
  }

  /**
   * Process the fact that the given node is a reference to the given type.
   * 
   * @param node the node that is a reference to the type
   * @param type the type that is referenced by the node
   */
  private void processTypeReference(DartIdentifier node, InterfaceType type) {
    recordRelationship(getElement(type), IndexConstants.IS_REFERENCED_BY, getLocation(node));
  }

  /**
   * Record any information implied by the given variable definition.
   * 
   * @param node the node representing the name of the variable
   * @param element the element describing the variable
   */
  private void processVariable(DartIdentifier node, VariableElement element) {
  }

  /**
   * Record the given relationship between the given element and the given location.
   * 
   * @param element the element that has the given relationship with the location
   * @param relationship the relationship between the element and the location
   * @param location the location that is related to the element
   */
  private void recordRelationship(Element element, Relationship relationship, Location location) {
    if (element != null) {
      index.recordRelationship(compilationUnitResource, element, relationship, location);
      relationshipCount++;
      if (traceWriter != null) {
        traceWriter.print("   ");
        traceWriter.print(element);
        traceWriter.print(" ");
        traceWriter.print(relationship);
        traceWriter.print(" ");
        traceWriter.print(location);
      }
    }
  }

  /**
   * Convert the given node, which is assumed to represent either a simple or qualified name, into a
   * string corresponding to that name.
   * 
   * @param name the node representing the name to be converted
   * @return a string representing the given name
   */
  private String toString(DartNode name) {
    if (name instanceof DartIdentifier) {
      return ((DartIdentifier) name).getName();
    } else if (name instanceof DartPropertyAccess) {
      DartPropertyAccess access = (DartPropertyAccess) name;
      return toString(access.getQualifier()) + "." + access.getPropertyName();
    }
    return "";
  }
}
