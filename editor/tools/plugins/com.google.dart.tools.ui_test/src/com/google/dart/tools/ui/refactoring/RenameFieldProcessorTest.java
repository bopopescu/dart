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
package com.google.dart.tools.ui.refactoring;

import com.google.dart.tools.core.model.CompilationUnit;
import com.google.dart.tools.core.model.Field;
import com.google.dart.tools.core.test.util.TestProject;
import com.google.dart.tools.internal.corext.refactoring.rename.RenameFieldProcessor;
import com.google.dart.tools.ui.internal.refactoring.RenameSupport;

import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import static org.fest.assertions.Assertions.assertThat;

/**
 * Test for {@link RenameFieldProcessor}.
 */
public final class RenameFieldProcessorTest extends RefactoringTest {
  /**
   * Uses {@link RenameSupport} to rename {@link Field}.
   */
  private static void renameField(Field field, String newName) throws Exception {
    TestProject.waitForAutoBuild();
    RenameSupport renameSupport = RenameSupport.create(field, newName);
    IWorkbenchWindow workbenchWindow = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
    renameSupport.perform(workbenchWindow.getShell(), workbenchWindow);
  }

  /**
   * Just for coverage of {@link RenameFieldProcessor} accessors.
   */
  public void test_accessors() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int test = 1;",
        "}");
    Field field = findElement("test = 1;");
    // do check
    RenameFieldProcessor processor = new RenameFieldProcessor(field);
    assertEquals(RenameFieldProcessor.IDENTIFIER, processor.getIdentifier());
    assertEquals("test", processor.getCurrentElementName());
  }

  public void test_badNewName_alreadyNamed() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int test = 1;",
        "}");
    Field field = findElement("test = 1;");
    // try to rename
    String source = testUnit.getSource();
    try {
      renameField(field, "test");
      fail();
    } catch (InterruptedException e) {
    }
    // error should be displayed
    assertThat(openInformationMessages).isEmpty();
    assertThat(showStatusMessages).hasSize(1);
    assertEquals("Choose another name.", showStatusMessages.get(0));
    // no source changes
    assertEquals(source, testUnit.getSource());
  }

  public void test_badNewName_enclosingTypeHasField() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int test = 1;",
        "  int newName = 2;",
        "}");
    Field field = findElement("test = 1;");
    // try to rename
    String source = testUnit.getSource();
    try {
      renameField(field, "newName");
      fail();
    } catch (InterruptedException e) {
    }
    // error should be displayed
    assertThat(openInformationMessages).isEmpty();
    assertThat(showStatusMessages).hasSize(1);
    assertEquals(
        "Enclosing type 'A' in 'Test/Test.dart' already declares member with name 'newName'",
        showStatusMessages.get(0));
    // no source changes
    assertEquals(source, testUnit.getSource());
  }

  public void test_badNewName_enclosingTypeHasMethod() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int test = 1;",
        "  newName() {}",
        "}");
    Field field = findElement("test = 1;");
    // try to rename
    String source = testUnit.getSource();
    try {
      renameField(field, "newName");
      fail();
    } catch (InterruptedException e) {
    }
    // error should be displayed
    assertThat(openInformationMessages).isEmpty();
    assertThat(showStatusMessages).hasSize(1);
    assertEquals(
        "Enclosing type 'A' in 'Test/Test.dart' already declares member with name 'newName'",
        showStatusMessages.get(0));
    // no source changes
    assertEquals(source, testUnit.getSource());
  }

  public void test_badNewName_shouldBeLowerCase() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int test = 1;",
        "}");
    Field field = findElement("test = 1;");
    // try to rename
    showStatusCancel = false;
    renameField(field, "NAME");
    // warning should be displayed
    assertThat(openInformationMessages).isEmpty();
    assertThat(showStatusMessages).hasSize(1);
    assertEquals(
        "By convention, field names usually start with a lowercase letter",
        showStatusMessages.get(0));
    // status was warning, so rename was done
    assertTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int NAME = 1;",
        "}");
  }

  public void test_OK_multipleUnits_onReference() throws Exception {
    setUnitContent(
        "Test1.dart",
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int test = 1;",
        "  int bar = 2;",
        "  f1() {",
        "    test = 3;",
        "    bar = 4;",
        "  }",
        "}");
    setUnitContent(
        "Test2.dart",
        "// filler filler filler filler filler filler filler filler filler filler",
        "f2() {",
        "  A a = new A();",
        "  a.test = 5;",
        "}");
    setUnitContent(
        "Test3.dart",
        "// filler filler filler filler filler filler filler filler filler filler",
        "class B extends A {",
        "  f3() {",
        "    test = 6;",
        "  }",
        "}");
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "#library('test');",
        "#source('Test1.dart');",
        "#source('Test2.dart');",
        "#source('Test3.dart');");
    // get units, because they have not library
    CompilationUnit unit1 = testProject.getUnit("Test1.dart");
    CompilationUnit unit2 = testProject.getUnit("Test2.dart");
    CompilationUnit unit3 = testProject.getUnit("Test3.dart");
    // find Field to rename
    Field field = findElement(unit2, "test = 5;");
    // do rename
    renameField(field, "newName");
    assertUnitContent(
        unit1,
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int newName = 1;",
        "  int bar = 2;",
        "  f1() {",
        "    newName = 3;",
        "    bar = 4;",
        "  }",
        "}");
    assertUnitContent(
        unit2,
        "// filler filler filler filler filler filler filler filler filler filler",
        "f2() {",
        "  A a = new A();",
        "  a.newName = 5;",
        "}");
    assertUnitContent(
        unit3,
        "// filler filler filler filler filler filler filler filler filler filler",
        "class B extends A {",
        "  f3() {",
        "    newName = 6;",
        "  }",
        "}");
  }

  public void test_OK_singleUnit_onDeclaration() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int test = 1;",
        "  int bar = 2;",
        "  f1() {",
        "    test = 3;",
        "    bar = 4;",
        "  }",
        "}",
        "f2() {",
        "  A a = new A();",
        "  a.test = 5;",
        "}",
        "class B extends A {",
        "  f3() {",
        "    test = 6;",
        "  }",
        "}");
    Field field = findElement("test = 1;");
    // do rename
    renameField(field, "newName");
    assertTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int newName = 1;",
        "  int bar = 2;",
        "  f1() {",
        "    newName = 3;",
        "    bar = 4;",
        "  }",
        "}",
        "f2() {",
        "  A a = new A();",
        "  a.newName = 5;",
        "}",
        "class B extends A {",
        "  f3() {",
        "    newName = 6;",
        "  }",
        "}");
  }

  public void test_OK_singleUnit_onDeclaration_withoutInitializer() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  var test; // rename here",
        "}",
        "");
    Field field = findElement("test; // rename here");
    // do rename
    renameField(field, "newName");
    assertTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  var newName; // rename here",
        "}",
        "");
  }

  public void test_OK_singleUnit_onReference() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int test = 1;",
        "  int bar = 2;",
        "  f1() {",
        "    test = 3;",
        "    bar = 4;",
        "  }",
        "}",
        "f2() {",
        "  A a = new A();",
        "  a.test = 5;",
        "}",
        "class B extends A {",
        "  f3() {",
        "    test = 6;",
        "  }",
        "}");
    Field field = findElement("test = 5;");
    // do rename
    renameField(field, "newName");
    assertTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int newName = 1;",
        "  int bar = 2;",
        "  f1() {",
        "    newName = 3;",
        "    bar = 4;",
        "  }",
        "}",
        "f2() {",
        "  A a = new A();",
        "  a.newName = 5;",
        "}",
        "class B extends A {",
        "  f3() {",
        "    newName = 6;",
        "  }",
        "}");
  }

  public void test_OK_singleUnit_onReference_inSubClass() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  var test = 1;",
        "}",
        "class B extends A {",
        "  f() {",
        "    test = 2;",
        "  }",
        "}");
    Field field = findElement("test = 2;");
    // do rename
    renameField(field, "newName");
    assertTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  var newName = 1;",
        "}",
        "class B extends A {",
        "  f() {",
        "    newName = 2;",
        "  }",
        "}");
  }

  public void test_OK_singleUnit_withThisFieldConstructor() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int test = 1;",
        "  A(this.test) {",
        "  }",
        "  f1() {",
        "    test = 2;",
        "  }",
        "}");
    Field field = findElement("test = 1;");
    // do rename
    renameField(field, "newName");
    assertTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int newName = 1;",
        "  A(this.newName) {",
        "  }",
        "  f1() {",
        "    newName = 2;",
        "  }",
        "}");
  }

  public void test_postCondition_hasFieldOverride_inSubType() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  var test = 1;",
        "}",
        "class B extends A {",
        "}",
        "class C extends B {",
        "  newName() {}",
        "}",
        "");
    Field field = findElement("test = 1;");
    // try to rename
    String source = testUnit.getSource();
    try {
      renameField(field, "newName");
      fail();
    } catch (InterruptedException e) {
    }
    // error should be displayed
    assertThat(openInformationMessages).isEmpty();
    assertThat(showStatusMessages).hasSize(1);
    assertEquals(
        "Type 'C' in 'Test/Test.dart' declares method 'newName' which will shadow renamed field",
        showStatusMessages.get(0));
    // no source changes
    assertEquals(source, testUnit.getSource());
  }

  public void test_postCondition_hasFieldOverride_inSuperType() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  newName() {}",
        "}",
        "class B extends A {",
        "}",
        "class C extends B {",
        "  var test = 2;",
        "}",
        "");
    Field field = findElement("test = 2;");
    // try to rename
    String source = testUnit.getSource();
    try {
      renameField(field, "newName");
      fail();
    } catch (InterruptedException e) {
    }
    // error should be displayed
    assertThat(openInformationMessages).isEmpty();
    assertThat(showStatusMessages).hasSize(1);
    assertEquals(
        "Type 'A' in 'Test/Test.dart' declares method 'newName' which will be shadowed by renamed field",
        showStatusMessages.get(0));
    // no source changes
    assertEquals(source, testUnit.getSource());
  }

  public void test_postCondition_hasLocalVariableHiding_inSubType() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  var test = 1;",
        "}",
        "class B extends A {",
        "  foo() {",
        "    var newName;",
        "  }",
        "}",
        "");
    Field field = findElement("test = 1;");
    // try to rename
    String source = testUnit.getSource();
    try {
      renameField(field, "newName");
      fail();
    } catch (InterruptedException e) {
    }
    // error should be displayed
    assertThat(openInformationMessages).isEmpty();
    assertThat(showStatusMessages).hasSize(1);
    assertEquals(
        "Method 'B.foo()' in 'Test/Test.dart' declares local variable 'newName' which will shadow renamed field",
        showStatusMessages.get(0));
    // no source changes
    assertEquals(source, testUnit.getSource());
  }

  public void test_postCondition_hasParameterHiding_inSubType() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  var test = 1;",
        "}",
        "class B extends A {",
        "  foo(var newName) {",
        "  }",
        "}",
        "");
    Field field = findElement("test = 1;");
    // try to rename
    String source = testUnit.getSource();
    try {
      renameField(field, "newName");
      fail();
    } catch (InterruptedException e) {
    }
    // error should be displayed
    assertThat(openInformationMessages).isEmpty();
    assertThat(showStatusMessages).hasSize(1);
    assertEquals(
        "Method 'B.foo()' in 'Test/Test.dart' declares parameter 'newName' which will shadow renamed field",
        showStatusMessages.get(0));
    // no source changes
    assertEquals(source, testUnit.getSource());
  }

  public void test_postCondition_shadowsTopLevel_class() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  var test = 1;",
        "}",
        "class newName {",
        "}",
        "");
    check_postCondition_shadowsTopLevel("type");
  }

  public void test_postCondition_shadowsTopLevel_functionTypeAlias() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "typedef newName(int p);",
        "class A {",
        "  var test = 1;",
        "}",
        "");
    check_postCondition_shadowsTopLevel("function type alias");
  }

  public void test_postCondition_shadowsTopLevel_otherLibrary() throws Exception {
    setUnitContent(
        "Lib.dart",
        "// filler filler filler filler filler filler filler filler filler filler",
        "#library('Lib');",
        "var newName;");
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "#library('Test');",
        "#import('Lib.dart');",
        "class A {",
        "  var test = 1;",
        "}",
        "");
    check_postCondition_shadowsTopLevel("Lib.dart", "variable");
  }

  public void test_postCondition_shadowsTopLevel_variable() throws Exception {
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "var newName;",
        "class A {",
        "  var test = 1;",
        "}",
        "");
    check_postCondition_shadowsTopLevel("variable");
  }

  public void test_preCondition_hasCompilationErrors() throws Exception {
    setUnitContent(
        "Test1.dart",
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int test = 1;",
        "  f1() {",
        "    test = 3;",
        "  }",
        "}");
    setUnitContent(
        "Test2.dart",
        "// filler filler filler filler filler filler filler filler filler filler",
        "f2() {",
        "  A a = new A();",
        "  a.test = 5;",
        "}",
        "somethingBad");
    setTestUnitContent(
        "// filler filler filler filler filler filler filler filler filler filler",
        "#library('test');",
        "#source('Test1.dart');",
        "#source('Test2.dart');");
    // get units, because they have not library
    CompilationUnit unit1 = testProject.getUnit("Test1.dart");
    CompilationUnit unit2 = testProject.getUnit("Test2.dart");
    Field field = findElement(unit1, "test = 1;");
    // try to rename
    showStatusCancel = false;
    renameField(field, "newName");
    // warning should be displayed
    assertThat(openInformationMessages).isEmpty();
    assertThat(showStatusMessages).hasSize(1);
    assertEquals(
        "Code modification may not be accurate as affected resource 'Test/Test2.dart' has compile errors.",
        showStatusMessages.get(0));
    // status was warning, so rename was done
    assertUnitContent(
        unit1,
        "// filler filler filler filler filler filler filler filler filler filler",
        "class A {",
        "  int newName = 1;",
        "  f1() {",
        "    newName = 3;",
        "  }",
        "}");
    assertUnitContent(
        unit2,
        "// filler filler filler filler filler filler filler filler filler filler",
        "f2() {",
        "  A a = new A();",
        "  a.newName = 5;",
        "}",
        "somethingBad");
  }

  private void check_postCondition_shadowsTopLevel(String shadowName) throws Exception {
    check_postCondition_shadowsTopLevel("Test.dart", shadowName);
  }

  private void check_postCondition_shadowsTopLevel(String unitName, String shadowName)
      throws Exception {
    Field field = findElement("test = 1;");
    // try to rename
    String source = testUnit.getSource();
    try {
      renameField(field, "newName");
      fail();
    } catch (InterruptedException e) {
    }
    // error should be displayed
    assertThat(openInformationMessages).isEmpty();
    assertThat(showStatusMessages).hasSize(1);
    assertEquals("File 'Test/"
        + unitName
        + "' in library 'Test' declares top-level "
        + shadowName
        + " 'newName' which will shadow renamed field", showStatusMessages.get(0));
    // no source changes
    assertEquals(source, testUnit.getSource());
  }
}
