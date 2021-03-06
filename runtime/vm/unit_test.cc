// Copyright (c) 2012, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

#include <stdio.h>

#include "vm/unit_test.h"

#include "vm/assembler.h"
#include "vm/ast_printer.h"
#include "vm/code_generator.h"
#include "vm/compiler.h"
#include "vm/dart_api_impl.h"
#include "vm/disassembler.h"
#include "vm/longjump.h"
#include "vm/parser.h"
#include "vm/virtual_memory.h"


namespace dart {

DECLARE_FLAG(bool, disassemble);


TestCaseBase* TestCaseBase::first_ = NULL;
TestCaseBase* TestCaseBase::tail_ = NULL;


TestCaseBase::TestCaseBase(const char* name) : next_(NULL), name_(name) {
  if (first_ == NULL) {
    first_ = this;
  } else {
    tail_->next_ = this;
  }
  tail_ = this;
}


void TestCaseBase::RunAll() {
  TestCaseBase* test = first_;
  while (test != NULL) {
    test->RunTest();
    test = test->next_;
  }
}


static Dart_Handle LibraryTagHandler(Dart_LibraryTag tag,
                                     Dart_Handle library,
                                     Dart_Handle url,
                                     Dart_Handle import_map) {
  if (!Dart_IsLibrary(library)) {
    return Dart_Error("not a library");
  }
  if (!Dart_IsString8(url)) {
    return Dart_Error("url is not a string");
  }
  const char* url_chars = NULL;
  Dart_Handle result = Dart_StringToCString(url, &url_chars);
  if (Dart_IsError(result)) {
    return Dart_Error("accessing url characters failed");
  }
  static const char* kDartScheme = "dart:";
  static const intptr_t kDartSchemeLen = strlen(kDartScheme);
  // If the URL starts with "dart:" then it is not modified as it will be
  // handled by the VM internally.
  if (strncmp(url_chars, kDartScheme, kDartSchemeLen) == 0) {
    if (tag == kCanonicalizeUrl) {
      return url;
    }
    return Dart_Error("unexpected tag encountered %d", tag);
  }
  return Dart_Error("unsupported url encountered %s", url_chars);
}


Dart_Handle TestCase::LoadTestScript(const char* script,
                                     Dart_NativeEntryResolver resolver) {
  Dart_Handle url = Dart_NewString(TestCase::url());
  Dart_Handle source = Dart_NewString(script);
  Dart_Handle import_map = Dart_NewList(0);
  Dart_Handle lib = Dart_LoadScript(url, source, LibraryTagHandler, import_map);
  DART_CHECK_VALID(lib);
  Dart_Handle result = Dart_SetNativeResolver(lib, resolver);
  DART_CHECK_VALID(result);
  return lib;
}


Dart_Handle TestCase::lib() {
  Dart_Handle url = Dart_NewString(TestCase::url());
  Dart_Handle lib = Dart_LookupLibrary(url);
  DART_CHECK_VALID(lib);
  ASSERT(Dart_IsLibrary(lib));
  return lib;
}


Dart_Handle TestCase::library_handler(Dart_LibraryTag tag,
                                      Dart_Handle library,
                                      Dart_Handle url,
                                      Dart_Handle import_map) {
  if (tag == kCanonicalizeUrl) {
    return url;
  }
  return Api::Success(Isolate::Current());
}


uword AssemblerTest::Assemble() {
  const Code& code = Code::Handle(Code::FinalizeCode(name_, assembler_));
  if (FLAG_disassemble) {
    OS::Print("Code for test '%s' {\n", name_);
    const Instructions& instructions =
        Instructions::Handle(code.instructions());
    uword start = instructions.EntryPoint();
    Disassembler::Disassemble(start, start + assembler_->CodeSize());
    OS::Print("}\n");
  }
  const Instructions& instructions = Instructions::Handle(code.instructions());
  return instructions.EntryPoint();
}


CodeGenTest::CodeGenTest(const char* name)
  : function_(Function::ZoneHandle()),
    node_sequence_(new SequenceNode(Scanner::kDummyTokenIndex,
                                    new LocalScope(NULL, 0, 0))),
    default_parameter_values_(Array::ZoneHandle()) {
  ASSERT(name != NULL);
  const String& function_name = String::ZoneHandle(String::NewSymbol(name));
  function_ = Function::New(
      function_name, RawFunction::kFunction, true, false, 0);
  function_.set_result_type(Type::Handle(Type::DynamicType()));
  // Add function to a class and that class to the class dictionary so that
  // frame walking can be used.
  Class& cls = Class::ZoneHandle();
  const Script& script = Script::Handle();
  cls = Class::New(function_name, script, Scanner::kDummyTokenIndex);
  const Array& functions = Array::Handle(Array::New(1));
  functions.SetAt(0, function_);
  cls.SetFunctions(functions);
  Library& lib = Library::Handle(Library::CoreLibrary());
  lib.AddClass(cls);
}


void CodeGenTest::Compile() {
  Assembler assembler;
  ParsedFunction parsed_function(function_);
  parsed_function.SetNodeSequence(node_sequence_);
  parsed_function.set_instantiator(NULL);
  parsed_function.set_default_parameter_values(default_parameter_values_);
  parsed_function.AllocateVariables();
  bool retval;
  Isolate* isolate = Isolate::Current();
  EXPECT(isolate != NULL);
  LongJump* base = isolate->long_jump_base();
  LongJump jump;
  isolate->set_long_jump_base(&jump);
  if (setjmp(*jump.Set()) == 0) {
    CodeGenerator code_gen(&assembler, parsed_function);
    code_gen.GenerateCode();
    const char* function_fullname = function_.ToFullyQualifiedCString();
    const Code& code =
        Code::Handle(Code::FinalizeCode(function_fullname, &assembler));
    if (FLAG_disassemble) {
      OS::Print("Code for function '%s' {\n", function_fullname);
      const Instructions& instructions =
          Instructions::Handle(code.instructions());
      uword start = instructions.EntryPoint();
      Disassembler::Disassemble(start, start + assembler.CodeSize());
      OS::Print("}\n");
    }
    function_.SetCode(code);
    retval = true;
  } else {
    retval = false;
  }
  EXPECT(retval);
  isolate->set_long_jump_base(base);
}


bool CompilerTest::TestCompileScript(const Library& library,
                                     const Script& script) {
  Isolate* isolate = Isolate::Current();
  ASSERT(isolate != NULL);
  const Error& error = Error::Handle(Compiler::Compile(library, script));
  return error.IsNull();
}


bool CompilerTest::TestCompileFunction(const Function& function) {
  Isolate* isolate = Isolate::Current();
  ASSERT(isolate != NULL);
  ASSERT(ClassFinalizer::AllClassesFinalized());
  const Error& error = Error::Handle(Compiler::CompileFunction(function));
  return error.IsNull();
}

}  // namespace dart
