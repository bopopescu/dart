// Copyright (c) 2012, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
#include <string.h>
#include <stdio.h>

#include "include/dart_api.h"
#include "sqlite3.h"

#define VA_NARGS_IMPL(_1,_2,_3,_4,_5,_6,_7,_8,N,...) N
#define VA_NARGS(...) VA_NARGS_IMPL(__VA_ARGS__, 8, 7, 6, 5, 4, 3, 2, 1)
#define DART_ARG(name, i) Dart_Handle name = Dart_GetNativeArgument(arguments, i);
#define DART_ARGS_0() /*{printf("Entering %s\n", __FUNCTION__);}*/
#define DART_ARGS_1(arg0) DART_ARGS_0() DART_ARG(arg0, 0)
#define DART_ARGS_2(arg0, arg1) DART_ARGS_1(arg0); DART_ARG(arg1, 1)
#define DART_ARGS_3(arg0, arg1, arg2) DART_ARGS_2(arg0, arg1); DART_ARG(arg2, 2)
#define DART_ARGS_4(arg0, arg1, arg2, arg3) DART_ARGS_3(arg0, arg1, arg2); DART_ARG(arg3, 3)

#define DART_ARGS_IMPL1(count, ...) DART_ARGS_ ## count (__VA_ARGS__)
#define DART_ARGS_IMPL0(count, ...) DART_ARGS_IMPL1(count, __VA_ARGS__)
#define DART_ARGS(...) DART_ARGS_IMPL0(VA_NARGS(__VA_ARGS__), __VA_ARGS__)

#define DART_FUNCTION(name) void name(Dart_NativeArguments arguments)
#define DART_RETURN(expr) {Dart_SetReturnValue(arguments, expr); return;}

Dart_NativeFunction ResolveName(Dart_Handle name, int argc);

static Dart_Handle library;

DART_EXPORT Dart_Handle test_extension_Init(Dart_Handle parent_library) {
  if (Dart_IsError(parent_library)) { return parent_library; }

  Dart_Handle result_code = Dart_SetNativeResolver(parent_library, ResolveName);
  if (Dart_IsError(result_code)) return result_code;

  library = Dart_NewPersistentHandle(parent_library);
  return parent_library;
}

void Throw(const char* message) {
  Dart_Handle messageHandle = Dart_NewString(message);
  Dart_PropagateError(Dart_Invoke(library, Dart_NewString("_raiseError"), 1, &messageHandle));
}

void CheckSqlError(sqlite3* db, int result) {
  if (result) {
    Dart_Handle message = Dart_NewString(sqlite3_errmsg(db));
    Dart_PropagateError(Dart_Invoke(library, Dart_NewString("_raiseError"), 1, &message));
  }
}

Dart_Handle CheckDartError(Dart_Handle result, const char* message) {
  if (Dart_IsError(result)) {
    Throw(message);
  }
  return result;
}

Dart_Handle CheckDartUnexpectedError(Dart_Handle result) {
  return CheckDartError(result, Dart_GetError(result));
}

sqlite3* get_db(Dart_Handle db_handle) {
  int64_t db_addr;
  Dart_IntegerToInt64(db_handle, &db_addr);
  return (sqlite3*) db_addr;
}

sqlite3_stmt* get_statement(Dart_Handle statement_handle) {
  int64_t statement_addr;
  Dart_IntegerToInt64(statement_handle, &statement_addr);
  return (sqlite3_stmt*) statement_addr;
}

DART_FUNCTION(New) {
  DART_ARGS(path);

  sqlite3* db;
  const char* cpath;
  CheckDartUnexpectedError(Dart_StringToCString(path, &cpath));
  CheckSqlError(db, sqlite3_open(cpath, &db));
  sqlite3_busy_timeout(db, 100);

  DART_RETURN(Dart_NewInteger((int64_t) db));
}

DART_FUNCTION(Close) {
  DART_ARGS(db_handle);
  sqlite3* db = get_db(db_handle);

  CheckSqlError(db, sqlite3_close(db));

  DART_RETURN(Dart_Null());
}

DART_FUNCTION(Version) {
  DART_RETURN(Dart_NewString(sqlite3_version));
}

DART_FUNCTION(PrepareStatement) {
  DART_ARGS(db_handle, sql_handle);
  sqlite3* db = get_db(db_handle);
  const char* sql;
  CheckDartUnexpectedError(Dart_StringToCString(sql_handle, &sql));
  sqlite3_stmt* stmt;
  CheckSqlError(db, sqlite3_prepare_v2(db, sql, strlen(sql), &stmt, NULL));
  DART_RETURN(Dart_NewInteger((int64_t) stmt));
}

DART_FUNCTION(Reset) {
  DART_ARGS(db_handle, statement_handle);
  sqlite3* db = get_db(db_handle);
  sqlite3_stmt* statement = get_statement(statement_handle);
  CheckSqlError(db, sqlite3_clear_bindings(statement));
  CheckSqlError(db, sqlite3_reset(statement));
}

DART_FUNCTION(Bind) {
  DART_ARGS(db_handle, statement_handle, args);
  sqlite3* db = get_db(db_handle);
  sqlite3_stmt* stmt = get_statement(statement_handle);
  if (!Dart_IsList(args)) {
    Throw("args must be a List");
  }
  int count;
  Dart_ListLength(args, &count);
  if (sqlite3_bind_parameter_count(stmt) != count) {
    Throw("Number of arguments doesn't match number of placeholders");
  }
  for (int i = 0; i < count; i++) {
    Dart_Handle value = Dart_ListGetAt(args, i);
    if (Dart_IsInteger(value)) {
      int64_t result;
      Dart_IntegerToInt64(value, &result);
      CheckSqlError(db, sqlite3_bind_int64(stmt, i + 1, result));
    } else if (Dart_IsDouble(value)) {
      double result;
      Dart_DoubleValue(value, &result);
      CheckSqlError(db, sqlite3_bind_double(stmt, i + 1, result));
    } else if (Dart_IsNull(value)) {
      CheckSqlError(db, sqlite3_bind_null(stmt, i + 1));
    } else if (Dart_IsString(value)) {
      const char* result;
      CheckDartUnexpectedError(Dart_StringToCString(value, &result));
      CheckSqlError(db, sqlite3_bind_text(stmt, i + 1, result, strlen(result), SQLITE_TRANSIENT));
    } else if (Dart_IsByteArray(value)) {
      int count;
      CheckDartUnexpectedError(Dart_ListLength(value, &count));
      unsigned char* result = (unsigned char*) sqlite3_malloc(count);
      for (int j = 0; j < count; j++) {
        Dart_ByteArrayGetUint8At(value, i + 1, &result[j]);
      }
      CheckSqlError(db, sqlite3_bind_blob(stmt, i + 1, result, count, sqlite3_free));
    } else {
      Throw("Invalid parameter type");
    }
  }
}

Dart_Handle get_column_value(sqlite3* db, sqlite3_stmt* statement, int col) {
  int count;
  const unsigned char* binary_data;
  Dart_Handle result;
  switch (sqlite3_column_type(statement, col)) {
    case SQLITE_INTEGER:
      return Dart_NewInteger(sqlite3_column_int64(statement, col));
    case SQLITE_FLOAT:
      return Dart_NewDouble(sqlite3_column_double(statement, col));
    case SQLITE_TEXT:
      return Dart_NewString((const char*) sqlite3_column_text(statement, col));
    case SQLITE_BLOB:
      count = sqlite3_column_bytes(statement, col);
      result = CheckDartUnexpectedError(Dart_NewByteArray(count));
      binary_data = (const unsigned char*) sqlite3_column_blob(statement, col);
      // this is stupid
      for (int i = 0; i < count; i++) {
        Dart_ByteArraySetUint8At(result, i, binary_data[i]);
      }
      return result;
    case SQLITE_NULL:
      return Dart_Null();
    default:
      Throw("Unknown result type");
      return Dart_Null();
  }
}

Dart_Handle get_last_row(sqlite3* db, sqlite3_stmt* statement) {
  int count = sqlite3_column_count(statement);
  Dart_Handle list = CheckDartUnexpectedError(Dart_NewList(count));
  for (int i = 0; i < count; i++) {
    Dart_ListSetAt(list, i, get_column_value(db, statement, i));
  }
  return list;
}

DART_FUNCTION(ColumnInfo) {
  DART_ARGS(db_handle, statement_handle);
  /* sqlite3* db = */get_db(db_handle);
  sqlite3_stmt* statement = get_statement(statement_handle);
  int count = sqlite3_column_count(statement);
  Dart_Handle result = Dart_NewList(count);
  for (int i = 0; i < count; i++) {
    Dart_ListSetAt(result, i, Dart_NewString(sqlite3_column_name(statement, i)));
  }
  DART_RETURN(result);
}

DART_FUNCTION(Step) {
  DART_ARGS(db_handle, statement_handle);
  sqlite3* db = get_db(db_handle);
  sqlite3_stmt* statement = get_statement(statement_handle);
  while (true) {
    int status = sqlite3_step(statement);
    switch (status) {
      case SQLITE_BUSY:
        continue; // TODO: have to roll back transaction?
      case SQLITE_DONE:
        DART_RETURN(Dart_NewInteger(sqlite3_changes(db)));
      case SQLITE_ROW:
        DART_RETURN(get_last_row(db, statement));
      default:
        CheckSqlError(db, status);
        Throw("Unreachable");
    }
  }
}

DART_FUNCTION(CloseStatement) {
  DART_ARGS(db_handle, statement_handle);
  sqlite3* db = get_db(db_handle);
  sqlite3_stmt* statement = get_statement(statement_handle);
  CheckSqlError(db, sqlite3_finalize(statement));
}

#define EXPORT(func, args) if (!strcmp(#func, cname) && argc == args) { return func; }
Dart_NativeFunction ResolveName(Dart_Handle name, int argc) {
  assert(Dart_IsString8(name));
  const char* cname;
  Dart_Handle check_error = Dart_StringToCString(name, &cname);
  if (Dart_IsError(check_error)) Dart_PropagateError(check_error);

  EXPORT(New, 1);
  EXPORT(Close, 1);
  EXPORT(Version, 0);
  EXPORT(PrepareStatement, 2);
  EXPORT(Reset, 2);
  EXPORT(Bind, 3);
  EXPORT(Step, 2);
  EXPORT(ColumnInfo, 2);
  EXPORT(CloseStatement, 2);
  return NULL;
}
